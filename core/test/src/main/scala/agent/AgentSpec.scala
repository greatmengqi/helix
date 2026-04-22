package agent

import kyo.*
import kyo.AllowUnsafe.embrace.danger
import kyo.kernel.*
import scala.util.chaining.*

/** Agent 端到端单元测试。覆盖：
  *   - 纯回答（无工具）
  *   - 单工具调用 + history 累积
  *   - 多工具（一次 response 多 invocation）串行执行
  *   - `errorHandling` middleware 吞错成字符串
  *   - 无 middleware 时 registry 错误冒成 Abort
  *   - `maxSteps` 耗尽 → Abort
  *   - middleware 组合顺序
  */
class AgentSpec extends munit.FunSuite {

  // ====================== 可控 mock：按固定序列产出 LLM response ======================

  /** 按预设队列依次吐 response，用完则 Answer 兜底。 这样每个测试都能精确控制"第 N 次 LLM 调用返回什么"，不用共享
    * MockLLM 的启发式 match。
    */
  final class ScriptedLLM(responses: List[LLMResponse]) extends LLMClient {
    private val it = responses.iterator
    def complete(
        history: List[Message]
    ): LLMResponse < (IO & Abort[Throwable]) =
      if (it.hasNext) it.next()
      else LLMResponse.Answer("<exhausted>")
  }

  object TestTools extends ToolRegistry {
    def call(
        name: String,
        args: Map[String, String]
    ): String < (IO & Abort[Throwable]) =
      name match {
        case "weather" => s"${args.getOrElse("city", "?")}: sunny 28°C"
        case "add"     =>
          val a = args.getOrElse("a", "0").toInt
          val b = args.getOrElse("b", "0").toInt
          s"${a + b}"
        case other =>
          Abort.fail(new RuntimeException(s"unknown tool: $other"))
      }
  }

  // ====================== 测试 harness：把 effect tree 跑到 Result ======================

  /** 静默 logger：测试用 console silent 级别防止 log 噪声污染输出。 */
  private def runSilent[A, S](v: A < S)(using kyo.Frame): A < S =
    Log.withConsoleLogger("kyo.test", Log.Level.silent)(v)

  def runAgent(
      llm: LLMClient,
      registry: ToolRegistry,
      input: String,
      maxSteps: Int = 6
  ): Result[Throwable, String] =
    Agent
      .loop(input, maxSteps)
      .pipe(HistoryRewrite.runIdentity(_))
      .pipe(AgentHalt.runNever(_))
      .pipe(ResponseHook.runIdentity(_))
      .pipe(Tool.run(registry))
      .pipe(LLM.run(llm))
      .pipe(runSilent(_))
      .pipe(IO.Unsafe.run(_))
      .pipe(Abort.run[Throwable](_))
      .eval

  // ====================== 测试用例 ======================

  test("pure answer: no tool calls, LLM answers directly") {
    val llm = new ScriptedLLM(List(LLMResponse.Answer("hello world")))
    val r = runAgent(llm, TestTools, "hi")
    assertEquals(r, Result.Success("hello world"))
  }

  test("single tool: LLM asks weather, sees result, answers") {
    val llm = new ScriptedLLM(
      List(
        LLMResponse.ToolCalls(
          List(ToolInvocation("weather", Map("city" -> "Shanghai")))
        ),
        LLMResponse.Answer("上海今天晴")
      )
    )
    val r = runAgent(llm, TestTools, "上海天气")
    assertEquals(r, Result.Success("上海今天晴"))
  }

  test("multi-tool: one LLM response with two invocations, serial execution") {
    val llm = new ScriptedLLM(
      List(
        LLMResponse.ToolCalls(
          List(
            ToolInvocation("weather", Map("city" -> "Tokyo")),
            ToolInvocation("add", Map("a" -> "2", "b" -> "3"))
          )
        ),
        LLMResponse.Answer("done")
      )
    )
    val r = runAgent(llm, TestTools, "q")
    assertEquals(r, Result.Success("done"))
  }

  test(
    "errorHandling middleware: unknown tool becomes structured error string"
  ) {
    val llm = new ScriptedLLM(
      List(
        LLMResponse.ToolCalls(
          List(ToolInvocation("gold_price", Map("date" -> "today")))
        ),
        LLMResponse.Answer("caught")
      )
    )
    val r = runAgent(
      llm,
      ToolMiddleware.errorHandling(TestTools),
      "黄金价格"
    )
    assertEquals(r, Result.Success("caught"))
  }

  test("without errorHandling: tool failure propagates as Abort") {
    val llm = new ScriptedLLM(
      List(
        LLMResponse.ToolCalls(
          List(ToolInvocation("gold_price", Map.empty))
        )
      )
    )
    val r = runAgent(llm, TestTools, "q")
    assert(r.isFailure, s"expected failure, got $r")
  }

