package agent

import kyo.*
import kyo.kernel.*

// ====================== LLM Response ADT ======================

/** LLM 的响应 sum type：要么给最终答案，要么请求一批工具调用。
  *
  * `ToolCalls` 承载 `List[ToolInvocation]` 而非单个—— 贴合 OpenAI / Anthropic / Gemini
  * 的"一次 response 多个 tool call"约定。 当前实现串行执行；并行需引入 Async effect。
  */
enum LLMResponse derives CanEqual {
  case Answer(text: String)
  case ToolCalls(invocations: List[ToolInvocation])
}

// ====================== LLM：真 Kyo 自定义 effect ======================

/** LLM 后端实现。和 Tool 侧的 `ToolRegistry` 完全对称——`LLM.run(client)` 把 LLM effect
  * 翻译为对这个 client 的真实调用。middleware (`LLMMiddleware`) 在 wire 时 叠加装饰，对业务代码透明。
  *
  * 签名带 `IO`：middleware 走 `kyo.Log` 写日志属 I/O，必须把 IO 通道贯穿到 backend trait。
  */
trait LLMClient {
  def complete(history: List[Message]): LLMResponse < (IO & Abort[Throwable])
}

/** LLM 作为 ArrowEffect，与 Tool 结构完全对称：
  *   - Input = Const[List[Message]]——一次调用喂当前 history
  *   - Output = Const[LLMResponse]——返回答案 or 工具请求
  *
  * 从 `type LLM = Env[LLMClient]` 升级上来的原因：让 LLM 侧的 cross-cutting 关注点（logging /
  * retry / caching / fallback）走统一的 `LLMMiddleware` 装饰器抽象， 而不是在应用层一个个手写 one-off
  * wrapper——和 Tool 侧彻底对齐。
  */
sealed trait LLM extends ArrowEffect[Const[List[Message]], Const[LLMResponse]]

object LLM {

  /** **invoke**：raise 一次 LLM 调用——把 history 作为 suspended 计算抛出，等待上层 handler 给回
    * response。
    *
    * **三层角色**（source-level）：
    *   - **def**：`sealed trait LLM extends ArrowEffect[...]`——效应契约（type）
    *   - **invoke**：`def invoke(history)`（本方法）——调用方，`ArrowEffect.suspend` 的封装
    *   - **impl**：`def impl(client)`——handler 实现（terminal handler，dispatch 到
    *     Backend）
    */
  inline def invoke(history: List[Message])(using
      inline frame: Frame,
      inline tag: Tag[LLM]
  ): LLMResponse < LLM =
    ArrowEffect.suspend[Any](tag, history)

  /** **impl**：terminal handler，把 LLM effect discharge 为对 `LLMClient` backend
    * 的真实调用。 结果类型从 `A < (LLM & S)` 变成 `A < (S & IO & Abort[Throwable])`——LLM
    * 消失，client.complete 引入 IO + Abort。
    */
  inline def impl[A, S](client: LLMClient)(v: A < (LLM & S))(using
      inline frame: Frame,
      inline tag: Tag[LLM]
  ): A < (S & IO & Abort[Throwable]) =
    ArrowEffect.handle(tag, v)([C] =>
      (input, cont) => client.complete(input).map(cont)
    )
}

// ====================== LLM Middleware ======================

/** LLM middleware：装饰 `LLMClient`，把 cross-cutting 关注点（logging / retry / caching
  * / fallback）叠加到 `complete` 调用上。
  *
  * 和 `ToolMiddleware` 结构完全对称——升级 LLM 到 `ArrowEffect` 的主要收益。 业务代码（`Agent.loop`
  * 里的 `LLM.complete(history)`）不动，能力通过 wire 时
  * `LLM.run((mw1 compose mw2)(realClient))` 叠加。
  *
  * 排布规则和 ToolMiddleware 一致：`(a compose b compose c)(client)` 等价于
  * `a(b(c(client)))`，compose 左侧最外层。同样不抽 `chain` 函数（详见决策 #13）。
  *
  * 日志走 `kyo.Log`：和 ToolMiddleware 同源，外层 `Log.let` / `Log.withConsoleLogger` 注入。
  */
