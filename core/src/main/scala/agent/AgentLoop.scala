package agent

import kyo.*
import kyo.kernel.*
import scala.util.chaining.*
import java.io.IOException

// ====================== 领域模型 ======================

enum Role derives CanEqual {
  case User, Assistant, Tool, System
}

final case class Message(role: Role, content: String) {
  override def toString: String = s"[$role] $content"
}

// ====================== Agent 主循环 ======================

object Agent {

  /** 一次 `loop(input)` 内部允许的最大推理步数——超过即 Abort。 集中在一处，REPL / 单发 overload /
    * 未来可配置都共享同一默认。
    */
  val DefaultMaxSteps: Int = 6

  /** 主循环（stateful 版）：给定起始 history，跑到 Answer 为止，返回
    * `(最终答案, 完整 history 含本轮所有 tool 轨迹 + Assistant 终答)`。
    *
    * 把 history 作为入参/出参外化后，REPL 可以把它作为跨 turn 状态串起来，
    * 实现多轮对话的累计记忆。单发（single-shot）场景用下面的 `loop(userInput, maxSteps)`
    * overload，完全等价于老接口。
    *
    * 没给 `maxSteps` 默认值——Scala 3 不允许同名 overload 都带 default（生成的 default accessor
    * 名字冲突）。 把 default 保留在 String 版里（老接口语义友好），这里要求调用方显式传——大多数时候传
    * `Agent.DefaultMaxSteps`，需要定制时传自己的值。
    */
  def loop(
      initialHistory: List[Message],
      maxSteps: Int
  ): (String, List[Message]) <
    (LLM & Tool & HistoryRewrite & AgentHalt & ResponseHook & IO &
      Abort[Throwable]) =
    for {
      _ <- Log.info(
        s"loop.start history_size=${initialHistory.size} max_steps=$maxSteps"
      )
      startedNanos <- IO(java.lang.System.nanoTime())
      result <- Loop[
        List[Message],
        Int,
        (String, List[Message]),
        LLM & Tool & HistoryRewrite & AgentHalt & ResponseHook & IO &
          Abort[Throwable]
      ](initialHistory, maxSteps) { (history, remaining) =>
        if (remaining <= 0)
          Abort.fail(
            new RuntimeException(s"agent exceeded max steps ($maxSteps)")
          )
        else
          for {
            // L4 before_model hook：handler 可改写 history（截断 / 压缩 / 注入 system）。
            // runIdentity 下 rewritten === history；runKeepLast(n) 下裁成尾部 n 条。
            rewritten <- HistoryRewrite.rewrite(history)
            // L4 goto='end' hook：handler 返回 Some(reason) 则跳过 LLM 直接终止。
            // runNever 下永远 None，行为等价于不启用 halt；runOn(guard) 可按外部 state 决策。
            haltOpt   <- AgentHalt.check()
            outcome   <- haltOpt match {
              case Some(reason) =>
                // 早停：不调 LLM，用 rewritten 作为终态 history（compaction 一致性），
                // reason 作为答案——LangChain Command(goto='end', update={answer: reason}) 的对位
                IO(
                  Loop.done[List[Message], Int, (String, List[Message])](
                    (reason, rewritten)
                  )
                )
              case None =>
                for {
                  raw      <- LLM.complete(rewritten)
                  // L4 after_model hook：handler 可改写 LLM 返回值
                  // （空答案回落默认 / 过滤非法工具 / 强制工具调用）。
                  response <- ResponseHook.hook(raw)
                  // decideNext 用 rewritten 作为基底 append——若 handler 做了压缩，
                  // 后续 session history 也相应收敛（compaction 语义，而非纯 view）。
                  decided  <- decideNext(response, rewritten, remaining)
                } yield decided
            }
          } yield outcome
      }
      elapsedMs <- IO((java.lang.System.nanoTime() - startedNanos) / 1_000_000)
      _ <- Log.info(
        s"loop.end elapsed_ms=$elapsedMs history_size=${result._2.size} answer=${
            val a = result._1; if (a.length > 40) a.take(40) + "…" else a
          }"
      )
    } yield result

  /** 单发 overload：只关心答案不关心 history，用在一次性提问场景（现有 AgentSpec）。 */
  def loop(
      userInput: String,
      maxSteps: Int = DefaultMaxSteps
  ): String <
    (LLM & Tool & HistoryRewrite & AgentHalt & ResponseHook & IO &
      Abort[Throwable]) =
    loop(List(Message(Role.User, userInput)), maxSteps).map(_._1)

  /** 无限主循环（REPL）：读一行 → 带累计 history 跑一次 agent → 打印 → 继续读下一行。
    *
    * 终止条件：
    *   - 用户输入 `exit` / `quit` / `:q` → `Loop.done`
    *   - stdin 关闭（ctrl-D，EOF）→ `Abort.recover[IOException]` 折成 "exit"
    *
    * 用 `Loop[History, Unit, Unit, _]` 把会话级 history 作为循环状态： 每 turn 把新 user 消息
    * append 进去喂给 `loop`，拿回 `(ans, updatedHistory)`， 把 `updatedHistory`（含 tool
    * 轨迹 + Assistant 终答）作为下一轮的起始状态。
    *
    * **Per-turn Abort 隔离**：`registry` / `llm` 作为**参数**传入而不是由外层 `pipe(Tool.run)`
    * / `pipe(LLM.run)` 注入——这样它们的 handler 在**每 turn 内部**被应用，让
    * `Abort.run[Throwable]` 的作用域正确覆盖 Tool / LLM handler 里引入的 Abort。失败轮的 user
    * 输入回滚，session 继续。
    *
    * 为什么不能外层 pipe：Kyo 的效应作用域——外层 `pipe(Tool.run(reg))` 的 handler 在回调里
    * `reg.call(...)` raise 的 Abort 结构上**在 repl-内所有 Abort.run 作用域之外**，抓不到。 这是
    * Kyo 作用域的硬约束，只能 per-turn discharge 解决。
    *
    * 用 `Loop.apply` 而非 `def turn = ... turn` 的尾递归——避免外层 for-comprehension 的
    * continuation 随迭代次数累积，保证长跑 O(1) per turn。
    */
  def repl(
      registry: ToolRegistry,
      llm: LLMClient
  ): Unit < (IO & Abort[Throwable]) =
    for {
      _ <- Log.info("session.start")
      _ <- Loop[
        List[Message],
        Unit,
        Unit,
        IO & Abort[Throwable]
      ](List.empty[Message], ()) { (history, _) =>
        for {
          _ <- Console.print("You: ")
          line <- Console.readLine
            .handle(Abort.recover[IOException](_ => "exit"))
          outcome <- line.trim match {
            case "exit" | "quit" | ":q" =>
              Console
                .printLine("bye")
                .map(_ => Loop.done[List[Message], Unit, Unit](()))
            case "" =>
              IO(Loop.continue[List[Message], Unit, Unit](history, ()))
            case input =>
              val nextHistory = history :+ Message(Role.User, input)
              for {
                // Per-turn discharge：Tool.run / LLM.run 在 Abort.run 内层应用，让 Abort.run
                // 的作用域能 catch 它们 handler 回调里 raise 的 Abort。三分支 match 决定下一步状态。
                // repl 内部默认把所有 L4 effects 装配为 identity（HistoryRewrite.runIdentity /
                // AgentHalt.runNever / ResponseHook.runIdentity）。需要 custom 策略的调用方
                // 应该绕过 repl 自己写 runner，或等 repl overload 把 handler 暴露出来。
                resultR <- loop(nextHistory, DefaultMaxSteps)
                  .pipe(HistoryRewrite.runIdentity(_))
                  .pipe(AgentHalt.runNever(_))
                  .pipe(ResponseHook.runIdentity(_))
                  .pipe(Tool.run(registry))
                  .pipe(LLM.run(llm))
                  .pipe(Abort.run[Throwable](_))
                pair = resultR match {
                  case Result.Success((a, h)) => (a, h)
                  // 回滚 history：失败轮 user 输入不保留，防止污染后续 LLM 上下文
                  case Result.Failure(e) =>
                    (s"<error: ${e.getMessage}>", history)
                  case Result.Panic(e) =>
                    (s"<panic: ${e.getMessage}>", history)
                }
                (ans, updated) = pair
                _ <- Console.printLine(s"Assistant: $ans\n")
              } yield Loop.continue[List[Message], Unit, Unit](updated, ())
          }
        } yield outcome
      }
      _ <- Log.info("session.end")
    } yield ()

  // ====================== L4 suspend-point 预备（§6.3）======================
  //
  // 下面两个 private 函数是**命名重构**，不引入任何 effect，不改 loop 行为。
  // 目的是给未来要补的 ArrowEffect（HistoryRewrite / AgentHalt / ResponseHook 等）
  // 锚定插入位置：提升这些函数的**入口或返回前**为 ArrowEffect.suspend 点。
  //
  // 对应 LangChain AgentMiddleware 的 hook 布局（但不借用其 god-ADT）：
  //   - decideNext 入口：before_agent_decision（halt/goto='end' 类）
  //   - appendTurn 返回前：after_tool / history patch 类

  /** 决定当前 LLM 响应后 agent 的下一步：终结 or 继续。
    *
    * Answer 分支：把 Assistant 终答 append 入 history 后 `Loop.done`。 ToolCalls
    * 分支：串行执行每个 invocation（保留原序），结果拼回 history 后 `Loop.continue`。
    * `remaining - 1` 是按"一次 LLM 推理步"扣，和工具数量无关。
    *
    * 未来补 `AgentHalt` effect 时，suspend 点放在本方法入口——halt 命中则忽略 response 直接
    * `Loop.done`。
    */
  private def decideNext(
      response: LLMResponse,
      history: List[Message],
      remaining: Int
  ) = response match {
    case LLMResponse.Answer(text) =>
      // Answer 也入 history——调用方能拿到完整 session 流水
      val finalHistory = history :+ Message(Role.Assistant, text)
      IO(
        Loop.done[List[Message], Int, (String, List[Message])](
          (text, finalHistory)
        )
      )
    case LLMResponse.ToolCalls(invocations) =>
      Kyo
        .foreach(invocations) { inv =>
          Tool.call(inv.name, inv.args).map(out => (inv, out))
        }
        .map { results =>
          val newMsgs = results.toList.flatMap { case (inv, out) =>
            List(
              Message(Role.Assistant, s"CALL ${inv.name}(${inv.args})"),
              Message(Role.Tool, out)
            )
          }
          Loop.continue[List[Message], Int, (String, List[Message])](
            appendTurn(history, newMsgs),
            remaining - 1
          )
        }
  }

  /** Tool 执行产出的 messages 如何 append 回 history。
    *
    * 当前：纯拼接。未来若要允许 middleware 改写（tool 结果压缩 / 过滤敏感信息 / 合并同名调用），suspend 点放在本方法内。
    */
  private def appendTurn(
      history: List[Message],
      newMsgs: List[Message]
  ): List[Message] = history ++ newMsgs
}