  test("maxSteps exhausted: agent keeps tool-calling, aborts") {
    // LLM 无限循环工具调用——maxSteps 必须踩住
    val infiniteToolCall = List.fill(20)(
      LLMResponse.ToolCalls(
        List(ToolInvocation("add", Map("a" -> "1", "b" -> "1")))
      )
    )
    val llm = new ScriptedLLM(infiniteToolCall)
    val r = runAgent(llm, TestTools, "q", maxSteps = 3)
    assert(r.isFailure, s"expected maxSteps failure, got $r")
  }

  test("errorHandling handles tool invocation that aborts from registry") {
    // 直接验 middleware 本身：调 unknown 工具，应返回 error 字符串。
    // errorHandling 现在用 kyo.Log，必须包 runSilent 提供 ambient Logger，否则会因 Local 默认 logger 仍输出。
    val wrapped = ToolMiddleware.errorHandling(TestTools)
    val out: Result[Throwable, String] =
      Abort
        .run[Throwable](
          IO.Unsafe.run(runSilent(wrapped.call("nonexistent", Map.empty)))
        )
        .eval
    out match {
      case Result.Success(s) =>
        assert(s.startsWith("<tool_error"), s"expected error string, got $s")
        assert(
          s.contains("nonexistent"),
          s"expected tool name in error, got $s"
        )
      case other =>
        fail(s"expected Success with error string, got $other")
    }
  }

  test("compose applies middleware outer-to-inner") {
    // 证明 (a compose b compose c)(reg) == a(b(c(reg)))：内层 prefix 先写，外层 postfix 后写
    val tagInner: ToolMiddleware = next =>
      new ToolRegistry {
        def call(n: String, a: Map[String, String]) =
          next.call(n, a).map(out => s"[inner]$out")
      }
    val tagOuter: ToolMiddleware = next =>
      new ToolRegistry {
        def call(n: String, a: Map[String, String]) =
          next.call(n, a).map(out => s"[outer]$out")
      }
    val wrapped =
      (tagOuter compose tagInner)(TestTools)
    val out =
      Abort
        .run[Throwable](
          IO.Unsafe.run(wrapped.call("add", Map("a" -> "1", "b" -> "2")))
        )
        .eval
    // inner 先处理底层结果，outer 最后拼接：最终格式 [outer][inner]3
    assertEquals(out, Result.Success("[outer][inner]3"))
  }

  // ====================== 第一阶段中间件测试 ======================

  /** 通用辅助：把单次 tool 调用跑到 Result，静默 logger 不吵输出。 */
  private def runToolCall(
      v: String < (IO & Abort[Throwable])
  ): Result[Throwable, String] =
    Abort.run[Throwable](IO.Unsafe.run(runSilent(v))).eval

  /** 通用辅助：把单次 LLM 调用跑到 Result。 */
  private def runLLMCall(
      v: LLMResponse < (IO & Abort[Throwable])
  ): Result[Throwable, LLMResponse] =
    Abort.run[Throwable](IO.Unsafe.run(runSilent(v))).eval

  /** 前 N 次失败、之后成功的 tool——用来验 retry 重试次数。 */
  final class FlakyTools(failBefore: Int) extends ToolRegistry {
    val attempts = new java.util.concurrent.atomic.AtomicInteger(0)
    def call(
        name: String,
        args: Map[String, String]
    ): String < (IO & Abort[Throwable]) = {
      val n = attempts.incrementAndGet()
      if (n <= failBefore) Abort.fail(new RuntimeException(s"flaky fail #$n"))
      else s"ok after $n"
    }
  }

  test("ToolMiddleware.retry: fails 2 then succeeds on 3rd attempt") {
    val flaky = new FlakyTools(failBefore = 2)
    val wrapped = ToolMiddleware.retryWith(maxAttempts = 3)(flaky)
    val r = runToolCall(wrapped.call("anything", Map.empty))
    assertEquals(r, Result.Success("ok after 3"))
    assertEquals(flaky.attempts.get(), 3)
  }

  test("ToolMiddleware.retry: attempts exhausted → Abort propagates") {
    val flaky = new FlakyTools(failBefore = 10) // 永远失败
    val wrapped = ToolMiddleware.retryWith(maxAttempts = 2)(flaky)
    val r = runToolCall(wrapped.call("anything", Map.empty))
    assert(r.isFailure, s"expected failure after retries exhausted, got $r")
    assertEquals(flaky.attempts.get(), 2, "retry should stop at maxAttempts")
  }

  test("ToolMiddleware.callLimit: raises Abort after N calls") {
    val wrapped = ToolMiddleware.callLimit(2)(TestTools)
    val r1 = runToolCall(wrapped.call("add", Map("a" -> "1", "b" -> "2")))
    val r2 = runToolCall(wrapped.call("add", Map("a" -> "3", "b" -> "4")))
    val r3 = runToolCall(wrapped.call("add", Map("a" -> "5", "b" -> "6")))
    assertEquals(r1, Result.Success("3"))
    assertEquals(r2, Result.Success("7"))
    assert(r3.isFailure, s"expected limit exceeded on 3rd call, got $r3")
  }

