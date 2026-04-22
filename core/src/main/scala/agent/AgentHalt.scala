package agent

import kyo.*
import kyo.kernel.*

// ====================== AgentHalt：L4 - goto='end' hook ======================

/** L4 `ArrowEffect`：允许 middleware 在每步开始时请求早停。
  *
  * 对应 LangChain `Command(goto='end')`——主流程在循环入口 check，handler 返回 `Some(reason)`
  * 则跳过本轮 LLM 调用，直接 `Loop.done((reason, history))` 终止；返回 `None` 则正常流转。
  *
  * 消费者（示例）：
  *   - `runNever`      永不早停，identity（默认）
  *   - `runOn(guard)`  条件早停——按用户提供的 guard 决定，typical guard 捕获外部 state
  *     （AtomicBoolean kill-switch / 计数器超阈值 / 预算读数等）
  *
  * **设计决策**（`docs/kyo-middleware-ioc-layers.md` §5.1）：和 `HistoryRewrite` 正交——都是
  * 独立 effect，不合并到 god-ADT。加新能力（例如 `ResponseHook`）= 加新 sealed trait，
  * 不扩已有的 `Option[String]` signal shape。
  *
  * **相对 `maxSteps`**：`maxSteps` 是硬上限兜底（超限 `Abort.fail`），`AgentHalt` 是软性预算
  * 控制（halt 是正常终止、返回 reason 作为 answer）。两者正交共存。
  */
sealed trait AgentHalt extends ArrowEffect[Const[Unit], Const[Option[String]]]

object AgentHalt {

  /** **invoke**：raise 一次 halt check——每轮主流程入口调一次，handler 决定
    * `Some(reason)` 早停或 `None` 继续。
    *
    * **三层角色**（source-level）：
    *   - **def**：`sealed trait AgentHalt extends ArrowEffect[...]`——效应契约（type）
    *   - **invoke**：`def invoke()`（本方法）——调用方，`ArrowEffect.suspend` 的封装
    *   - **impl**：`def implNever` / `def implOn(guard)`——handler 实现（策略）
    */
  inline def invoke()(using
      inline frame: Frame,
      inline tag: Tag[AgentHalt]
  ): Option[String] < AgentHalt =
    ArrowEffect.suspend[Any](tag, ())

  /** **impl**（never）：永不早停，等价于"不启用 halt 能力"。默认 wire。 */
  inline def implNever[A, S](v: A < (AgentHalt & S))(using
      inline frame: Frame,
      inline tag: Tag[AgentHalt]
  ): A < S =
    ArrowEffect.handle(tag, v)([C] => (_, cont) => cont(None))

  /** **impl**（on）：条件早停——每次 invoke 调用时重新求值 `guard`，`Some(reason)` 立即终止、`None` 继续。
    *
    * `guard: => Option[String]` 是 by-name——handler 每次拦截都重新求值，所以 guard
    * 里读取的外部 state（`AtomicInteger.get()` / `AtomicBoolean.get()` / 时钟等）总是最新的。
    *
    * 不做"halted 后仍被调用"的二次保护——loop 在 `haltOpt = Some(...)` 分支就 `Loop.done`，
    * 不会继续 invoke。
    */
  def implOn[A, S](guard: => Option[String])(v: A < (AgentHalt & S))(using
      frame: Frame,
      tag: Tag[AgentHalt]
  ): A < S =
    ArrowEffect.handle(tag, v)([C] => (_, cont) => cont(guard))
}
