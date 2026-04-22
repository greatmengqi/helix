# Kyo ArrowEffect 定义手册

> 结合本项目 `core/src/main/scala/agent/` 下的 `Tool` 和 `LLM` 两个真实 effect 总结。

## 概览

一个完整的 ArrowEffect 定义有 **2 部分强制 + 3 部分约定**：

| 类别 | 成员 | Tool 示例 | LLM 示例 |
|------|------|-----------|----------|
| **强制 ①** | Effect marker trait | `sealed trait Tool extends ArrowEffect[Const[ToolInvocation], Const[String]]` | `sealed trait LLM extends ArrowEffect[Const[List[Message]], Const[LLMResponse]]` |
| **强制 ②** | Companion object 的 suspend + handle | `Tool.call` / `Tool.run` | `LLM.complete` / `LLM.run` |
| **约定 ③** | Input 领域类型 | `case class ToolInvocation(name, args)` | 直接复用 `List[Message]` |
| **约定 ④** | Backend trait | `trait ToolRegistry` | `trait LLMClient` |
| **约定 ⑤** | Middleware | `ToolMiddleware` + `object ToolMiddleware` | `LLMMiddleware` + `object LLMMiddleware` |

---

## 强制部分（Kyo API 语法要求）

### ① Effect marker trait

```scala
sealed trait LLM extends ArrowEffect[Const[List[Message]], Const[LLMResponse]]
```

要点：

- `sealed trait`：约定，effect 是**类型级标识符**，没人实例化它，所有语义由 handler 提供。用 `sealed` 防止外部扩展
- 类型参数 `ArrowEffect[Input[_], Output[_]]`——两个 kind 为 `[_] =>> X` 的"容器"
- `Const[X]` = `[_] =>> X`：每次调用的 I/O 类型稳定，不跨类型参数变。对照 GADT-style effect（比如 `Var[S]` 的 get 返回 S、set 接收 S 类型不同），Const 是"同一个 channel 类型恒定"的场景

### ② Companion object 的 suspension + handler

两个 inline 方法，一对"发射/消费"原语。

**Suspension**（发射 effect）：

```scala
inline def complete(history: List[Message])(using
    inline frame: Frame,
    inline tag: Tag[LLM]
): LLMResponse < LLM =
  ArrowEffect.suspend[Any](tag, history)
```

- `Frame`：调用点元信息（文件、行号），用于错误栈/trace
- `Tag[LLM]`：运行时识别 effect 标识，Kyo 靠 macro 自动派生
- `suspend[Any](tag, input)`：把输入"抛"进 effect 树，等待上层 handler 接管
- 返回类型 `LLMResponse < LLM`：**这个计算会产出 LLMResponse，但前提是 LLM effect 被处理**

**Terminal handler**（discharge effect）：

```scala
inline def run[A, S](client: LLMClient)(v: A < (LLM & S))(using
    inline frame: Frame,
    inline tag: Tag[LLM]
): A < (S & Abort[Throwable]) =
  ArrowEffect.handle(tag, v)([C] =>
    (input, cont) => client.complete(input).map(cont)
  )
```

- 签名变化：`A < (LLM & S)` → `A < (S & Abort[Throwable])`
  - `LLM` 消失——effect 被 discharge
  - 新增 `Abort[Throwable]`——`client.complete` 可能抛错
- `handle(tag, v)` 回调签名 `(input, cont) => O`：每次 effect 被 raise 时，handler 拿到 `input` 和"继续执行"的 continuation `cont`
- `[C] =>`：poly-function 的类型参数（Scala 3 多态函数语法），因为 `cont` 返回类型要跨 call 点通用

---

## 约定部分（不强制但强烈建议）

### ③ Input 领域类型

**什么时候用 case class**：多字段、需要模式匹配、值得单独命名

```scala
final case class ToolInvocation(name: String, args: Map[String, String])
```

**什么时候不用**：已有足够表达力的类型

```scala
// LLM 直接用 List[Message]，没必要再包一层
sealed trait LLM extends ArrowEffect[Const[List[Message]], Const[LLMResponse]]
```

判断标准：如果 Input 将来会有更多字段、需要被其他模块引用、有自然的领域名字——做 case class；否则直接用标准库类型。

### ④ Backend trait

把"effect 实际怎么处理"抽成一个接口：

```scala
trait LLMClient {
  def complete(history: List[Message]): LLMResponse < Abort[Throwable]
}

trait ToolRegistry {
  def call(name: String, args: Map[String, String]): String < Abort[Throwable]
}
```

**作用**：handler 是 **effect ↔ backend 的桥**。

```
用户代码       effect 定义层        backend impl
 ─────         ────────────        ───────────
LLM.complete  ==(handle)==>  LLMClient.complete
```

为什么要这一层？直接在 handler 里硬编码也能工作，但抽成 trait 的好处：

1. **多 impl 注入**：真实 LLM / mock / scripted / replay——`LLM.run(差异 client)` 切换
2. **装饰器模式**（即 middleware）：`LLM.run(logging(retry(realClient)))`
3. **依赖倒置**：effect 定义不耦合具体后端

### ⑤ Middleware（装饰器）