  test("LLMMiddleware.fallback: primary fails → backup serves") {
    val failingPrimary = new LLMClient {
      def complete(
          history: List[Message]
      ): LLMResponse < (IO & Abort[Throwable]) =
        Abort.fail(new RuntimeException("primary down"))
    }
    val backup = new ScriptedLLM(List(LLMResponse.Answer("from backup")))
    val wrapped = LLMMiddleware.fallback(backup)(failingPrimary)
    val r = runLLMCall(wrapped.complete(List(Message(Role.User, "hi"))))
    assertEquals(r, Result.Success(LLMResponse.Answer("from backup")))
  }

  test("LLMMiddleware.fallback: primary succeeds → backup untouched") {
    var backupCalled = false
    val primary = new ScriptedLLM(List(LLMResponse.Answer("from primary")))
    val backup = new LLMClient {
      def complete(
          history: List[Message]
      ): LLMResponse < (IO & Abort[Throwable]) = {
        backupCalled = true
        LLMResponse.Answer("should not be called")
      }
    }
    val wrapped = LLMMiddleware.fallback(backup)(primary)
    val r = runLLMCall(wrapped.complete(List(Message(Role.User, "hi"))))
    assertEquals(r, Result.Success(LLMResponse.Answer("from primary")))
    assert(!backupCalled, "backup should not be called when primary succeeds")
  }

  test("LLMMiddleware.caching: same history → backend called once") {
    val backendCalls = new java.util.concurrent.atomic.AtomicInteger(0)
    val counting = new LLMClient {
      def complete(
          history: List[Message]
      ): LLMResponse < (IO & Abort[Throwable]) = {
        backendCalls.incrementAndGet()
        LLMResponse.Answer("cached answer")
      }
    }
    val cache =
      new java.util.concurrent.ConcurrentHashMap[List[Message], LLMResponse]()
    val wrapped = LLMMiddleware.cachingWith(cache)(counting)
    val hist = List(Message(Role.User, "same query"))
    val r1 = runLLMCall(wrapped.complete(hist))
    val r2 = runLLMCall(wrapped.complete(hist))
    val r3 = runLLMCall(wrapped.complete(hist))
    assertEquals(r1, Result.Success(LLMResponse.Answer("cached answer")))
    assertEquals(r2, r1)
    assertEquals(r3, r1)
    assertEquals(backendCalls.get(), 1, "backend should be called only once")
  }

  test("LLMMiddleware.caching: different history → each call hits backend") {
    val backendCalls = new java.util.concurrent.atomic.AtomicInteger(0)
    val counting = new LLMClient {
      def complete(
          history: List[Message]
      ): LLMResponse < (IO & Abort[Throwable]) = {
        backendCalls.incrementAndGet()
        LLMResponse.Answer(s"call ${backendCalls.get()}")
      }
    }
    val cache =
      new java.util.concurrent.ConcurrentHashMap[List[Message], LLMResponse]()
    val wrapped = LLMMiddleware.cachingWith(cache)(counting)
    runLLMCall(wrapped.complete(List(Message(Role.User, "q1"))))
    runLLMCall(wrapped.complete(List(Message(Role.User, "q2"))))
    runLLMCall(wrapped.complete(List(Message(Role.User, "q3"))))
    assertEquals(
      backendCalls.get(),
      3,
      "each distinct history triggers backend"
    )
  }

  // ====================== Agent.loop(List[Message], Int) stateful 版本 ======================

  /** 记录所有进入 complete 的 history——用于验证 loop 传给 LLM 的 history 形状。 */
  final class RecordingLLM(responses: List[LLMResponse]) extends LLMClient {
    val seen = scala.collection.mutable.ListBuffer[List[Message]]()
    private val it = responses.iterator
    def complete(
        history: List[Message]
    ): LLMResponse < (IO & Abort[Throwable]) = {
      seen.append(history)
      if (it.hasNext) it.next()
      else LLMResponse.Answer("<exhausted>")
    }
  }

  private def runLoopWithHistory(
      llm: LLMClient,
      registry: ToolRegistry,
      initialHistory: List[Message],
      maxSteps: Int = 6
  ): Result[Throwable, (String, List[Message])] = {
    val handled: (String, List[Message]) < (IO & Abort[Throwable]) =
      runSilent(
        Agent
          .loop(initialHistory, maxSteps)
          .pipe(HistoryRewrite.runIdentity(_))
          .pipe(AgentHalt.runNever(_))
          .pipe(ResponseHook.runIdentity(_))
          .pipe(Tool.run(registry))
          .pipe(LLM.run(llm))
      )
    Abort.run[Throwable](IO.Unsafe.run(handled)).eval
  }

