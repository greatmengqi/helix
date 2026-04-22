# Effect vs Middleware

> 本文聚焦 helix 框架里两个**易混的抽象**：`ArrowEffect` 定义的 effect（Tool / LLM / HistoryRewrite / AgentHalt / ResponseHook）和 `Xxx => Xxx` 形态的 middleware（ToolMiddleware / LLMMiddleware）。它们处理的问题**不在同一层**，但都被叫"拦截器 / 装饰器 / 控制反转"——容易混。
>
> 相关文档：
> - `kyo-arrow-effect.md`：Effect 的定义语法手册（def / invoke / impl 三层）
> - `kyo-middleware-ioc-layers.md`：IoC 的 L1-L6 分层谱系（本文件是它的精讲版，专注 L2 vs L3/L4）

---

## 一句话区分

**Effect 增加一个"新的 suspend 点"（改变流程的能力）；Middleware 装饰一个"已有 backend 调用"（不改变能力，只加装饰）。**

- 加 effect → 改 `Agent.loop` 的效应签名、所有调用方要 wire 新的 impl
- 加 middleware → 不动任何签名，只在 wire 时把 backend 套一层

---

## 本质对比

| 维度 | **Effect** | **Middleware** |
|---|---|---|
| 代码形态 | `sealed trait X extends ArrowEffect[Const[In], Const[Out]]` | `type XMiddleware = Backend => Backend` |
| 抽象层级 | **类型层**（`A < (X & ...)`，进签名） | **值层**（纯函数 endomorphism） |
| 拦截对象 | effect 本身的 **suspend + continuation**（CPS 回调） | 一次 **backend 方法调用**的前后 |
| 组合方式 | `.pipe(X.impl(...))`，类型层 `&` 并集 | `compose`（stdlib `Function1`），值层链 |
| 签名可见度 | **改变** `Agent.loop` 的效应签名 | **不改**任何签名 |
| 强制 wire | 是——不 wire 编译失败 | 否——不加能跑（只是缺装饰） |
| 改变控制流 | **能**（`ResponseHook.implMap` 能把 Answer 改成 ToolCalls） | **不能**（输入输出同形态 Backend） |
| 添加成本 | 高——新文件 + 改签名 + 所有 wire 点 | 低——新 val / def，零 wire 点改动 |
| IoC 谱系层级 | L3（ArrowEffect handler） / L4（跨调用状态） | L2（单次调用装饰器） |

---

## 具象到代码

### Tool effect + ToolMiddleware 的协作

**Tool effect**（L3 层）：

```scala
// def（契约）
sealed trait Tool extends ArrowEffect[Const[ToolInvocation], Const[String]]

// invoke（唤起）
Tool.invoke(name, args): String < Tool

// impl（terminal handler）
Tool.impl(registry: ToolRegistry): A < (Tool & S) => A < (S & IO & Abort[Throwable])
```

`Tool.impl` 内部把 effect 的 continuation 桥到 `ToolRegistry.call(name, args)` 这个 backend 方法——backend 怎么实现、有没有被装饰，effect 不关心。

**ToolMiddleware**（L2 层）：

```scala
type ToolMiddleware = ToolRegistry => ToolRegistry

object ToolMiddleware {
  val errorHandling: ToolMiddleware = ...  // 把 Abort 折成 <tool_error>
  val logging:       ToolMiddleware = ...  // 记 start/ok/fail
  val retry:         ToolMiddleware = ...  // 失败重试
  def callLimit(max: Int): ToolMiddleware = ...  // 全局次数上限
}
```

middleware 是 `ToolRegistry => ToolRegistry`——**输入和输出形态相同**。所以可以无限叠加、任意换序、单元测试，不会影响 `Tool.impl` 那一层。

**两层协作的 wire 代码**：

```scala
val wrapped: ToolRegistry =              // 还是个 ToolRegistry
  (ToolMiddleware.errorHandling          // 外层：折 Abort
    compose ToolMiddleware.logging       // 中层：记 log
    compose ToolMiddleware.retry         // 内层：失败重试
  )(realRegistry)                        // 被装饰的"真"后端

Agent.loop(...)
  .pipe(Tool.impl(wrapped))              // Tool.impl 只看到 ToolRegistry 接口
                                         // 无所谓传进来的是真后端还是被包 3 层的
```

关键认知：`Tool.impl` 的类型签名**只认 `ToolRegistry` 接口**，middleware 装饰在它下面"隐身"。effect 层和 middleware 层是**正交的**——effect 层做"Tool 这个能力存在吗？怎么接入？"，middleware 层做"这次接入的 backend 要不要加 log / retry / cache？"

---

### L4 effects 为什么没有 middleware

HistoryRewrite / AgentHalt / ResponseHook 都**没有**相应的 `XxxMiddleware` 类型。原因：

- Tool / LLM 有 **backend trait**（`ToolRegistry` / `LLMClient`），middleware 装饰的对象是这个 backend
- L4 effects **直接在 impl 里写完整策略逻辑**，没有独立的 backend trait 可以装饰

想给 L4 加"装饰"行为？不用 middleware 抽象——直接写**新 impl**。例如：

