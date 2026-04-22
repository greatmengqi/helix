# 中间件 IoC 分层与 L4 State-Transition Middleware 设计备忘

## 这是什么

一份**设计备忘**，不是实现计划，更不是教程。用来：

1. 记录"中间件控制反转"的完整分层框架，避免未来重复推导
2. 明确标出我们项目**当前占据哪几格、缺哪几格**
3. 预先思考 L4（状态转移级中间件）如果要补应该怎么做、何时做
4. 作为未来"该不该补 L4"决策的参考

**当前结论**：L4 不做。预留的**是思考**，不是代码。

---

## 一、中间件 IoC 的 6 层框架

中间件的"控制反转"不是一个单一概念，是一个 **(被反转对象 × 反转粒度)** 的二维谱系：

```
L1  协议级         HTTP middleware / envoy / nginx / service mesh
                   反转对象：byte stream / 请求路由
                   典型形态：proxy / sidecar

L2  调用级         A => A 函数装饰器
                   反转对象：单次函数调用
                   反转粒度：是否调下层 / 几次 / 怎么改参
                   典型形态：Koa next() / Finagle Filter / 我们的 ToolMiddleware & LLMMiddleware

L3  效应级         CPS handler / free monad interpreter
                   反转对象：effect continuation
                   反转粒度：下层整条后续计算怎么执行
                   典型形态：Kyo ArrowEffect.handle / cats-mtl / scalaz Free foldMap

L4  转移级         State graph edge hook
                   反转对象：agent 状态机的转移
                   反转粒度：下一步走向哪个节点 / 改 state / 悬挂 / 终止
                   典型形态：LangChain AgentMiddleware / LangGraph node guard

L5  图结构级       Graph restructure
                   反转对象：agent 状态机的拓扑本身
                   反转粒度：动态增删节点 / 边
                   典型形态：LangGraph dynamic graph / OpenAI Swarm hand-off

L6  会话级         Session / memory / cross-run policy
                   反转对象：跨 agent run 的行为一致性
                   反转粒度：多租户策略 / 长期记忆 / 审计
                   典型形态：MemGPT / LangSmith middleware
```

**注意**：这**不是**嵌套栈，是**独立反转轴**。L4 不是"比 L2/L3 更高级"，而是**反转了不同的决定权对象**。不同层级各自填不同的能力空白，不互相替代。

---

## 二、本项目现状映射

当前 `core/agent` 覆盖的层级：

| 层 | 是否有 | 实现 |
|---|---|---|
| L1 | N/A | 本项目不涉及协议层 |
| **L2** | ✅ | `ToolMiddleware` / `LLMMiddleware`（`A => A`，用 `compose` 组合，`pipe` 应用） |
| **L3** | ✅ | `Tool.impl` / `LLM.impl`（`ArrowEffect.handle` 的 CPS 形态） |
| L4 | ❌ | **缺口**。`Agent.loop` 里的状态转移（`Loop.done` / `Loop.continue` / `history ++ newMsgs`）写死在 body 里，外部不可注入 |
| L5 | ❌ | Agent 是单一状态机，无图结构抽象 |
| L6 | ❌ | 无 session policy / 长期记忆 |

L2 和 L3 合起来覆盖了"单次 backend call 的全部反转需求"。L4 的缺口意味着：**无法外部干预 agent 在多步推理间的流转决策**。

---

## 三、L4 缺口的具体表现

L2/L3 中间件能看到：一次 `LLM.invoke` / `Tool.invoke` 的 in/out。

L2/L3 中间件**看不到、干预不了**：

1. **Forced think**：`LLM` 返回 `Answer` 时强制忽略、再 think 一轮
2. **Skip model**：工具调用完不回 LLM，直接对用户产出工具结果
3. **History compaction**：多轮累积后自动压缩 history 再继续（语义层压缩，不是微优化）
4. **Human in the loop**：中途悬挂等外部审批，batch 收集人话后 resume
5. **Confidence-based early stop**：LLM 置信度够高时提前终止剩余 maxSteps
6. **Multi-agent hand-off**：把控制权交给另一个 agent sub-loop

共同特征：**跨单次 call 的状态级决策**。这些不是"包装调用"能做到的——因为决策对象是 agent 的 state transition，不是 backend call。

---

## 四、LangChain 做法参考

LangChain 1.x `AgentMiddleware` 暴露的 hook：

```python
class XxxMiddleware(AgentMiddleware):
    def before_agent(self, state):        # session 起点
    def before_model(self, state):        # 每次 LLM 之前
    def modify_model_request(self, req):  # 改请求
    def after_model(self, state):         # 每次 LLM 之后
    def on_tool_call(self, call):         # tool 拦截
    def after_tool(self, result):         # tool 结果后
    def after_agent(self, state):         # session 终点
```