  /** HistoryRewrite 测试专用：把 keepLast(n) 作为改写 handler 跑 loop。 */
  private def runLoopWithKeepLast(
      llm: LLMClient,
      registry: ToolRegistry,
      initialHistory: List[Message],
      n: Int,
      maxSteps: Int = 6
  ): Result[Throwable, (String, List[Message])] = {
    val handled: (String, List[Message]) < (IO & Abort[Throwable]) =
      runSilent(
        Agent
          .loop(initialHistory, maxSteps)
          .pipe(HistoryRewrite.runKeepLast(n)(_))
          .pipe(AgentHalt.runNever(_))
          .pipe(ResponseHook.runIdentity(_))
          .pipe(Tool.run(registry))
          .pipe(LLM.run(llm))
      )
    Abort.run[Throwable](IO.Unsafe.run(handled)).eval
  }

  /** AgentHalt 测试专用：把 guard 作为 halt handler 跑 loop。 */
  private def runLoopWithHalt(
      llm: LLMClient,
      registry: ToolRegistry,
      initialHistory: List[Message],
      guard: => Option[String],
      maxSteps: Int = 6
  ): Result[Throwable, (String, List[Message])] = {
    val handled: (String, List[Message]) < (IO & Abort[Throwable]) =
      runSilent(
        Agent
          .loop(initialHistory, maxSteps)
          .pipe(HistoryRewrite.runIdentity(_))
          .pipe(AgentHalt.runOn(guard)(_))
          .pipe(ResponseHook.runIdentity(_))
          .pipe(Tool.run(registry))
          .pipe(LLM.run(llm))
      )
    Abort.run[Throwable](IO.Unsafe.run(handled)).eval
  }

  /** ResponseHook 测试专用：把 f 作为 response 变换 handler 跑 loop。 */
  private def runLoopWithResponseMap(
      llm: LLMClient,
      registry: ToolRegistry,
      initialHistory: List[Message],
      f: LLMResponse => LLMResponse,
      maxSteps: Int = 6
  ): Result[Throwable, (String, List[Message])] = {
    val handled: (String, List[Message]) < (IO & Abort[Throwable]) =
      runSilent(
        Agent
          .loop(initialHistory, maxSteps)
          .pipe(HistoryRewrite.runIdentity(_))
          .pipe(AgentHalt.runNever(_))
          .pipe(ResponseHook.runMap(f)(_))
          .pipe(Tool.run(registry))
          .pipe(LLM.run(llm))
      )
    Abort.run[Throwable](IO.Unsafe.run(handled)).eval
  }

  test("loop stateful: non-empty initial history continues conversation") {
    val llm = new RecordingLLM(List(LLMResponse.Answer("turn 2 answer")))
    val existing = List(
      Message(Role.User, "turn 1 q"),
      Message(Role.Assistant, "turn 1 answer")
    )
    val input = existing :+ Message(Role.User, "turn 2 q")
    val r = runLoopWithHistory(llm, TestTools, input)
    r match {
      case Result.Success((ans, hist)) =>
        assertEquals(ans, "turn 2 answer")
        // LLM 看到了完整 history：不是裸的 [User(turn 2 q)]
        assertEquals(llm.seen.head, input)
      case other => fail(s"expected Success, got $other")
    }
  }

  test("loop stateful: returned history contains Assistant final answer") {
    val llm = new RecordingLLM(List(LLMResponse.Answer("final")))
    val input = List(Message(Role.User, "hi"))
    val r = runLoopWithHistory(llm, TestTools, input)
    r match {
      case Result.Success((ans, hist)) =>
        assertEquals(ans, "final")
        assertEquals(hist.last, Message(Role.Assistant, "final"))
        assertEquals(hist.size, 2) // User + Assistant
      case other => fail(s"expected Success, got $other")
    }
  }

  test("loop stateful: returned history contains full tool-call trace") {
    val llm = new RecordingLLM(
      List(
        LLMResponse.ToolCalls(
          List(ToolInvocation("weather", Map("city" -> "Tokyo")))
        ),
        LLMResponse.Answer("done")
      )
    )
    val r = runLoopWithHistory(
      llm,
      TestTools,
      List(Message(Role.User, "weather tokyo"))
    )
    r match {
      case Result.Success((ans, hist)) =>
        assertEquals(ans, "done")
        // User + Assistant(CALL) + Tool(result) + Assistant(final) = 4
        assertEquals(hist.size, 4)
        assert(
          hist(1).role == Role.Assistant && hist(1).content.startsWith("CALL")
        )
        assert(hist(2).role == Role.Tool)
        assert(hist(3) == Message(Role.Assistant, "done"))
      case other => fail(s"expected Success, got $other")
    }
  }

  // ====================== Agent.repl 跨 turn 累计 & 异常隔离 ======================

