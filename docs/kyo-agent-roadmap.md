# Kyo Agent 后续路线图

以 LangChain 的能力谱系当坐标系，把它翻译到现有的 Kyo + `ArrowEffect` 架构上。同样的语义，我们的形态更类型化、更 effect-first。

## 对标现状

| LangChain 概念 | 我们有吗 | 映射到 Kyo 的形态 | 优先级 |
|---|---|---|---|
| ChatModel | ✓ `LLM` effect | 已对齐 | — |
| BaseMessage 族 | ⚠️ 弱 | `Message` 只有 `Role + String`，缺 `tool_call_id / name` | **P0** |
| Tool | ✓ `Tool` effect | 已对齐 | — |
| AgentExecutor | ✓ `Agent.loop` | 已对齐 | — |
| Memory | ❌ | 目前 history 是裸 `List[Message]` | **P1** |
| Streaming | ❌ | `LLMResponse` → `Stream[LLMEvent]` | P2 |
| Structured Output | ❌ | Typeclass `OutputParser[A]` | P3 |
| PromptTemplate | ❌ | `Prompt[Vars]` 值类型 | P4（观察驱动） |
| Retriever / VectorStore | ❌ | `Retriever` / `Embedder` effect | P5 |
| Callbacks / Tracing | ⚠️ 部分 | 有 `Log` middleware，缺 span / trace | P6 |
| Chain / LCEL | ⚠️ 部分 | `pipe` 已足够，缺 `Runnable` 抽象 | 长期 |
| Agent 类型（ReAct / Functions / Structured） | ⚠️ 只有手写 ReAct | Agent 策略抽象化 | 长期 |

---

## P0：补齐消息模型

### 问题

LangChain 的 `AIMessage` 带 `tool_calls: List[ToolCall]`，`ToolMessage` 带 `tool_call_id` 回指。我们现在把 tool call 塞进 `Message(Role.Assistant, "CALL xxx(args)")` 这种字符串——**下游永远要靠正则 parse，信息不可逆丢失**。

### 目标形态

```scala
enum Message derives CanEqual:
  case System(content: String)
  case User(content: String)
  case Assistant(content: Option[String], toolCalls: List[ToolCall])
  case Tool(callId: String, name: String, content: String)

final case class ToolCall(id: String, name: String, args: String)
```

### 影响面

- `LLMResponse.ToolCalls` 直接携带 `List[ToolCall]`（含 id）
- `Agent.loop` 拼 history 时不再造字符串
- `LLMClient` 接真实 API 时 id 能正确回传
- `Role` enum 可能整体删掉（ADT 自带 tag）

### 为什么优先

后面接真实 OpenAI/Anthropic API 必须有 id 机制，现在不改后面得大翻。**先动数据类型，其他 P 级需求都站在这块地基上**。

---

## P1：Memory 抽象化（解决 history O(n²) 的正路）

### LangChain 谱系

- `ConversationBufferMemory` — 全量
- `ConversationBufferWindowMemory(k=10)` — 滑动窗口
- `ConversationSummaryMemory` — 老消息压缩成摘要
- `ConversationSummaryBufferMemory` — 窗口 + 超限摘要
- `VectorStoreRetrieverMemory` — 按相关度召回

### 目标形态

引入 `Memory` effect，取代裸 `List[Message]`：

```scala
sealed trait Memory extends ArrowEffect[MemoryOp, [R] =>> R]

enum MemoryOp[R]:
  case Append(msg: Message)        extends MemoryOp[Unit]
  case Snapshot                    extends MemoryOp[List[Message]]
  case Clear                       extends MemoryOp[Unit]
  case Checkpoint                  extends MemoryOp[CheckpointId]
  case Restore(id: CheckpointId)   extends MemoryOp[Unit]

object Memory:
  def append(msg: Message): Unit < Memory
  def snapshot: List[Message] < Memory
  
  // 不同实现
  def runBuffer: ...                           // = ConversationBufferMemory
  def runWindow(k: Int): ...                   // = ConversationBufferWindowMemory
  def runSummary(llm: LLMClient, maxTokens: Int): ... // = SummaryMemory
```

### 关键决策点（先讨论再落）

1. **effect vs Var**：`Memory` 是 `ArrowEffect` 还是 `Var[HistoryState]`？
   - ArrowEffect：干净、可拦截、支持 middleware，每次 snapshot 有 continuation 开销
   - Var：快、实现简单，但耦合到具体数据结构
2. **压缩策略**：Summary 是 pluggable 的 `Compressor: List[Message] => Message < LLM` 还是内建？
3. **事务语义**：失败轮 history 回滚现在靠重赋值。改成 Memory 后用 `Memory.checkpoint / restore` 还是保持在 REPL 外层？
4. **容器**：从 `List` 换成 `Vector`？——仅当 Memory 内部实现，不暴露给外层

### 价值

一次性解决 O(n²) 追加 + 长上下文超限 + 后续接向量召回的统一入口。

---

## P2：Streaming LLM 输出

### 问题

LangChain 的 `chat_model.stream(messages)` 返回 async iterator。我们现在只有 `LLMResponse.Answer(String)` 一次性返回。接真实 LLM 时流式 API 是主流。

### 目标形态

```scala
enum LLMEvent:
  case TextDelta(chunk: String)
  case ToolCallDelta(id: String, nameChunk: String, argsChunk: String)
  case Done(finalMessage: Message)

object LLM:
  def complete(history: List[Message]): LLMResponse < LLM           // 现有
  def stream(history: List[Message]): Stream[LLMEvent, LLM]         // 新增
```

