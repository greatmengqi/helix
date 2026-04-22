package agent

import kyo.*
import kyo.AllowUnsafe.embrace.danger
import scala.util.chaining.*

/** Agent 可运行 demo：起一个 REPL，配上启发式 mock LLM + 若干内置工具。
  *
  * 运行：`mill kyo.runMain agent.AgentApp`
  *
  * 演示完整的 effect wire 顺序：
  * {{{
  *   Agent.repl(registry, llm)                 // Unit < (IO & Abort[Throwable])
  *     .pipe(Log.withConsoleLogger(_))         // 注入 logger
  *     .pipe(IO.Unsafe.run(_))                 // 消 IO   effect
  *     .pipe(Abort.run[Throwable](_))          // 消 Abort，得到 Result
  *     .eval                                   // 抽出最终值
  * }}}
  *
  * 注：`.pipe(f)` 需要 `f` 是 plain function，当 `f` 带 `using Frame` 等上下文参数时 Scala
  * 不会自动 eta-expand，要写成 `.pipe(f(_))` 显式桥接。Kyo 的 effect handler 几乎都有
  * Frame context，都适用这一写法。
  *
  * `Tool.run` / `LLM.run` 不在最外层 pipe，而是被 `Agent.repl` 内部 per-turn 应用——必要， 见
  * AgentLoop.scala 的 repl docstring。
  */
object AgentApp {

  /** 启发式 mock LLM：看 history 尾部决定下一步。
    *
    *   - 这一 turn 还没有 Tool 结果 → 按关键词决定调哪个工具
    *   - 已经有 Tool 结果 → 综合成 Answer
    *   - 关键词不匹配 → 兜底 Answer 说明能力范围
    *
    * 没有任何可变状态：同一个 history 进来，输出确定，天然可测、可重放。 真实 LLM 实现需要走 HTTP，签名要扩成 `< (IO &
    * Abort[Throwable])`。
    */
  object HeuristicLLM extends LLMClient {

    private val cities = Seq(
      "shanghai" -> "shanghai",
      "上海" -> "shanghai",
      "beijing" -> "beijing",
      "北京" -> "beijing",
      "tokyo" -> "tokyo",
      "东京" -> "tokyo"
    )

    private val addPattern = """(-?\d+)\s*[+＋加]\s*(-?\d+)""".r

    private def extractCity(text: String): String = {
      val lower = text.toLowerCase
      cities
        .collectFirst {
          case (k, v) if lower.contains(k) => v
        }
        .getOrElse("unknown")
    }

    /** 识别用户输入里出现的所有城市（去重保序）。 */
    private def extractCities(text: String): List[String] = {
      val lower = text.toLowerCase
      cities
        .collect {
          case (k, v) if lower.contains(k) => v
        }
        .distinct
        .toList
    }

    /** 从 weather 工具返回值里逆向解出城市名——形如 `shanghai: 晴，28°C`。 */
    private def cityFromToolResult(s: String): Option[String] =
      s.split(":", 2).headOption.map(_.trim).filter(_.nonEmpty)

    def complete(
        history: List[Message]
    ): LLMResponse < (IO & Abort[Throwable]) = {
      val lastUserIdx = history.lastIndexWhere(_.role == Role.User)
      if (lastUserIdx < 0) LLMResponse.Answer("（空输入）")
      else {
        val user = history(lastUserIdx).content
        val lower = user.toLowerCase
        // 只看最近一条 User 之后产出的 Tool 结果——跨 turn 的旧 Tool 消息不误伤
        val afterLast = history.drop(lastUserIdx + 1)
        val toolResults = afterLast.filter(_.role == Role.Tool).map(_.content)

        // ===== 触发多步推理的 compare 分支 =====
        // "对比/比较 A 和 B 的天气"：
        //   第 1 次 complete → 看到 0 个 Tool 结果 → 发 weather(A)
        //   第 2 次 complete → 看到 1 个 Tool 结果 → 发 weather(B)
        //   第 3 次 complete → 看到 2 个 Tool 结果 → Answer 综合
        // 这样一次 loop(input) 内循环走 3 轮 LLM 推理 + 2 次 tool 调用，可见。
        val wantsCompare =
          lower.contains("对比") || lower.contains("比较") || lower.contains("vs")
        val citiesInInput = extractCities(user)

        if (wantsCompare && citiesInInput.size >= 2) {
          val alreadyQueried = toolResults.flatMap(cityFromToolResult).toSet
          val remaining = citiesInInput.filterNot(alreadyQueried.contains)
          if (remaining.isEmpty)
            LLMResponse.Answer(
              s"对比结果：${toolResults.mkString("；")}"
            )
          else
            LLMResponse.ToolCalls(
              List(ToolInvocation("weather", Map("city" -> remaining.head)))
            )
        } else if (toolResults.nonEmpty) {
          LLMResponse.Answer(
            s"好的，查询结果：${toolResults.mkString("；")}"
          )
        } else if (lower.contains("错误") || lower.contains("test error")) {
          // 刻意调不存在的工具，触发 errorHandling middleware 折叠路径——
          // 用户肉眼能看到："[tool] 抛 Abort → errorHandling 折成 <tool_error> + log → LLM 看到反馈 → 综合 Answer"
          LLMResponse.ToolCalls(
            List(ToolInvocation("nonexistent_tool", Map("probe" -> "demo")))
          )
        } else if (lower.contains("天气") || lower.contains("weather")) {
          LLMResponse.ToolCalls(
            List(
              ToolInvocation("weather", Map("city" -> extractCity(user)))
            )
          )
        } else
          addPattern.findFirstMatchIn(user) match {
            case Some(m) =>
              LLMResponse.ToolCalls(
                List(
                  ToolInvocation(
                    "add",
                    Map("a" -> m.group(1), "b" -> m.group(2))
                  )
                )
              )
            case None =>
              LLMResponse.Answer(
                s"不确定怎么处理『$user』。试试『上海天气』、『1 + 2』或『对比上海和北京的天气』。"
              )
          }
      }
    }
  }