  /** 跑一次 REPL，喂指定输入序列。 输入消费完后的 `readLine` 由 Kyo 抛 IOException， repl 的
    * `Abort.recover[IOException](_ => "exit")` 折成 exit——等效于用户敲 Ctrl-D。
    *
    * 新 API：registry / llm 作为参数传入 Agent.repl，不是 pipe 注入。
    */
  private def runReplWith(
      llm: LLMClient,
      registry: ToolRegistry,
      inputs: Seq[String]
  ): Result[Throwable, Unit] = {
    val handled: Unit < (IO & Abort[Throwable]) =
      runSilent(
        Console.withIn(inputs)(Agent.repl(registry, llm))
      )
    Abort.run[Throwable](IO.Unsafe.run(handled)).eval
  }

  test("repl: cross-turn history accumulation (problem B fix)") {
    // 核心断言：第二个 turn 的 LLM 调用能看到第一个 turn 的 history
    val llm = new RecordingLLM(
      List(
        LLMResponse.Answer("ans 1"),
        LLMResponse.Answer("ans 2")
      )
    )
    val r = runReplWith(llm, TestTools, Seq("q1", "q2", "exit"))
    assert(r.isSuccess, s"repl should terminate cleanly, got $r")
    assertEquals(llm.seen.size, 2)
    assertEquals(llm.seen(0), List(Message(Role.User, "q1")))
    // turn 2 的 LLM 看到：turn 1 的 User + Assistant（终答）+ turn 2 的 User
    assertEquals(
      llm.seen(1),
      List(
        Message(Role.User, "q1"),
        Message(Role.Assistant, "ans 1"),
        Message(Role.User, "q2")
      )
    )
  }

  test(
    "repl: per-turn Abort isolation—failed turn rolls back, session continues"
  ) {
    // 新 API 的核心能力：即便**没有 errorHandling middleware**，raw Abort 也会被 repl 内部的
    // Abort.run 捕获（因为 Tool.run / LLM.run 现在是 per-turn 应用的，Abort.run 作用域覆盖得到）。
    //
    // turn 1 触发未知工具 → Tool.run 里 Abort.fail → Abort.run catch → fallback 到 error 字符串 +
    // 回滚 history → session 继续
    // turn 2 起点 history 为空（turn 1 的 q1 已回滚），LLM 看到干净上下文
    val llm = new RecordingLLM(
      List(
        LLMResponse.ToolCalls(
          List(ToolInvocation("nonexistent", Map.empty))
        ),
        LLMResponse.Answer("ans 2")
      )
    )
    val r = runReplWith(llm, TestTools, Seq("q1", "q2", "exit"))
    assert(r.isSuccess, s"repl should survive per-turn failure, got $r")
    // turn 1 发了 1 次 LLM 调用就挂了；turn 2 发了 1 次 LLM 调用成功
    assertEquals(llm.seen.size, 2)
    assertEquals(llm.seen(0), List(Message(Role.User, "q1")))
    // 关键：turn 2 的起点 history 不含 q1——失败轮已回滚
    assertEquals(llm.seen(1), List(Message(Role.User, "q2")))
  }

  test(
    "repl: tool failure + errorHandling → LLM sees tool_error feedback (alternative pattern)"
  ) {
    // 另一种等价范式：用 errorHandling middleware 在更早的层把 Abort 折成字符串，
    // LLM 看到 `<tool_error>` 作为 Tool 消息反馈，可以基于它决策下一步——
    // 这是 LangChain / OpenAI Tool API 的实战做法。
    //
    // 和上一测试的区别：上面是"硬失败 → 回滚"，这个是"软失败 → LLM 自己处理"。两者在新 API 下都支持。
    val llm = new RecordingLLM(
      List(
        LLMResponse.ToolCalls(
          List(ToolInvocation("nonexistent", Map.empty))
        ),
        LLMResponse.Answer("recovered from tool error"),
        LLMResponse.Answer("ans 2")
      )
    )
    val r = runReplWith(
      llm,
      ToolMiddleware.errorHandling(TestTools),
      Seq("q1", "q2", "exit")
    )
    assert(r.isSuccess, s"repl should succeed with errorHandling, got $r")
    // errorHandling 折成 <tool_error> 字符串后不 propagate，所以 turn 1 会走第 2 轮 LLM 调用综合答案
    assertEquals(llm.seen.size, 3)
    assert(
      llm
        .seen(1)
        .exists(m =>
          m.role == Role.Tool && m.content.startsWith("<tool_error")
        ),
      s"LLM call #2 应看到 <tool_error> 作为 Tool 消息，history=${llm.seen(1)}"
    )
  }

  test("repl: exit terminates on first turn without LLM call") {
    val llm = new RecordingLLM(List.empty)
    val r = runReplWith(llm, TestTools, Seq("exit"))
    assert(r.isSuccess)
    assertEquals(llm.seen.size, 0, "no LLM call when user immediately exits")
  }

