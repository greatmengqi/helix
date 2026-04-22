package agent

import kyo.*
import kyo.kernel.*

// ====================== HistoryRewrite：L4 - before_model hook ======================

/** L4 `ArrowEffect`：允许 middleware 在每次 `LLM.complete` 之前改写 history。
  *
  * 对应 LangChain `AgentMiddleware` 的 `before_model(state)` + `modify_model_request(req)`
  * hook——两者合并为一个 effect，因为 request 改写本质就是 history 改写。
  *
  * 消费者（示例）：
  *   - `runIdentity`    透传，默认
  *   - `runKeepLast(n)` 只保留最后 n 条（deterministic 截断）
  *   - `runSummarize`   达阈值后 LLM 压缩（待补，消耗 LLM effect）
  *   - `runInjectSystem` 每轮首部注入 system prompt（待补）
  *
  * `Const[List[Message]]` 表示输入输出类型固定（前后都是 `List[Message]`）——和 `Tool` /
  * `LLM` 的 Const pattern 一致。正交于它们：wire 时作为独立 pipe step 叠加。
  *
  * 设计决策（`docs/kyo-middleware-ioc-layers.md` §5.1 正交小 effect 方案）：
  * 每个决定点是独立 effect，不合并到 god-ADT。加新能力 = 加新 effect，不扩已有 enum。
  */
sealed trait HistoryRewrite
    extends ArrowEffect[Const[List[Message]], Const[List[Message]]]

object HistoryRewrite {

  /** raise 一次 history 改写请求：把当前 history 交给 handler， 等回一个（可能被改写的）history。
    *
    * handler 可以压缩、裁剪、注入 system 消息；透传（runIdentity）时行为不变。
    */
  inline def apply(h: List[Message])(using
      inline frame: Frame,
      inline tag: Tag[HistoryRewrite]
  ): List[Message] < HistoryRewrite =
    ArrowEffect.suspend[Any](tag, h)

  /** Identity handler：透传，等价于"不启用改写"。
    *
    * 必须显式 wire——Kyo 不提供 ambient 默认 handler，这保证 opt-out 是显式选择、签名透明（看
    * `loop` 签名就知道带 `HistoryRewrite` 效应）。
    */
  inline def runIdentity[A, S](v: A < (HistoryRewrite & S))(using
      inline frame: Frame,
      inline tag: Tag[HistoryRewrite]
  ): A < S =
    ArrowEffect.handle(tag, v)([C] => (h, cont) => cont(h))

  /** 截断：只保留最后 n 条消息。
    *
    * 适合长 session 防 LLM context 膨胀。deterministic 策略，无 LLM 调用。
    *
    * 极简形态——不区分角色、不特殊保留 system prompt。需要更复杂策略（始终保留首条、按
    * token 预算截、按 role 过滤）请写独立 handler，这里保持 minimal。
    *
    * 边界：
    *   - `n <= 0` → 返回 `Nil`（handler 行为透明；调用方若要保底自己控 n）
    *   - `n >= h.size` → 等价 identity
    */
  inline def runKeepLast[A, S](n: Int)(v: A < (HistoryRewrite & S))(using
      inline frame: Frame,
      inline tag: Tag[HistoryRewrite]
  ): A < S =
    ArrowEffect.handle(tag, v)([C] => (h, cont) => cont(h.takeRight(n)))
}