type LLMMiddleware = LLMClient => LLMClient

object LLMMiddleware {

  /** 纯观测 middleware：记录每次 `complete` 的输入 / 输出 / 耗时。 不改语义，失败照样 propagate。
    *
    * 每个 LLMClient wrapper 实例持有一个 AtomicInteger 作为调用编号，输出里 `llm.call#N` 帮助读者对齐
    * ReAct 轨迹里交错的 tool 调用。编号在 wrapper 生命周期内单调递增——因为 session 级计数比 per-turn
    * 更能反映"上下文从哪轮继承来"。
    *
    * 失败观测通过 `Abort.run[Throwable]` 降到 `Result` 观测三分支再重新 inject， 和
    * `ToolMiddleware.logging` 是同一个套路。
    */
  val logging: LLMMiddleware = underlying =>
    new LLMClient {
      private val step = new java.util.concurrent.atomic.AtomicInteger(0)
      def complete(
          history: List[Message]
      ): LLMResponse < (IO & Abort[Throwable]) =
        for {
          n <- IO(step.incrementAndGet())
          tail <- IO(history.lastOption.map(_.toString).getOrElse("∅"))
          _ <- Log.info(s"llm.call#$n history_size=${history.size} tail=$tail")
          startedNanos <- IO(java.lang.System.nanoTime())
          result <- Abort.run[Throwable](underlying.complete(history))
          elapsedMs <- IO(
            (java.lang.System.nanoTime() - startedNanos) / 1_000_000
          )
          resp <- result match {
            case Result.Success(resp) =>
              val label = resp match {
                case LLMResponse.Answer(t) =>
                  val snippet = if (t.length > 40) t.take(40) + "…" else t
                  s"Answer($snippet)"
                case LLMResponse.ToolCalls(invs) =>
                  s"ToolCalls(${invs.map(i => s"${i.name}${i.args}").mkString(", ")})"
              }
              Log
                .info(
                  s"llm.call#$n elapsed_ms=$elapsedMs → $label"
                )
                .map(_ => resp: LLMResponse < (IO & Abort[Throwable]))
            case Result.Failure(e) =>
              Log
                .error(
                  s"llm.call#$n.fail elapsed_ms=$elapsedMs",
                  e
                )
                .map(_ => Abort.fail[Throwable](e))
            case Result.Panic(e) =>
              Log
                .error(
                  s"llm.call#$n.panic elapsed_ms=$elapsedMs",
                  e
                )
                .map(_ => Abort.fail[Throwable](e))
          }
        } yield resp
    }

  /** 重试 middleware：遇到 Abort.Failure 立即重试，上限 `maxAttempts` 次。
    *
    * 语义和 `ToolMiddleware.retry` 对称。不做 backoff（没 Async）；panic 不重试。
    *
    * Chain 位置同样要注意：retry 要在"吞异常的 middleware"之内。本项目 LLM 侧没有 errorHandling，
    * 但未来如果加 `llmErrorHandling`，同理要把 retry 放在它内层。
    */
  val retry: LLMMiddleware = retryWith(maxAttempts = 3)

  def retryWith(maxAttempts: Int): LLMMiddleware = underlying =>
    new LLMClient {
      def complete(
          history: List[Message]
      ): LLMResponse < (IO & Abort[Throwable]) = {
        def attempt(remaining: Int): LLMResponse < (IO & Abort[Throwable]) =
          Abort.run[Throwable](underlying.complete(history)).map {
            case Result.Success(r) =>
              r: LLMResponse < (IO & Abort[Throwable])
            case Result.Failure(e) if remaining > 1 =>
              Log
                .warn(
                  s"llm.retry remaining=${remaining - 1}",
                  e
                )
                .map(_ => attempt(remaining - 1))
            case Result.Failure(e) => Abort.fail[Throwable](e)
            case Result.Panic(e)   => Abort.fail[Throwable](e)
          }
        attempt(maxAttempts)
      }
    }