  test("repl: empty input line is skipped without calling LLM") {
    val llm = new RecordingLLM(List(LLMResponse.Answer("ok")))
    val r = runReplWith(llm, TestTools, Seq("", "hi", "exit"))
    assert(r.isSuccess)
    // 只有 "hi" 触发了 LLM 调用；"" 被 repl 跳过
    assertEquals(llm.seen.size, 1)
    assertEquals(llm.seen(0), List(Message(Role.User, "hi")))
  }

  test("repl: stdin exhaustion equivalent to exit (Ctrl-D / EOF)") {
    // 不喂 "exit"——inputs 耗尽时 readLine 抛 IOException，被 Abort.recover 折成 "exit"
    val llm = new RecordingLLM(List(LLMResponse.Answer("ok")))
    val r = runReplWith(llm, TestTools, Seq("hello"))
    assert(r.isSuccess, s"EOF should terminate session cleanly, got $r")
    assertEquals(llm.seen.size, 1)
  }

  test("retry must be inside errorHandling to see raw failures") {
    // 证明组合顺序要求：errorHandling 外 / retry 内。反着排 retry 永远看不到 Failure。
    val flaky = new FlakyTools(failBefore = 2)
    val correctOrder =
      (ToolMiddleware.errorHandling compose ToolMiddleware.retryWith(3))(flaky)
    val r = runToolCall(correctOrder.call("anything", Map.empty))
    assertEquals(r, Result.Success("ok after 3"))
    assertEquals(flaky.attempts.get(), 3)

    // 反着排：retry 看到的是 errorHandling 折出来的 `<tool_error>` 字符串（成功分支），不重试
    val flaky2 = new FlakyTools(failBefore = 2)
    val wrongOrder =
      (ToolMiddleware.retryWith(3) compose ToolMiddleware.errorHandling)(flaky2)
    val r2 = runToolCall(wrongOrder.call("anything", Map.empty))
    // errorHandling 把 Abort 折成字符串，retry 以为成功不再重试
    assert(
      r2 match {
        case Result.Success(s) => s.startsWith("<tool_error")
        case _                 => false
      },
      s"wrong order should yield folded error on first fail, got $r2"
    )
    assertEquals(flaky2.attempts.get(), 1, "wrong order: retry never triggers")
  }

  // ====================== HistoryRewrite effect（L4 before_model hook）======================

  test("HistoryRewrite.runIdentity: LLM sees full history unchanged") {
    val llm = new RecordingLLM(List(LLMResponse.Answer("ok")))
    val existing = List(
      Message(Role.User, "q1"),
      Message(Role.Assistant, "a1"),
      Message(Role.User, "q2"),
      Message(Role.Assistant, "a2"),
      Message(Role.User, "q3")
    )
    val r = runLoopWithHistory(llm, TestTools, existing)
    assert(r.isSuccess, s"expected success, got $r")
    // identity handler 下 LLM 看到完整原始 history
    assertEquals(llm.seen.head, existing)
  }

  test("HistoryRewrite.runKeepLast: LLM sees only tail n messages") {
    val llm = new RecordingLLM(List(LLMResponse.Answer("ok")))
    val ten = (1 to 10).map(i => Message(Role.User, s"msg $i")).toList
    val r = runLoopWithKeepLast(llm, TestTools, ten, n = 3)
    assert(r.isSuccess, s"expected success, got $r")
    // LLM 只看到最后 3 条
    assertEquals(llm.seen.head.size, 3)
    assertEquals(
      llm.seen.head,
      List(
        Message(Role.User, "msg 8"),
        Message(Role.User, "msg 9"),
        Message(Role.User, "msg 10")
      )
    )
  }

  test("HistoryRewrite.runKeepLast: n >= history.size acts as identity") {
    val llm = new RecordingLLM(List(LLMResponse.Answer("ok")))
    val three = List(
      Message(Role.User, "a"),
      Message(Role.User, "b"),
      Message(Role.User, "c")
    )
    val r = runLoopWithKeepLast(llm, TestTools, three, n = 100)
    assert(r.isSuccess, s"expected success, got $r")
    assertEquals(llm.seen.head, three, "n > size 不影响 history")
  }

  test(
    "HistoryRewrite.runKeepLast: compaction semantics—returned history uses rewritten base"
  ) {
    // 关键语义：HistoryRewrite 是 compaction 而不是"仅 view"。
    // rewritten 成为 decideNext 的 history 基底，最终返回的 history 也基于它。
    val llm = new RecordingLLM(List(LLMResponse.Answer("final")))
    val ten = (1 to 10).map(i => Message(Role.User, s"msg $i")).toList
    val r = runLoopWithKeepLast(llm, TestTools, ten, n = 2)
    r match {
      case Result.Success((ans, hist)) =>
        assertEquals(ans, "final")
        // 返回的 hist = rewritten(最后 2 条) + Assistant(final) = 3
        assertEquals(hist.size, 3)
        assertEquals(hist.head, Message(Role.User, "msg 9"))
        assertEquals(hist(1), Message(Role.User, "msg 10"))
        assertEquals(hist.last, Message(Role.Assistant, "final"))
      case other => fail(s"expected Success, got $other")
    }
  }