```scala
// 想要"既截断又记日志"——不抽象 middleware，就是一个新 impl
def implLoggedKeepLast[A, S](n: Int)(v: A < (HistoryRewrite & S))
    (using Frame, Tag[HistoryRewrite]): A < (S & IO) =
  ArrowEffect.handle(Tag[HistoryRewrite], v)([C] => (h, cont) =>
    Log.info(s"history.rewrite size=${h.size} keep=$n").map(_ =>
      cont(h.takeRight(n))
    )
  )
```

**判据**：有几个 impl 还少（当前每个 L4 effect 2 个），没必要提前抽 middleware 层。`kyo-middleware-ioc-layers.md` §5.5 讨论过"L4 middleware = handler transformer"——真要抽，也是等出现 **≥3 个独立关注点**（例如 log / metric / cache 都想横切加到 impl 上）再动手。

---

## 决策树：新能力该用哪个？

```
我要加新能力 X
│
├─ X 是一个"新 suspend 点"（业务代码要 X.invoke(...)）？
│   │
│   ├─ 是 → 这是 effect（会改签名）
│   │       ├─ 反转单次调用的 continuation？    → L3 effect（Tool / LLM 这类）
│   │       └─ 反转跨调用的状态决策？           → L4 effect（HistoryRewrite / AgentHalt / ResponseHook）
│   │
│   └─ 否 → 看下一条
│
├─ X 是现有 backend 的"横切关注"（log / retry / cache / limit / fallback）？
│   │
│   ├─ 是 → 这是 middleware（不改签名）
│   │       - 加到 ToolMiddleware / LLMMiddleware object 里
│   │       - 对应 L2
│   │
│   └─ 否 → X 也许是更高层的东西（L5 动态图 / L6 会话策略）
│            参考 kyo-middleware-ioc-layers.md 第一节
```

**一句话判断**：改完你要不要动 `< (...)` 效应签名？

- **要动** → effect
- **不用动** → middleware

---

## 代数视角

两者的组合律来自**不同代数**：

| 代数性质 | Effect | Middleware |
|---|---|---|
| 组合子 | 类型并集 `X & Y`（commutative set union） | `Function1.compose`（associative monoid） |
| 单位元 | `Any`（空效应） | `identity[Backend]`（空装饰） |
| 元素形态 | codata（被 handler 消费的 suspension） | data（一阶函数值） |
| 作用方向 | CPS 反向（handler 决定 continuation） | direct 正向（wrap→call→unwrap） |

两者**互相嵌套、各管一摊**：
- `Tool.impl(wrapped)` 里，`Tool` 是 effect 层 IoC，`wrapped` 是 middleware 层 IoC
- 两层同时在工作，互不干扰

这就是 `kyo-middleware-ioc-layers.md` §1 说的"**独立反转轴**"——不是嵌套栈，是不同维度的控制反转。

---

## 关键要点

1. **加什么时机不同**
   - Effect：需要业务代码显式调 `Xxx.invoke(...)`，才写 effect
   - Middleware：想给已有 backend 加装饰（log / retry / cache 等），才写 middleware

2. **破坏性不同**
   - Effect 改签名，是 **breaking API change**，需要评估下游
   - Middleware 不改签名，是**纯增量扩展**，可以随时加

3. **扩展点优先原则**
   - 能用 middleware 解决的，优先 middleware（零破坏）
   - 只有需要"新 suspend 点"或"改变控制流"时才升格到 effect
   - 参考 `CLAUDE.md` 的 Project Purpose 第 5 条"扩展点优先"

4. **类型系统的角色不同**
   - Effect 由类型系统强制 wire——忘了 wire，编译直接失败
   - Middleware 不被类型系统约束——忘了加，代码能跑，只是缺功能

5. **和 Kyo `kyo.Log` 的对照**
   - `kyo.Log` 是 **`Local[T]` effect**，不走 ArrowEffect，不进效应签名，但能被 `Log.withConsoleLogger(...)` wire。它**介于** effect 和 middleware 之间——有类型层身份（Local），但调用点不污染签名。这是 Kyo 效应三档（`Env` / `Local` / `ArrowEffect`）中的第二档，按"贯穿计算的配置 vs 要被拦截的协议"选
   - 详见 `CLAUDE.md` 关键设计决策 #12

---

## 速查：helix 现有结构

| 层 | 名字 | 形态 |
|---|---|---|
| L3 effect | `Tool` | `ArrowEffect[Const[ToolInvocation], Const[String]]` + `ToolRegistry` backend |
| L3 effect | `LLM` | `ArrowEffect[Const[List[Message]], Const[LLMResponse]]` + `LLMClient` backend |
| L4 effect | `HistoryRewrite` | `ArrowEffect[Const[List[Message]], Const[List[Message]]]`（无 backend，多 impl） |
| L4 effect | `AgentHalt` | `ArrowEffect[Const[Unit], Const[Option[String]]]` |
| L4 effect | `ResponseHook` | `ArrowEffect[Const[LLMResponse], Const[LLMResponse]]` |
| L2 middleware | `ToolMiddleware` | `ToolRegistry => ToolRegistry`（errorHandling / logging / retry / callLimit） |
| L2 middleware | `LLMMiddleware` | `LLMClient => LLMClient`（logging / retry / caching / fallback / callLimit） |

新加能力时对着这张表想："我的新需求应该长成哪一行的形态？" 答案自动揭示该用 effect 还是 middleware。
