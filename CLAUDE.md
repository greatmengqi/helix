# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

- **Scala 3.8.3**，构建工具 **mill**（单文件 `build.mill`，非 sbt）
- 模块与依赖：
  - `core` — Kyo effect system + Agent 主循环练习，依赖 `io.getkyo::kyo-core`、`kyo-direct`（v0.19.0）
  - `core.test` — munit 1.0.3

常用命令：
```
mill core.compile                   # 编译 core 模块
mill core.test                      # 跑单测
mill core.runMain agent.AgentApp    # 起 Agent REPL demo
```

## Project Purpose

**这是一个 Scala agent loop 框架**，基于 Kyo 0.19 `ArrowEffect` 构建，目标是产出可复用、可组合、可扩展的 ReAct agent 基础设施。**不是学习/练习仓库**，按框架库标准要求代码质量、API 稳定性、扩展点设计。

Claude 的工作方式：
1. **架构决策**：遇到抽象/模式取舍时，摆出选项 + 各自取舍理由，让用户拍板；**不自作主张做结构重构**
2. **落地实现**：按讨论达成的方向落代码，保持 FP 风格（ADT / 显式 effect / 不可变默认 / 组合性）
3. **维持对称性**：Tool 和 LLM 两侧已架构对称（同构的 ArrowEffect + Backend trait + object + Middleware），新能力默认保持两侧同步扩展
4. **对外 API 敏感**：public signature（`Agent.loop` / `Tool` / `LLM` / 各 `Middleware` 对象方法）的任何变更都要评估破坏性，优先考虑可叠加扩展而非 breaking change；必要的 breaking 变更明确标注
5. **扩展点优先**：用户的新需求若能通过写新 middleware / 新 backend 实现，就不碰核心 effect 定义——这是框架可扩展性的主要证据

## `core/` 当前架构快照

```
core/src/main/scala/agent/
├── AgentLoop.scala       — Role / Message / object Agent（loop + repl）
├── Tool.scala            — ToolInvocation / Tool effect / ToolRegistry / object Tool / ToolMiddleware
├── LLM.scala             — LLMResponse / LLMClient / LLM effect / object LLM / LLMMiddleware
├── HistoryRewrite.scala  — L4 effect：before_model hook，runIdentity / runKeepLast
└── AgentApp.scala        — 可运行 demo（HeuristicLLM + DemoTools + REPL）
```

**L2/L3 对称结构**（Tool / LLM 两侧完全平行）：

| 层 | Effect | Backend trait | Suspension | Handler | Middleware |
|---|---|---|---|---|---|
| Tool | `sealed trait Tool extends ArrowEffect[Const[ToolInvocation], Const[String]]` | `ToolRegistry` | `Tool.call(name, args)` | `Tool.run(registry)` | `ToolMiddleware = ToolRegistry => ToolRegistry` |
| LLM | `sealed trait LLM extends ArrowEffect[Const[List[Message]], Const[LLMResponse]]` | `LLMClient` | `LLM.complete(history)` | `LLM.run(client)` | `LLMMiddleware = LLMClient => LLMClient` |

**Backend trait 签名**：两侧 `call` / `complete` 都是 `... < (IO & Abort[Throwable])`——`IO` 是为 middleware 走 `kyo.Log` 留的通道。

**L4 effects**（状态转移级 IoC，正交小 effect 方案，参见 `docs/kyo-middleware-ioc-layers.md`）：

| Effect | suspend 时机 | 对应 LangChain hook | 已落地 handler |
|---|---|---|---|
| `HistoryRewrite` | 每次 `LLM.complete` 之前 | `before_model` / `modify_model_request` | `runIdentity` / `runKeepLast(n)` |
| _未来_ `AgentHalt` | `decideNext` 入口 | `Command(goto='end')` | — |
| _未来_ `ResponseHook` | `LLM.complete` 之后 | `after_model` | — |

**L4 的 compaction 语义**（和 LangChain 共享）：`HistoryRewrite.apply(history)` 返回的 `rewritten` 成为当前 turn 的 history 基底——`decideNext` / `appendTurn` 都用 rewritten。若 handler 做了压缩，后续 session history 随之收敛，不是纯 view。