  // ====================== AgentHalt effect（L4 goto='end' hook）======================

  test("AgentHalt.runNever: never halts, identity to existing behavior") {
    val llm = new RecordingLLM(List(LLMResponse.Answer("normal ans")))
    val input = List(Message(Role.User, "q"))
    val r = runLoopWithHistory(llm, TestTools, input)
    r match {
      case Result.Success((ans, hist)) =>
        assertEquals(ans, "normal ans")
        assertEquals(llm.seen.size, 1, "LLM 应被正常调用")
      case other => fail(s"expected Success, got $other")
    }
  }

  test("AgentHalt.runOn: Some(reason) fires first turn → skips LLM entirely") {
    val llm = new RecordingLLM(List.empty)
    val input = List(Message(Role.User, "q"))
    val r = runLoopWithHalt(
      llm,
      TestTools,
      input,
      guard = Some("halted by budget")
    )
    r match {
      case Result.Success((ans, hist)) =>
        assertEquals(ans, "halted by budget")
        assertEquals(hist, input, "halt 分支返回 rewritten 作为终态 history")
        assertEquals(llm.seen.size, 0, "LLM 不应被调用")
      case other => fail(s"expected Success, got $other")
    }
  }

  test("AgentHalt.runOn: guard re-evaluates each check, fires after N turns") {
    // guard 捕获外部计数，第 3 次 check 时返回 Some
    val checks = new java.util.concurrent.atomic.AtomicInteger(0)
    def guard: Option[String] = {
      val n = checks.incrementAndGet()
      if (n >= 3) Some(s"halt at check $n") else None
    }
    val llm = new RecordingLLM(
      List(
        LLMResponse.ToolCalls(
          List(ToolInvocation("add", Map("a" -> "1", "b" -> "1")))
        ),
        LLMResponse.ToolCalls(
          List(ToolInvocation("add", Map("a" -> "2", "b" -> "2")))
        ),
        LLMResponse.Answer("never reached")
      )
    )
    val input = List(Message(Role.User, "q"))
    val r = runLoopWithHalt(llm, TestTools, input, guard = guard)
    r match {
      case Result.Success((ans, hist)) =>
        // check 1: None → LLM 1，tool call
        // check 2: None → LLM 2，tool call
        // check 3: Some("halt at check 3") → 跳过 LLM，终止
        assertEquals(ans, "halt at check 3")
        assertEquals(llm.seen.size, 2, "只有前两轮 LLM 被调用")
        assertEquals(checks.get(), 3, "guard 被求值 3 次")
      case other => fail(s"expected Success, got $other")
    }
  }

  test(
    "AgentHalt vs maxSteps: halt is soft termination, maxSteps is hard Abort"
  ) {
    // halt 永远返回 Some → 首轮就终止，即便 maxSteps 很大也不 Abort
    val llm = new RecordingLLM(List.empty)
    val r = runLoopWithHalt(
      llm,
      TestTools,
      List(Message(Role.User, "q")),
      guard = Some("done"),
      maxSteps = 100
    )
    assert(r.isSuccess, "halt 是正常终止，不应该 Abort")
    assertEquals(r, Result.Success(("done", List(Message(Role.User, "q")))))
  }

  test(
    "AgentHalt + HistoryRewrite: both fire, halt branch uses rewritten history"
  ) {
    // 同时 wire keepLast(2) + runOn(Some("halted"))——验证 halt 分支返回 hist 是 rewritten
    val llm = new RecordingLLM(List.empty)
    val ten = (1 to 10).map(i => Message(Role.User, s"m$i")).toList
    val handled: (String, List[Message]) < (IO & Abort[Throwable]) =
      runSilent(
        Agent
          .loop(ten, 6)
          .pipe(HistoryRewrite.runKeepLast(2)(_))
          .pipe(AgentHalt.runOn(Some("halted"))(_))
          .pipe(ResponseHook.runIdentity(_))
          .pipe(Tool.run(TestTools))
          .pipe(LLM.run(llm))
      )
    val r = Abort.run[Throwable](IO.Unsafe.run(handled)).eval
    r match {
      case Result.Success((ans, hist)) =>
        assertEquals(ans, "halted")
        // 关键：halt 分支返回的 hist 是 rewritten（keepLast(2) → 2 条），不是原始 10 条
        assertEquals(hist.size, 2)
        assertEquals(hist.map(_.content), List("m9", "m10"))
        assertEquals(llm.seen.size, 0, "halt 分支跳过 LLM")
      case other => fail(s"expected Success, got $other")
    }
  }

  // ====================== ResponseHook effect（L4 after_model hook）======================