  /** 内置工具。和 test 侧的 TestTools 对应，但各自独立（main / test 源集分开）。 */
  object DemoTools extends ToolRegistry {
    def call(
        name: String,
        args: Map[String, String]
    ): String < (IO & Abort[Throwable]) =
      name match {
        case "weather" =>
          val city = args.getOrElse("city", "unknown")
          if (city == "unknown") "暂不支持的城市"
          else s"$city: 晴，28°C"
        case "add" =>
          val a = args.getOrElse("a", "0").toInt
          val b = args.getOrElse("b", "0").toInt
          s"${a + b}"
        case other =>
          Abort.fail(new RuntimeException(s"unknown tool: $other"))
      }
  }

  private val banner: String =
    """|────────────────────────────────────
       | agent demo (mill kyo.runMain agent.AgentApp)
       |   试试：
       |     上海天气                     单步
       |     1 + 2                         单步
       |     对比上海和北京的天气         多步（看内循环展开）
       |     错误测试                     errorHandling 折叠 + session 存活
       |     exit
       |────────────────────────────────────""".stripMargin

  def main(args: Array[String]): Unit = {
    println(banner)

    // Agent.repl 的新 API：registry / llm 作为参数传入（而不是 pipe(Tool.run) / pipe(LLM.run)），
    // 这样 per-turn 内能 discharge Abort，单 turn 失败不炸 session。
    //
    // Tool middleware 排布：errorHandling 在外、logging 在内——
    //   logging 在内层靠近真实调用，能拿到原始 Abort 异常、记 `tool.call.fail` 再重抛
    //   errorHandling 在外层把异常折成 `<tool_error>` 字符串，同时记 `tool.call.recover`
    //
    // LLM middleware 排布：caching 在外、logging 在内——
    //   cache 命中时直接 return，跳过下层 logging（避免假的 `llm.call.start`/`ok` 误导 telemetry）
    //   cache 未命中时，logging 内层正常记一次真实调用
    //
    // 日志走 `kyo.Log`：最外层 `Log.withConsoleLogger` 注入 console 实现，middleware 内部
    // `Log.info/error` 自动找到。换 SLF4J 只需 classpath 多一个 backend，零代码改动。
    // middleware 栈作为一等值：先 compose 拼结构，再 pipe 喂 backend。
    // 不抽 `chain` 函数——stdlib compose 已经是 endomorphism monoid 的组合子，再套一层命名
    // 聚合不引入新能力（详见 CLAUDE.md 决策 #13）。
    val toolStack: ToolMiddleware =
      ToolMiddleware.errorHandling compose ToolMiddleware.logging
    val llmStack: LLMMiddleware =
      LLMMiddleware.caching compose LLMMiddleware.logging

    // 效应 discharge 流水线：每个 `.pipe(xxx)` 消一层效应，左到右读 = 从最内层开始剥皮。
    // 和文件头 ScalaDoc 的 wire 顺序示例对齐。
    val result: Result[Throwable, Unit] =
      Agent
        .repl(
          registry = DemoTools.pipe(toolStack),
          llm = HeuristicLLM.pipe(llmStack)
        )
        .pipe(Log.withConsoleLogger(_))
        .pipe(IO.Unsafe.run(_))
        .pipe(Abort.run[Throwable](_))
        .eval

    result match {
      case Result.Success(_) => ()
      case Result.Failure(e) =>
        java.lang.System.err.println(s"[agent failed] ${e.getMessage}")
        sys.exit(1)
      case Result.Panic(e) =>
        java.lang.System.err.println(s"[agent panicked] ${e.getMessage}")
        e.printStackTrace()
        sys.exit(2)
    }
  }
}
