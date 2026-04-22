package agent

import kyo.*
import kyo.kernel.*

// ====================== Tool：真 Kyo 自定义 effect ======================

/** Tool 调用的输入描述。ArrowEffect 的 Input 是个 `[_] =>> X`（Const），
  * 因为我们不需要跨类型参数传递信息——调用只关心 name+args，返回只关心 String。
  */
final case class ToolInvocation(name: String, args: Map[String, String])

/** Tool 作为 ArrowEffect：
  *   - Input = Const[ToolInvocation]
  *   - Output = Const[String]
  *
  * `sealed trait` 是 Kyo 的约定：effect 是抽象标识符，没人会实例化它， 所有语义由 handler 提供。
  */
sealed trait Tool extends ArrowEffect[Const[ToolInvocation], Const[String]]

/** 工具的后端实现。一个 ToolRegistry 是 "name -> impl" 的注册表抽象， 最终由 Tool.run(registry) 把
  * Tool effect 翻译为对 registry 的调用。
  *
  * 签名带 `IO`：middleware 走 `kyo.Log` 写日志属 I/O，必须把 IO 通道贯穿到 backend trait。
  */
trait ToolRegistry {
  def call(
      name: String,
      args: Map[String, String]
  ): String < (IO & Abort[Throwable])
}

object Tool {

  /** raise 一次 Tool 调用：把 `ToolInvocation` 作为 suspended 计算抛出， 等待上层 handler 给回
    * `String`。
    */
  inline def call(name: String, args: Map[String, String])(using
      inline frame: Frame,
      inline tag: Tag[Tool]
  ): String < Tool =
    ArrowEffect.suspend[Any](tag, ToolInvocation(name, args))

  /** Terminal handler：把 Tool effect discharge 为对 registry 的真实调用。 结果类型从
    * `A < (Tool & S)` 变成 `A < (S & IO & Abort[Throwable])`—— Tool
    * 消失，registry.call 引入 IO + Abort。
    */
  inline def run[A, S](registry: ToolRegistry)(v: A < (Tool & S))(using
      inline frame: Frame,
      inline tag: Tag[Tool]
  ): A < (S & IO & Abort[Throwable]) =
    ArrowEffect.handle(tag, v)([C] =>
      (input, cont) => registry.call(input.name, input.args).map(cont)
    )
}

// ====================== Tool Middleware ======================

/** Tool middleware：装饰 `ToolRegistry`，把 cross-cutting 关注点（错误处理、日志、 计时、重试、限流）叠加到
  * tool 调用上。
  *
  * 设计：middleware 不改 `Tool` effect 的语义，只调整 handler 拿到的 `ToolRegistry`
  * 实现。业务代码（`Agent.loop` / `LLMResponse.ToolCalls`）完全不动， 新能力通过 wire 时叠加：
  *
  * {{{
  *   Agent.repl.pipe(Tool.run(
  *     MockTools.pipe(ToolMiddleware.errorHandling compose ToolMiddleware.logging)
  *   ))
  * }}}
  *
  * 叠加顺序：`(a compose b compose c)(reg)` 等价于 `a(b(c(reg)))`——compose 左侧最外层，
  * 最先看到请求、最后加工响应。像 HTTP middleware stack。不抽专门的 `chain` 函数——stdlib
  * `Function1.compose` + `scala.util.chaining.pipe` 已经覆盖所有语义，多一层命名聚合是 API
  * sugar 而非代数抽象（详见决策 #13）。
  *
  * 日志走 `kyo.Log`：middleware 不再持有 `Logger` 实例，从环境取（`Log.let` /
  * `Log.withConsoleLogger` 在外层注入）。 测试要静默就在 wire 时用 `Log.let(silentLog)`。
  */
type ToolMiddleware = ToolRegistry => ToolRegistry

object ToolMiddleware {

  /** 错误处理 middleware：把 registry 的 `Abort[Throwable]` 转成结构化错误字符串， 作为正常 tool
    * 结果回传。
    *
    * LLM 在 history 里会看到 `<tool_error name=... msg=...>`，自己决定重试 / 放弃 / 继续推理—— 对齐
    * OpenAI / Anthropic 的约定（工具错误作为 tool_result 发送，带 is_error 标记）。
    *
    * 符合"catch 必须 log"原则：recover 时通过 `Log.error` 留痕原始异常，不静默吞掉。
    */
  val errorHandling: ToolMiddleware = next =>
    new ToolRegistry {
      def call(
          name: String,
          args: Map[String, String]
      ): String < (IO & Abort[Throwable]) =
        next
          .call(name, args)
          .handle(Abort.recover[Throwable] { e =>
            Log
              .error(
                s"tool.call.recover name=$name args=$args",
                e
              )
              .map(_ => s"<tool_error name=$name msg=${e.getMessage}>")
          })
    }