  test("ResponseHook.runIdentity: LLM response passes through unchanged") {
    // 覆盖 Phase 3 的 identity path——通过 runLoopWithHistory 隐式验证（它 wire 了 runIdentity）
    val llm = new RecordingLLM(List(LLMResponse.Answer("raw")))
    val r = runLoopWithHistory(llm, TestTools, List(Message(Role.User, "q")))
    r match {
      case Result.Success((ans, _)) => assertEquals(ans, "raw")
      case other                    => fail(s"expected Success, got $other")
    }
  }

  test("ResponseHook.runMap: transform Answer text") {
    // 把 Answer 的内容全部大写——verify f 作用到了 raw response
    val llm = new RecordingLLM(List(LLMResponse.Answer("hello")))
    val r = runLoopWithResponseMap(
      llm,
      TestTools,
      List(Message(Role.User, "q")),
      f = {
        case LLMResponse.Answer(t) => LLMResponse.Answer(t.toUpperCase)
        case other                 => other
      }
    )
    assertEquals(r, Result.Success(("HELLO", List(
      Message(Role.User, "q"),
      Message(Role.Assistant, "HELLO")
    ))))
  }

  test(
    "ResponseHook.runMap: can convert Answer to ToolCalls (force tool use)"
  ) {
    // 展示 L4 after_model 的最大威力：改变控制流。
    // LLM 说"done"，handler 把它改写成 tool call，agent 继续执行工具。
    val llm = new RecordingLLM(
      List(
        LLMResponse.Answer("done"), // raw #1：LLM 想结束
        LLMResponse.Answer("really done") // raw #2：tool 轮后再回 LLM 的回答
      )
    )
    val forceAdd: LLMResponse => LLMResponse = {
      // 第一次 Answer 改写成强制 tool call；之后的 Answer 保留
      var firstAnswerSeen = false
      r =>
        r match {
          case LLMResponse.Answer(_) if !firstAnswerSeen =>
            firstAnswerSeen = true
            LLMResponse.ToolCalls(
              List(ToolInvocation("add", Map("a" -> "1", "b" -> "2")))
            )
          case other => other
        }
    }
    val r = runLoopWithResponseMap(
      llm,
      TestTools,
      List(Message(Role.User, "q")),
      f = forceAdd
    )
    r match {
      case Result.Success((ans, hist)) =>
        // Answer 被改写成 tool call，所以第一轮做了工具调用；第二轮 LLM 才真正返回 "really done"
        assertEquals(ans, "really done")
        assertEquals(llm.seen.size, 2, "LLM 被调用两次——handler 改变了控制流")
      case other => fail(s"expected Success, got $other")
    }
  }

  test("ResponseHook.runMap: filter invalid tool names") {
    // handler 过滤掉 LLM 幻觉出的不存在工具——只保留白名单
    val allowList = Set("weather", "add")
    val filterTools: LLMResponse => LLMResponse = {
      case LLMResponse.ToolCalls(invs) =>
        val valid = invs.filter(i => allowList.contains(i.name))
        if (valid.isEmpty) LLMResponse.Answer("no valid tools requested")
        else LLMResponse.ToolCalls(valid)
      case other => other
    }
    val llm = new RecordingLLM(
      List(
        LLMResponse.ToolCalls(
          List(
            ToolInvocation("hallucinated", Map.empty),
            ToolInvocation("also_fake", Map.empty)
          )
        )
      )
    )
    val r = runLoopWithResponseMap(
      llm,
      TestTools,
      List(Message(Role.User, "q")),
      f = filterTools
    )
    r match {
      case Result.Success((ans, _)) =>
        assertEquals(ans, "no valid tools requested")
        assertEquals(llm.seen.size, 1, "filter 后没 tool 调用，不需要回 LLM 第二轮")
      case other => fail(s"expected Success, got $other")
    }
  }

  test(
    "HistoryRewrite applied every turn: tool call branch also uses rewritten"
  ) {
    // 两轮 LLM 调用都经过 rewrite——不只 first turn。
    val llm = new RecordingLLM(
      List(
        LLMResponse.ToolCalls(
          List(ToolInvocation("weather", Map("city" -> "Tokyo")))
        ),
        LLMResponse.Answer("done")
      )
    )
    val five = (1 to 5).map(i => Message(Role.User, s"m$i")).toList
    val r = runLoopWithKeepLast(llm, TestTools, five, n = 2)
    assert(r.isSuccess, s"expected success, got $r")
    // 第 1 次 LLM 调用：rewrite 后 2 条
    assertEquals(llm.seen(0).size, 2)
    assertEquals(llm.seen(0).map(_.content), List("m4", "m5"))
    // 第 2 次 LLM 调用：append tool 轨迹 (Assistant CALL + Tool result) 后再 rewrite 取 last 2
    // = 最后两条（Assistant CALL + Tool result）
    assertEquals(llm.seen(1).size, 2)
    assertEquals(llm.seen(1)(0).role, Role.Assistant)
    assert(llm.seen(1)(0).content.startsWith("CALL weather"))
    assertEquals(llm.seen(1)(1).role, Role.Tool)
  }
}