  /** 调用次数上限 middleware：全局累计 `max` 次 LLM 调用后 raise Abort。
    *
    * **对应 LangChain `ModelCallLimitMiddleware`**——防止 agent 失控循环调用 LLM 把预算花干。 和
    * Tool 侧的 `callLimit` 独立计数（两者用处不同：LLM 次数影响成本，tool 次数影响外部 API 配额）。
    */
  def callLimit(max: Int): LLMMiddleware = underlying =>
    new LLMClient {
      private val counter = new java.util.concurrent.atomic.AtomicInteger(0)
      def complete(
          history: List[Message]
      ): LLMResponse < (IO & Abort[Throwable]) = {
        val n = counter.incrementAndGet()
        if (n > max)
          Abort.fail(
            new RuntimeException(s"llm call limit exceeded: $n > $max")
          )
        else underlying.complete(history)
      }
    }

  /** Fallback middleware：主 client 失败时切到备份 client。
    *
    * **对应 LangChain `ModelFallbackMiddleware`**——常见场景：主走 GPT-4 省钱失败降级 GPT-3.5；
    * 或跨家备份（OpenAI 挂了用 Anthropic）。Panic 不 fallback（运行时错误切了也是错）。
    *
    * 和 `retry` 的区别：retry 是"重试同一个"，fallback 是"换一个"。串起来用
    * `(fallback(backup) compose retry)(primary)`： primary 先 retry N 次，全失败才切
    * backup。
    */
  def fallback(backup: LLMClient): LLMMiddleware = primary =>
    new LLMClient {
      def complete(
          history: List[Message]
      ): LLMResponse < (IO & Abort[Throwable]) =
        Abort.run[Throwable](primary.complete(history)).map {
          case Result.Success(r) =>
            r: LLMResponse < (IO & Abort[Throwable])
          case Result.Failure(e) =>
            Log
              .warn(
                s"llm.fallback reason=${e.getMessage}",
                e
              )
              .map(_ => backup.complete(history))
          case Result.Panic(e) => Abort.fail[Throwable](e)
        }
    }

  /** Caching middleware：相同 history 返回缓存的 response，不再调 backend。
    *
    * **对应 LangChain `AnthropicPromptCachingMiddleware` 的精神（简化版）**——真实 LangChain
    * 是在 prompt 里插 cache_control marker 让 Anthropic 服务端缓存前缀； 我们这里是**客户端内存缓存完整
    * history → response 映射**。 两者目标相同（避免重复计算），层次不同。
    *
    * 默认工厂 `caching` 用一个进程级 `ConcurrentHashMap`；`cachingWith(cache)` 让调用方自带
    * cache（测试能清空、多 agent 共享、换成 Redis 等）。
    *
    * 注意：失败不缓存（`Result.Failure` / `Panic` 直接 propagate 不 put）。
    *
    * **组合位置**：caching 在最外层——命中时跳过后续所有 middleware（retry / errorHandling 全省）。
    * 典型顺序：`(caching compose errorHandling compose retry compose
    * logging)(client)`。
    */
  val caching: LLMMiddleware =
    cachingWith(
      new java.util.concurrent.ConcurrentHashMap[
        List[Message],
        LLMResponse
      ]()
    )

  def cachingWith(
      cache: java.util.concurrent.ConcurrentMap[List[Message], LLMResponse]
  ): LLMMiddleware = underlying =>
    new LLMClient {
      def complete(
          history: List[Message]
      ): LLMResponse < (IO & Abort[Throwable]) = {
        val hit = cache.get(history)
        if (hit != null)
          Log
            .info(s"llm.cache.hit history_size=${history.size}")
            .map(_ => hit: LLMResponse < (IO & Abort[Throwable]))
        else
          underlying.complete(history).map { resp =>
            cache.put(history, resp)
            resp
          }
      }
    }

}
