package agent

import kyo.*
import kyo.kernel.*

// ====================== ResponseHook：L4 - after_model hook ======================

/** L4 `ArrowEffect`：允许 middleware 在 `LLM.complete` 返回之后、`decideNext` 之前 拦截 / 改写
  * `LLMResponse`。
  *
  * 对应 LangChain `AgentMiddleware.after_model(state)`——LLM 说完话、agent 决策之前的窗口。
  *
  * 消费者（示例）：
  *   - `runIdentity`  透传，默认
  *   - `runMap(f)`    generic 变换——可以：
  *       - `Answer("") → Answer("<empty>")` 避免空回答污染
  *       - `ToolCalls(invs) → ToolCalls(invs.filter(allowList.contains))` 过滤非法工具
  *       - `Answer(text) → ToolCalls(...)` 强制工具调用（改变控制流！）
  *
  * **不支持**：回放/重试 LLM 调用——handler 拿不到 history 上下文，没法重新 `LLM.complete`。 真要这种能力属于
  * L5 级，需要状态机结构化抽象。
  *
  * **和 `HistoryRewrite` / `AgentHalt` 的正交性**：三者独立 wire，顺序互不影响语义
  * （顺序仅改变 suspend 触发时机）。
  */
sealed trait ResponseHook
    extends ArrowEffect[Const[LLMResponse], Const[LLMResponse]]

object ResponseHook {

  /** **invoke**：raise 一次 response 改写请求——LLM 返回的 raw response 交给 handler，
    * 等回一个（可能被改写的）response。
    *
    * **三层角色**（source-level）：
    *   - **def**：`sealed trait ResponseHook extends ArrowEffect[...]`——效应契约（type）
    *   - **invoke**：`def hook(r)`（本方法）——调用方，`ArrowEffect.suspend` 的 domain 动词封装
    *   - **impl**：`def runIdentity` / `def runMap(f)`——handler 实现（策略）
    */
  inline def hook(r: LLMResponse)(using
      inline frame: Frame,
      inline tag: Tag[ResponseHook]
  ): LLMResponse < ResponseHook =
    ArrowEffect.suspend[Any](tag, r)

  /** Identity handler：透传，等价于"不启用 response 改写"。默认 wire。 */
  inline def runIdentity[A, S](v: A < (ResponseHook & S))(using
      inline frame: Frame,
      inline tag: Tag[ResponseHook]
  ): A < S =
    ArrowEffect.handle(tag, v)([C] => (r, cont) => cont(r))

  /** Generic 变换：用户提供的 `LLMResponse => LLMResponse` 函数每次被应用到 LLM 返回值上。
    *
    * 特殊变换场景自己写 `f`。Framework 不提供 `runRewriteEmpty` / `runFilterTools`
    * 等预制 handler——消费者自己最清楚要改什么，framework 只提供接入点。
    */
  inline def runMap[A, S](f: LLMResponse => LLMResponse)(
      v: A < (ResponseHook & S)
  )(using
      inline frame: Frame,
      inline tag: Tag[ResponseHook]
  ): A < S =
    ArrowEffect.handle(tag, v)([C] => (r, cont) => cont(f(r)))
}