**关键**：这些 hook 的返回值可以是 `Command`：

```python
Command(
    goto="end" | "model" | "<node>",  # 跳转目标
    update={...}                       # state patch
)
```

`goto` 能让中间件把 agent 跳到状态机的任意节点——**这是 L4 的核心能力，L2/L3 做不到**。

### 代数骨架对照

| 项 | L2 `A => A` | L4 AgentMiddleware |
|---|---|---|
| 对象 | 单个 backend call | agent 整次运行 |
| 作用域 | 一次 `complete` / `call` | 一个 session 的完整状态图 |
| 反转对象 | 调用实现 | 状态转移 |
| 范畴身份 | Function 范畴的 endomorphism | Graph 范畴的 endofunctor |
| 跨层协议 | 只能走数据管道 | 共享 state 对象（显式） |

---

## 五、Kyo 风格的 L4 设计草图（未实现）

如果补 L4，**不要照抄 LangChain 的 Python 类继承**——利用 Kyo 的 `ArrowEffect` 能用一半代码量 + 全类型安全做出同等能力。

Kyo 场景下**最优雅的形态是正交小 effect**（Algebra of Effects），而**不是** LangChain 风格的单个 god-effect + `StepDecision` ADT。两种方案的取舍见 §5.4。

### 5.1 主推：正交小 effect

每个可被 middleware 干预的决定点是一个独立 `ArrowEffect`。每个 effect 由三部分组成：

1. 最小 input / output（不用 ADT 覆盖所有 case）
2. 一个 **identity / 默认** handler（middleware 不 opt-in 时的行为）
3. 按需的 middleware handler（不同策略各写一个）

```scala
// 能力 1：history rewrite hook
sealed trait HistoryRewrite extends ArrowEffect[Const[List[Message]], Const[List[Message]]]

object HistoryRewrite:
  def apply(h: List[Message])(using Frame, Tag[HistoryRewrite]): List[Message] < HistoryRewrite =
    ArrowEffect.suspend[Any](Tag[HistoryRewrite], h)

  // 默认：透传
  def implIdentity[A, S](v: A < (HistoryRewrite & S))(using Frame): A < S =
    ArrowEffect.handle(Tag[HistoryRewrite], v)([C] => (h, cont) => cont(h))

  // middleware：超阈值自动压缩（消费 LLM effect 生成 summary）
  def implSummarize[A, S](threshold: Int)(v: A < (HistoryRewrite & S))(using Frame): A < (S & LLM) =
    ArrowEffect.handle(Tag[HistoryRewrite], v)([C] => (h, cont) =>
      if h.size > threshold then summarizeLLM(h).flatMap(cont)
      else cont(h))

// 能力 2：halt request hook
sealed trait AgentHalt extends ArrowEffect[Const[Unit], Const[Option[String]]]

object AgentHalt:
  def check()(using Frame, Tag[AgentHalt]): Option[String] < AgentHalt =
    ArrowEffect.suspend[Any](Tag[AgentHalt], ())

  def implNever[A, S](v: A < (AgentHalt & S))(using Frame): A < S =
    ArrowEffect.handle(Tag[AgentHalt], v)([C] => (_, cont) => cont(None))

  def implOn[A, S](guard: => Option[String])(v: A < (AgentHalt & S))(using Frame): A < S =
    ArrowEffect.handle(Tag[AgentHalt], v)([C] => (_, cont) => cont(guard))

// 能力 3：suspend / resume hook（更重，和 Async 整合——待实装时设计）
sealed trait AgentSuspend extends ArrowEffect[Const[String], Const[ResumeSignal]]
```

### 5.2 `Agent.loop` 在关键点 suspend

```scala
def loop(initialHistory: List[Message], maxSteps: Int)
    : (String, List[Message]) <
      (LLM & Tool & HistoryRewrite & AgentHalt & IO & Abort[Throwable]) =
  Loop[List[Message], Int, (String, List[Message]), ...](
    initialHistory, maxSteps
  ) { (history, remaining) =>
    for {
      rewritten <- HistoryRewrite.invoke(history)     // 允许外部压缩 / 改写
      halt      <- AgentHalt.invoke()                   // 允许外部请求早停
      outcome   <- halt match
        case Some(reason) =>
          Loop.done((reason, rewritten))
        case None =>
          for
            response <- LLM.invoke(rewritten)
            decided  <- response match
              case Answer(t)       => Loop.done((t, rewritten :+ Msg(Assistant, t)))
              case ToolCalls(invs) => ...
          yield decided
    } yield outcome
  }
```