```scala
type LLMMiddleware = LLMClient => LLMClient

object LLMMiddleware {
  val logging: LLMMiddleware = ...
  // 不封装 chain 函数——middleware 是 endomorphism，stdlib compose 已经是 monoid 组合子
}
```

**引入时机**：出现第一个横切关注点（logging / retry / caching / fallback / ratelimit）就抽。

本项目 Tool / LLM 两侧都有 `XMiddleware`，组合走 stdlib：

- `(outer compose ... compose inner)(backend)`：compose 左侧最外层（类 HTTP middleware stack）
- 常见排布：`(errorHandling compose logging)(backend)`——logging 在内拿原始异常，errorHandling 在外折叠
- 应用时用 `scala.util.chaining.pipe`：`backend.pipe(stack)` 让数据左到右流

---

## 完整骨架模板

把上面所有部分按依赖顺序拼起来：

```scala
package agent

import kyo.*
import kyo.kernel.*

// ③ Input 领域类型（可选）
final case class XInvocation(field1: String, field2: Map[String, String])

// ① Effect marker trait
sealed trait X extends ArrowEffect[Const[XInvocation], Const[XResult]]

// ④ Backend trait（可选但常用）
trait XBackend {
  def handle(inv: XInvocation): XResult < Abort[Throwable]
}

// ② Suspension + terminal handler
object X {

  inline def call(inv: XInvocation)(using
      inline frame: Frame,
      inline tag: Tag[X]
  ): XResult < X =
    ArrowEffect.suspend[Any](tag, inv)

  inline def run[A, S](backend: XBackend)(v: A < (X & S))(using
      inline frame: Frame,
      inline tag: Tag[X]
  ): A < (S & Abort[Throwable]) =
    ArrowEffect.handle(tag, v)([C] =>
      (input, cont) => backend.handle(input).map(cont)
    )
}

// ⑤ Middleware（可选，横切关注点 >= 1 个时引入）
type XMiddleware = XBackend => XBackend

object XMiddleware {

  val logging: XMiddleware = loggingWith(Logger.stdout)

  def loggingWith(logger: Logger): XMiddleware = next =>
    new XBackend {
      def handle(inv: XInvocation): XResult < Abort[Throwable] = {
        logger.info(s"x.call.start $inv")
        // ... 参考 Tool.scala / LLM.scala 的 Abort.run + Result 三分支观测模式
        next.handle(inv)
      }
    }

  // 不封装 chain：调用方用 stdlib compose + pipe
  // 例如：xBackend.pipe(XMiddleware.logging compose XMiddleware.caching)
}
```

---

## 典型 wire 顺序

```scala
import scala.util.chaining.*

val program: Unit < (X & Y & IO & Abort[Throwable]) = Agent.repl

program
  .pipe(X.run(xBackend.pipe(XMiddleware.logging)))   // 消 X，中间件栈 = logging
  .pipe(Y.run(yBackend.pipe(YMiddleware.logging)))   // 消 Y
  .pipe(IO.Unsafe.run)                               // 消 IO
  .pipe(Abort.run[Throwable])                        // 消 Abort
  .eval                                              // 抽取 Result
```

规则：

1. **从里到外 discharge**：业务代码写成 `A < (X & Y & IO & Abort)`，handler 一层层剥皮
2. **顺序无关（但结果类型有差）**：`X.run` 和 `Y.run` 可以交换，但最终类型会反映剩余 effect 的并集
3. **IO 和 Abort 通常最后**：因为 backend impl 里可能抛 Abort，IO 的不安全 run 通常是最外层入口

---

## 核心概念对齐

ArrowEffect 和其他 effect 系统的对应：

| 概念 | Kyo | Koka | OCaml 5 | Eff monad | cats-effect |
|------|-----|------|---------|-----------|-------------|
| Effect 定义 | `sealed trait X extends ArrowEffect[...]` | `effect X { ... }` | `type _ X += ...` | `trait X[F[_]]` | `F[_]: Effect` |
| 发射 | `ArrowEffect.suspend(tag, input)` | `X.op(input)` | `perform (X input)` | `send[X](...)` | `F.delay(...)` |
| 接住 | `ArrowEffect.handle(tag, v)([C] => (input, cont) => ...)` | `handler { op(input, resume) => ... }` | `try ... with effect X input k -> ...` | `runState`/`interpret` | `MonadCancel#uncancelable` |

核心是 **suspend/resume** 这对原语——把输入"挂起"送进 effect 树，handler "接住"后用 backend 处理并通过 `cont` 把结果送回原计算。这就是 **delimited continuation** 的 effect 系统化。

---

## 决策指南：什么时候跳过约定部分

| 场景 | 省略哪部分 | 理由 |
|------|-----------|------|
| Input 是标准库类型、不需命名 | ③ Input 案例类 | LLM 直接用 `List[Message]`，不造 `LLMRequest` |
| Backend 只有一个实现、永远不会换 | ④ Backend trait | 直接在 handler 里调具体函数（但失去 middleware 能力，回想清楚） |
| 没有任何横切关注点 | ⑤ Middleware | 等第一个出现（logging / retry 等）再抽，不提前付抽象税 |

**不能省的永远是 ① 和 ②**——它们是 Kyo API 的语法门槛。省了就不是 ArrowEffect 了。