### 难点

Kyo 的 `Stream` + 我们的 `ArrowEffect` 怎么接合：

- `Stream` 本身是 effect，ArrowEffect 能 yield 出 Stream 吗？
- 还是 LLM effect 里开个 `streamOp` 子操作 + handler 把它转成 `Stream`？

**需要先写 prototype 摸**。

### 为什么不更早

纯 agent 主循环不依赖 stream，stream 更多是 UX（打字机效果）。但接真实 LLM 时天然需要。

---

## P3：Structured Output

### LangChain

```python
model.with_structured_output(MyPydanticModel)  # 直接返回类型化对象
```

### 目标形态

借助 Scala 3 的 `inline` + `Mirror`：

```scala
trait OutputParser[A]:
  def schema: String                         // 注入 prompt 的 schema 描述
  def parse(raw: String): Result[ParseError, A]

object LLM:
  def completeAs[A: OutputParser](
      history: List[Message]
  ): A < (LLM & Abort[ParseError]) =
    for
      parser <- IO(summon[OutputParser[A]])
      sys = Message.System(
        s"Respond in JSON matching: ${parser.schema}"
      )
      raw <- complete(sys :: history)
      parsed <- Abort.get(parser.parse(raw))
    yield parsed
```

### FP 风味

`OutputParser[A]` 是 typeclass，case class → derive OutputParser，跟 circe / zio-json 思路一致。**类型系统端到端保住**——LLM 返回不是 `String` 而是 `MyCaseClass`。

---

## P4：Prompt 作为值（PromptTemplate）

### LangChain

```python
prompt = ChatPromptTemplate.from_messages([
  ("system", "You are a {role}."),
  ("human", "{question}"),
])
```

### 目标形态

```scala
final case class Prompt[Vars](render: Vars => List[Message])

val agentPrompt: Prompt[(String, String)] = Prompt.of:
  case (role, question) => List(
    Message.System(s"You are a $role."),
    Message.User(question),
  )
```

### 抽不抽？

**观察驱动**。看 prompt 会不会被复用、参数化。如果 agent 只有一个硬编码 prompt，这层抽象纯属 over-engineering。**等第二个 prompt 场景出现再动**。

---

## P5：Retrieval / RAG（长期）

### LangChain

`Retriever` + `VectorStore` + `Embeddings` + `DocumentLoader` + `TextSplitter`

### 目标形态

```scala
sealed trait Retriever extends ArrowEffect[Const[Query], Const[List[Document]]]
sealed trait Embedder  extends ArrowEffect[Const[String], Const[Array[Float]]]
```

配套 `VectorStoreBackend` trait（in-memory / qdrant / pgvector 各一个实现）。

### 独立方向

和 agent loop 相对正交，优先级看产品需求。

---

## P6：Tracing / Callbacks（LangSmith 级）

### 现状

`LLMMiddleware.logging` / `ToolMiddleware.logging` 已有 `call#N` 编号，但事件是扁平 log，不是结构化 span。

### 目标形态

```scala
sealed trait Trace extends ArrowEffect[TraceOp, [R] =>> R]

enum TraceOp[R]:
  case StartSpan(name: String, attrs: Map[String, String]) extends TraceOp[SpanId]
  case EndSpan(id: SpanId, status: SpanStatus)             extends TraceOp[Unit]
  case Event(name: String, attrs: Map[String, String])     extends TraceOp[Unit]

object Trace:
  def runOtel: ...             // 接 OpenTelemetry
  def runConsole: ...          // 本地开发
  def runLangSmith: ...        // 上报 LangSmith
```

LLM / Tool middleware 内部 `Trace.startSpan(...)` / `endSpan(...)`，形成完整的 agent step 调用树。

---

## 不打算做 / 观察再说

- **LCEL（LangChain Expression Language）**：我们的 `pipe` 已经是 LCEL 的 Scala 原生等价物，再抽 `Runnable` trait 是重复建设
- **多种 Agent 类型抽象**：ReAct / Functions / Structured 三种策略，等第二种真要跑再抽，否则直接分 `Agent.loopReact` / `Agent.loopFunctions` 两个顶层函数更清晰
- **Chain of Chains 任意嵌套**：我们的 `ArrowEffect` 本身就允许嵌套，不需要专门的 "chain composition" 抽象

---

## 执行顺序建议

```
P0 消息模型 ──┬─→ P1 Memory ────┬─→ P2 Streaming ──→ 真实 LLM 接入
              │                  │
              └─→ P3 Structured ─┘
                  Output

P6 Tracing   独立，任意时点可插
P4 Prompt    按需触发
P5 RAG       独立方向
```

**P0 是所有路径的前置**。P1 和 P3 可以并行（一个偏状态层，一个偏调用层）。P2 依赖 P0 的 `LLMEvent` 形态，同时是接真实 LLM 的硬前置。

---

## 每一步的交付形态

每个 P 级完工时应产出：

1. **代码**：effect 定义 + 至少 2 个 backend 实现（一个真实 / 一个 in-memory for test）
2. **测试**：对称于现有 `AgentSpec` 的风格，覆盖正常路径 + 至少 1 个失败路径
3. **决策记录**：关键取舍补到 `CLAUDE.md` 的"`core/` 关键设计决策记录"列表，避免后续会话重复推导
4. **demo 更新**：`AgentApp.scala` 扩展一个最小可跑演示