每个 suspend point 是**单用途**的——不做"统一决策 gateway"。想加"force tool call"能力？再加一个 `AgentForceAction` effect，不扩已有 ADT。

### 5.3 Wire 时装配能力

```scala
// 最小配置：全 identity，等价于当前无 L4 的行为
Agent.loop(...)
  .pipe(HistoryRewrite.implIdentity(_))
  .pipe(AgentHalt.implNever(_))
  .pipe(Tool.impl(registry)(_))
  .pipe(LLM.impl(llmClient)(_))
  ...

// 启用具体 middleware：替换对应 effect 的 handler
Agent.loop(...)
  .pipe(HistoryRewrite.implSummarize(threshold = 20)(_))   // 超 20 条压缩
  .pipe(AgentHalt.implOn(confidenceGuard)(_))              // 置信度够高时早停
  .pipe(Tool.impl(registry)(_))
  .pipe(LLM.impl(llmClient)(_))
  ...
```

**启用 / 禁用是整个 handler 的替换**，不是 if-else 开关。类型系统保证每个能力都有且只有一个 handler。

### 5.4 对比：ADT god-effect（LangChain 风格，不推荐）

作为对照——如果直接翻译 LangChain 的 `Command(goto=..., update=...)` 模板到 Scala，会得到：

```scala
sealed trait AgentStep extends ArrowEffect[Const[StepContext], Const[StepDecision]]

final case class StepContext(history: List[Message], remaining: Int, lastResponse: Option[LLMResponse])

enum StepDecision:
  case ContinueThink(newHistory: List[Message])
  case ForceToolCall(invs: List[ToolInvocation])
  case Jump(to: AgentNode)
  case Suspend(tag: String)
  case Terminate(answer: String)
  // ...未来还要加
```

**不推荐**，原因：

| 对比维度 | §5.1 正交小 effect | 本节 ADT god-effect |
|---|---|---|
| 形状匹配 | 异质决策各住各的家 | 异质决策被强行同质化 |
| 类型表达能力清单 | 签名里直接看出（`HistoryRewrite & AgentHalt & ...`） | 要翻 `StepDecision` 定义才知道 |
| 增量演进 | 加能力 = 加一个 effect，移除 = 删一个 | 加 case 要改所有 handler，形成 ADT 膨胀 |
| 抗腐化 | 正交，互不污染 | ADT 会从 5 个 case 腐化到 20+，退化成 `Command(anything)` 翻版 |
| 和 L2/L3 组合一致性 | 所有 middleware 都是独立 handler | 需要中心 gateway，不自然 |
| Kyo 原生度 | 利用 effect union 的类型层能力 | 用 ADT 重造轮子 |

**ADT god-effect 的腐化路径**（可预测）：

```
初版: enum StepDecision { Continue, Terminate }
+1:   ... + Jump(to: AgentNode)
+2:   ... + Suspend(tag)
+3:   ... + Update(patch: StatePatch)                   ← 开始长成 state 补丁器
+4:   ... + Batch(List[StepDecision])                   ← 复合操作进场
+5:   ... + Conditional(guard, then, else)              ← 控制流嵌入 ADT
终点: 等同于 LangChain Python 的 Command(anything)——有类型外壳，无类型实质
```

**每一步都有"合理理由"，累加起来是灾难。** 避免这条路径的办法就是**一开始就不建 god-ADT**——用正交小 effect 的方案 B。

### 5.5 通用机制：middleware = handler transformer

两种方案共享同一个 L4 middleware 机制——**写成 handler transformer**：

```scala
// L4 middleware 通用形态：输入一个某 effect 的 handler，输出一个新的 handler
type HandlerTransform[E] = ... => ...  // 具体签名因 effect 而异
```

举 `HistoryRewrite` 的例子——`runSummarize` 本身就是一个 middleware，它在 identity handler 之上叠加了"超阈值自动压缩"的行为。要叠加多个：

```scala
// 串联：size 超 20 压缩 + 超 50 直接截断 + 每次打 log
val composedRewrite = (v: A < (HistoryRewrite & S)) =>
  v.pipe(HistoryRewrite.implSummarize(20)(_))
   .pipe(HistoryRewrite.implTruncate(50)(_))
   .pipe(HistoryRewrite.implLogging(_))
```

**组合机制**和 L2/L3 一致：`.pipe` 应用、`compose` 在需要"pipeline 作一等值"时使用。不需要新 API——stdlib 够用（参见决策 #13）。

### 5.6 和 L2/L3 的关系