**L4 wire**：和 L2/L3 同构，pipe 链叠加。每个 effect 必须显式 wire（Kyo 不提供 ambient 默认 handler），签名即能力清单：

```scala
Agent.loop(...)
  .pipe(HistoryRewrite.runKeepLast(20)(_))   // L4 改写
  .pipe(Tool.run(registry))                  // L3 discharge
  .pipe(LLM.run(llmClient))                  // L3 discharge
  ...
```

**Agent.loop 状态**：
- 主版 `loop(initialHistory, maxSteps): (String, List[Message]) < (LLM & Tool & IO & Abort[Throwable])`——入参 history、返回(答案, 含 tool 轨迹与终答的完整 history)
- 单发 overload `loop(userInput, maxSteps = DefaultMaxSteps): String < ...`——便捷入口
- 用 `Loop[List[Message], Int, (String, List[Message]), _]` 把 history 和剩余步数作为循环状态显式 threading

**Agent.repl 跨 turn 累计**：
- `Loop[List[Message], Unit, Unit, _]` 把 session 级 history 作为循环状态
- per-turn 异常隔离：`Abort.recover[Throwable]` 把 agent 失败折成错误字符串 + **回滚 history**（丢弃失败轮 user 输入，防止污染后续 LLM 上下文），REPL 继续存活

**Middleware**（已落地）：
- `ToolMiddleware.errorHandling`：把 Abort 折叠成 `<tool_error ...>` 字符串喂给 LLM，同时 `Log.error` 留痕
- `ToolMiddleware.logging`：纯观测（start / ok / fail + elapsed），失败照样 propagate
- `LLMMiddleware.logging`：对称版，带 `llm.call#N` 编号（AtomicInteger session 级单调递增）
- **组合用 stdlib `compose`**：`(a compose b compose c)(backend)` == `a(b(c(backend)))`，compose 左侧最外层，像 HTTP middleware stack。不封装 `chain` 函数（详见决策 #13）
- **应用用 `scala.util.chaining.pipe`**：`backend.pipe(stack)` 让数据左到右流，和 `compose` 的右到左构造互补
- **推荐排布**：`(errorHandling compose logging)(registry)`——logging 在内层拿原始异常，errorHandling 在外层折叠。反过来 `logging compose errorHandling` 会把 `<tool_error>` 当成功 log，**telemetry 说谎**

**日志走 `kyo.Log`**：
- middleware 内部用 `Log.info(...)` / `Log.error(msg, throwable)`，无需持有 logger 实例
- 最外层 wire `Log.withConsoleLogger(...)`（AgentApp）或 `Log.withConsoleLogger(name, Log.Level.silent)(...)`（测试）注入 backend
- 输出自动带 source location `[file:line:col]`，靠 `Frame` macro 编译期捕获
- SLF4J 集成：classpath 加 `slf4j-simple` / logback / log4j2 即生效，业务代码零改动；不加则 fallback 到 Kyo 自建 console

## `core/` 关键设计决策记录

未来会话遇到相关话题时，**先查这里**避免重复推导：