  /** 纯观测 middleware：记录每次 tool 调用的起止、耗时、成败，不改语义。
    *
    * 与 `errorHandling` 搭配的推荐排布：
    * {{{
    *   (errorHandling compose logging)(registry)
    *   //     ^ 外层                ^ 内层
    * }}}
    * 原因：
    *   - logging 在内 → 先看到真实 `Abort` 原始异常，记 `tool.call.fail` 再重抛
    *   - errorHandling 在外 → 把异常折叠成 `<tool_error>` 字符串，同时记 `tool.call.recover`
    *
    * 反过来 `logging compose errorHandling` 的话，logging 会把 errorHandling 产出的
    * `<tool_error>` 字符串当"成功结果" log 出 `tool.call.ok`——telemetry 在说谎。
    *
    * 实现：用 `Abort.run[Throwable]` 把计算降成 `Result[Throwable, String]`，按 Result 三分支
    * 观测完再重新 inject 回 Abort 通道。这样 logging 是透明层，不 discharge Abort，上层还能继续 recover。
    */
  val logging: ToolMiddleware = next =>
    new ToolRegistry {
      def call(
          name: String,
          args: Map[String, String]
      ): String < (IO & Abort[Throwable]) =
        for {
          _ <- Log.info(s"tool.call.start name=$name args=$args")
          startedNanos <- IO(java.lang.System.nanoTime())
          result <- Abort.run[Throwable](next.call(name, args))
          elapsedMs <- IO(
            (java.lang.System.nanoTime() - startedNanos) / 1_000_000
          )
          out <- result match {
            case Result.Success(out) =>
              Log
                .info(
                  s"tool.call.ok name=$name elapsed_ms=$elapsedMs result_len=${out.length}"
                )
                .map(_ => out: String < (IO & Abort[Throwable]))
            case Result.Failure(e) =>
              Log
                .error(
                  s"tool.call.fail name=$name elapsed_ms=$elapsedMs",
                  e
                )
                .map(_ => Abort.fail[Throwable](e))
            case Result.Panic(e) =>
              Log
                .error(
                  s"tool.call.panic name=$name elapsed_ms=$elapsedMs",
                  e
                )
                .map(_ => Abort.fail[Throwable](e))
          }
        } yield out
    }

  /** 重试 middleware：遇到 Abort.Failure 立即重试，上限 `maxAttempts` 次（含首次）。
    *
    * 不做 backoff——当前 effect 栈没有 `Async` / `kyo.Clock`，加 sleep 要级联引入 Async。
    * 对大多数瞬时错误（网络抖动、rate limit）立即重试已经够用；真要 exponential backoff 等引入 Async 再升级。
    *
    * **Panic 不重试**——panic 是 Kyo 运行时层面的不可恢复状态，重试没意义。
    *
    * 组合位置：**retry 必须在 errorHandling 内层**，否则 errorHandling 会把 Abort 折成
    * `<tool_error>` 字符串，retry 永远看不到 Failure：
    * {{{
    *   (errorHandling compose retry compose logging)(registry)  // ✓ 正确
    *   (retry compose errorHandling compose logging)(registry)  // ✗ retry 失效
    * }}}
    */
  val retry: ToolMiddleware = retryWith(maxAttempts = 3)

  def retryWith(maxAttempts: Int): ToolMiddleware = next =>
    new ToolRegistry {
      def call(
          name: String,
          args: Map[String, String]
      ): String < (IO & Abort[Throwable]) = {
        def attempt(remaining: Int): String < (IO & Abort[Throwable]) =
          Abort.run[Throwable](next.call(name, args)).map {
            case Result.Success(s) => s: String < (IO & Abort[Throwable])
            case Result.Failure(e) if remaining > 1 =>
              Log
                .warn(
                  s"tool.retry name=$name remaining=${remaining - 1}",
                  e
                )
                .map(_ => attempt(remaining - 1))
            case Result.Failure(e) => Abort.fail[Throwable](e)
            case Result.Panic(e)   => Abort.fail[Throwable](e) // panic 不重试
          }
        attempt(maxAttempts)
      }
    }

  /** 调用次数上限 middleware：全局累计 `max` 次后拒绝调用，raise Abort。
    *
    * **对应 LangChain `ToolCallLimitMiddleware`**——独立于单次 `loop(maxSteps)` 的每轮预算，
    * 这个是跨所有 session / turn 的总量硬上限。计数用每个 wrapper 实例里的 AtomicInteger， 所以每次
    * `callLimit(n)(registry)` 返回的实例有自己的计数。
    *
    * 注意：超限是 Abort.fail，如果 `errorHandling` 在外层，限额错误会被折成 `<tool_error>` 字符串喂给
    * LLM——这是刻意的，LLM 能自己决定"算了不调了"。
    */
  def callLimit(max: Int): ToolMiddleware = next =>
    new ToolRegistry {
      private val counter = new java.util.concurrent.atomic.AtomicInteger(0)
      def call(
          name: String,
          args: Map[String, String]
      ): String < (IO & Abort[Throwable]) = {
        val n = counter.incrementAndGet()
        if (n > max)
          Abort.fail(
            new RuntimeException(s"tool call limit exceeded: $n > $max")
          )
        else next.call(name, args)
      }
    }

}