- **机制**：L4 middleware 的底层机制就是 L3（`ArrowEffect.handle`）。**新的不是机制，是反转对象**——从 backend call 升级到 agent 状态转移。
- **组合**：和 L2 middleware 同构，都用 `.pipe` 链式 wire。
- **类型代价**：比 L2 重（要处理 continuation、tag、Frame），但比 LangChain Python 轻——Kyo 的 `ArrowEffect.handle` 把 CPS 的机械部分都封装好了。
- **和 L2/L3 共存**：L4 effect 和 L2/L3 middleware 是**正交**的——`HistoryRewrite.implSummarize` 里面可以调 `LLM.invoke`，而 `LLM.invoke` 本身被 L2 middleware 装饰。两层互不干涉。

---

## 六、现在不做的判据 + 何时触发

按"抽象必须有消费者"原则：

### 现在不做的理由

| 能力 | 现有替代 | 真实消费者？ |
|---|---|---|
| forced think | 无 | ❌ 当前用例不需要 |
| human in the loop | 无 | ❌ 当前是纯自动 agent |
| history compaction | 无 | ❌ history 还短 |
| confidence early stop | `maxSteps` 兜底 | ❌ 当前场景 maxSteps 够用 |
| multi-agent hand-off | 无 | ❌ 单 agent |

**零个真实消费者**。按门槛不做。

### 触发做的判据

**同时满足**下面 ≥ 2 条才启动设计：

1. 出现第一个真实场景硬需要"跳转"（典型：要实现 `HumanApprovalAgent`，必须悬挂等外部输入）
2. 出现第二个独立场景（典型：history 膨胀要 summarize）——双样本门槛
3. 现有 L2/L3 middleware 已经尝试实现上述需求并明显拧巴（signal 是：middleware 内部开始自己 new `Loop`、或者用 `Local[T]` 模拟跨层通信）

### 先不做但先留钩子（不成本的预备）

可以**现在就做**的零成本预备：

1. `Agent.loop` body 里的 `history ++ newMsgs` 提成命名函数 `appendTurn(history, results)`——方便未来提升为 `HistoryRewrite.invoke(history)` suspend 点
2. `Loop.done(...)` / `Loop.continue(...)` 的决策点抽成命名函数 `decideNext(response, ctx)`——方便未来插入 `AgentHalt.invoke()` 等 effect
3. **不**提前引入任何 L4 effect——哪怕只是一个 `AgentHalt`。正交小 effect 方案的前提是**每个 effect 都有真实消费者驱动**，没消费者的 effect 即使"只有一个 input/output"也是空抽象

这是"不抽象但不堵死"的中间态，符合决策 #7 "等样本到位再抽"的精神。按 §5.1 的正交方案，新增一个 L4 能力的增量很小（加一个 `sealed trait` + identity handler + 具体 middleware handler），所以更没理由提前做。

---

## 七、记忆锚点

遇到以下讨论时，先翻这份文档：

- "能不能加一个 middleware 让 agent 中途暂停等外部输入" → 这是 L4 需求，当前架构做不到，看 §5.1 `AgentSuspend` effect
- "LangChain / LangGraph 怎么做的 X 能力" → 参考第四节的代数骨架对照
- "Summarize / compaction 放哪一层" → L4 的 `HistoryRewrite` effect，当前不做，看第六节判据
- "是不是现在就该把 Agent.loop 重构成 step-based" → 不是。看第六节"触发做的判据"
- "要不要做一个统一的 `AgentStep` / `Command` effect" → **不要**。看 §5.4 对比 + ADT 腐化路径
- "L4 要加第 N 个能力" → 加一个正交的新 `ArrowEffect`，**不要扩已有 ADT**

---

## 八、遗留问题（未来再答）

1. `AgentSuspend` / resume 协议设计：怎么把外部事件（human approval）接回到 Kyo continuation？是否要走 `Async` + `Promise` 的组合？悬挂跨进程时状态如何序列化？
2. 正交 effect 数量上限——L4 能力清单膨胀到多少个时签名开始拥挤（`A < (LLM & Tool & HistoryRewrite & AgentHalt & AgentSuspend & AgentForceAction & ...)`）？是否需要一个 type alias 聚合（`type AgentEffects = HistoryRewrite & AgentHalt & ...`）？聚合会不会反噬"签名即能力清单"的透明度？
3. L5（动态 graph）在 Kyo 里有没有更轻的形态？是否可以通过"动态链组装"（runtime 选择 pipe 哪个 handler）模拟大部分 L5 需求而不真正实现 graph？
4. LangChain 的 state 共享（所有 middleware 读同一个 `state` dict）vs Kyo 的 `Local[T]` / `Var[T]` 的语义对照——哪个更干净？正交 effect 方案下还需不需要共享 state，还是每个 effect 的 input/output 已经覆盖所有跨层通信？
