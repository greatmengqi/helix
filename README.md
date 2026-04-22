# scala_learn — Kyo ArrowEffect Agent Loop

基于 [Kyo 0.19](https://getkyo.io/) `ArrowEffect` 构建的 ReAct agent 框架。Tool 和 LLM 两侧架构对称，`def / invoke / impl` 三层命名贯穿所有 effect，横切关注点走 middleware，状态转移走 L4 正交小 effect，replay / reflexion 这类控制结构走 L5 meta-loop。

不是学习仓库——按框架库标准维护代码质量、API 稳定性、扩展点设计。

## Build & Run

- Scala **3.8.3** + Mill **1.1.5**（单文件 `build.mill`，非 sbt）
- 运行时依赖：`io.getkyo::kyo-core` / `kyo-direct` 0.19.0 + `slf4j-simple`
- 测试：munit 1.0.3

```bash
mill core.compile                   # 编译
mill core.test                      # 跑 138 个单测
mill core.runMain agent.AgentApp    # 起 REPL demo（HeuristicLLM + DemoTools）
mill __.reformat                    # scalafmt
```

## 五层结构

```
L5  Agent.loopWithReplay       — meta-loop，judge 驱动重入
L4  HistoryRewrite / AgentHalt / ResponseHook
                               — 状态转移级 IoC（LangChain hook 对位）
L3  Tool / LLM ArrowEffect     — 协议调用通道，Backend trait 做实现注入
L2  ToolMiddleware / LLMMiddleware
                               — 横切关注点（error handling / retry / logging / caching）
L1  Loop + decideNext + appendTurn
                               — ReAct 主循环骨架
```

每层都遵守**扩展不改核心**：加新 tool backend 不改 `Tool` effect，加新 middleware 不改 Backend trait，加新 halt 策略不改 `Agent.loop`，加新 replay 策略不改 `loopWithReplay`。

## 核心 API

```scala
// 主循环（stateful，出入参都是 history）
Agent.loop(initialHistory, maxSteps): (String, List[Message]) < (LLM & Tool & ...)
Agent.loop(userInput, maxSteps = DefaultMaxSteps): String < (...)  // 便捷 overload

// L5 meta-loop（judge 决定是否重入下一轮）
Agent.loopWithReplay(initialHistory, maxRounds, judge, innerMaxSteps): ...
Agent.loopWithReplay(userInput, maxRounds, judge, innerMaxSteps = DefaultMaxSteps): ...

// REPL（跨 turn 累计 history + per-turn Abort 隔离）
Agent.repl(registry, llm): Unit < (IO & Abort[Throwable])
```

Effect 签名就是能力清单——上面 `...` 处完整写开是：

```
LLM & Tool & HistoryRewrite & AgentHalt & ResponseHook & IO & Abort[Throwable]
```

每个 effect 必须显式 wire（Kyo 不提供 ambient 默认 handler）：

```scala
Agent.loop(...)
  .pipe(HistoryRewrite.implKeepLast(20)(_))   // L4 改写 history
  .pipe(AgentHalt.implOn(budgetGuard)(_))     // L4 软终止
  .pipe(ResponseHook.implMap(filterTools)(_)) // L4 response 改写
  .pipe(Tool.impl(registry))                  // L3 discharge
  .pipe(LLM.impl(llmClient))                  // L3 discharge
  .pipe(Log.withConsoleLogger(_))             // 日志 backend
  .pipe(IO.Unsafe.run(_))
  .pipe(Abort.run[Throwable](_))
```

## 扩展点

| 想做 | 应该改哪层 | 不该碰 |
|---|---|---|
| 新 Tool 实现（DB / HTTP / MCP） | 写新 `ToolRegistry` | `Tool` effect / `Agent.loop` |
| 新 LLM backend（Claude / OpenAI / Mock） | 写新 `LLMClient` | `LLM` effect |
| tool 错误处理 / retry / cache | 写新 `ToolMiddleware` | `ToolRegistry` trait |
| LLM response 缓存 / 多模型降级 | 写新 `LLMMiddleware` | `LLMClient` trait |
| history 压缩策略 | `HistoryRewrite.implXxx` 新 handler | effect def / invoke |
| 外部信号早停（budget / 关键字） | `AgentHalt.implXxx` 新 handler | 主循环 |
| LLM 输出后处理（过滤 / 强制工具） | `ResponseHook.implXxx` 新 handler | 主循环 |
| reflexion / 自检 / 多轮修正 | 包在 `loopWithReplay` 的 `judge` 里 | L1–L4 |

## 文档

- [`CLAUDE.md`](CLAUDE.md) — 架构快照 + 15 条关键设计决策（Loop vs 自递归 / ArrowEffect vs Env / Middleware compose / pipe eta-expansion 坑 等）
- [`docs/kyo-arrow-effect.md`](docs/kyo-arrow-effect.md) — ArrowEffect 机制与本项目的用法
- [`docs/kyo-middleware-ioc-layers.md`](docs/kyo-middleware-ioc-layers.md) — 五层 IoC 分层论证
- [`docs/effect-vs-middleware.md`](docs/effect-vs-middleware.md) — effect 和 middleware 各自的作用对象
- [`docs/kyo-agent-roadmap.md`](docs/kyo-agent-roadmap.md) — 未来能力演进路线
- [`docs/mill-tutorial.md`](docs/mill-tutorial.md) — Mill 构建工具快速上手

## CI

`.github/workflows/ci.yml` 跑三步：`mill __.checkFormat` → `mill core.compile` → `mill core.test`，JDK 21 + Mill 1.1.5 从 Maven Central 拉 `mill-dist` launcher。