1. **`Loop[...]` vs tail-recursive turn**：自递归 + for-comprehension 的 continuation 会随迭代累积，O(n²) 退化。必须用 Kyo `Loop` 原语，它内部是 trampoline + per-iteration effect discharge
2. **`Loop.continue/done` 是 ADT 值，`Loop[...]` 是 interpreter**：两者是"指令数据/执行器"关系，对应 cats `tailRecM` 的 Either。`continue` 本身不驱动迭代，扔在那儿不会继续
3. **Scala 3 overload + default 限制**：同名 overload 最多只有一个能带 default 参数值（生成的 `xxx$default$N` accessor 名字冲突）。共享默认常量要提升成命名 `val`（本项目 `Agent.DefaultMaxSteps`）
4. **LLM: Env → ArrowEffect 升级**：`Env[LLMClient]` 只能注入 value；`ArrowEffect` 能拦截每次调用并干预 continuation（caching / replay / batch）。升级是为了未来能力（prompt cache / response recording），**不是今天就要这些能力**
5. **`Abort.recover` vs `Abort.run + Result`**：前者 discharge Abort effect；后者把错误降成纯数据（Result.Success/Failure/Panic）观测后再重新 inject。logging middleware 要"观测不吞"必须用后者
6. **History append 的 O(n²) 问题**：`history ++ newMsgs` 每轮 O(n)，n 轮 O(n²)。**不做数据结构微优化**（别换 Vector/ListBuffer），应做语义层压缩（滑动窗口 / summarize / 卸载到持久层）。等 history 读写点扩散出 `Agent.loop`（middleware 想读、持久层想 observe）时，再引入 `Var[List[AgentEvent]]` 作为单一事件源，history 和 trace 从同源投影出来
7. **Middleware 抽象引入时机**：出现第二个同类 cross-cutting 关注点才抽。`ToolMiddleware` 早有，是因为 error handling 和后续 logging / retry / caching 都是横切。对称地，LLM 侧凑够 logging 一个就抽了 `LLMMiddleware` 是因为**对称性本身也是动机**（两侧分歧是后续腐化的开始）
8. **`Loop.foreach` 和 `Loop[...]` 的选择**：0-state 用 foreach，多 state 用带类型参数的 `Loop[S1, (S2?), A, Pending]`。`Loop.foreach` 本质是 `Loop[Unit, Unit, Unit, _]` 的语法糖
9. **`Const[X]` 的含义**：`ArrowEffect[Const[In], Const[Out]]` 表示"每次调用 input/output 类型固定"。对照 GADT-like effect（比如 `Var[S]` 的 get 和 set 类型不同），Const 适合"同一个 channel 稳定类型"的场景。Tool / LLM 都是 Const
10. **`kyo.System` 命名陷阱**：`import kyo.*` 会引入 `kyo.System`（环境变量相关 effect），遮蔽 `java.lang.System`。要用 stderr / nanoTime 等写 `java.lang.System.xxx` 全限定名
11. **日志用 `kyo.Log`，不自己造 `Logger` trait**：`kyo.Log` 用 `Local[Log]` 实现（不是 ArrowEffect），调用点 `Log.info/error` 不污染效应签名只引入 `IO`。**不要再造 `trait Logger { def info(...): Unit }`**——已经踩过坑、已经迁移完。中间件不再持有 logger 实例参数，从环境取，最外层 wire `Log.withConsoleLogger(...)` 注入。SLF4J backend classpath 加一行就接入，业务零改动
12. **Local vs ArrowEffect 的语义边界**：贯穿计算的"上下文配置"（logger、tracer、当前 user_id）适合 `Local[T]`——快速读，无 continuation 开销。每次都要被拦截/接管的"协议调用"（LLM、Tool、HTTP）适合 `ArrowEffect`。Kyo 的 Env / Local / ArrowEffect 三档代价递增，按粒度选
13. **不抽 `chain` 函数组合 middleware**：`ToolMiddleware` / `LLMMiddleware` 本质是 `A => A`，stdlib `Function1.compose` 已经是 endomorphism monoid 的组合子，`scala.util.chaining.pipe` 给出应用语义。自建 `chain(mws*)` 只是 API 聚合（variadic sugar + 命名空间），**不引入新代数能力**。按"抽象必须区分什么 / 必须买到新能力"的原则删除。未来如果出现需要"middleware 栈作为一等值参与更大代数运算"（比如 `Monoid[Endo]` 参与环境配置合并），再引入也来得及——那时候是代数抽象，不是 API 聚合
14. **`.pipe(f)` + Kyo handler 的 eta-expansion 坑**：Scala 3 不会自动把带 `using Frame` / context function 的方法 eta-expand 成 `A => B`。`Kyo` 的 effect handler（`IO.Unsafe.run` / `Abort.run[E]` / `Log.withConsoleLogger` / 自定义 `Tool.run(registry)` / `LLM.run(client)` 等）**几乎都有 Frame context**，所以 `.pipe(Log.withConsoleLogger)` 会编译报 `Found: Frame ?=> ... =>, Required: A =>` 之类的型错。**一律写 `.pipe(handler(_))`**——underscore 强制 eta-expand 成普通函数，Frame 从外层 scope 注入。这不是 pipe 的缺陷，是 Scala 3 context-function 类型和普通函数类型不互为 subtype 的直接后果
