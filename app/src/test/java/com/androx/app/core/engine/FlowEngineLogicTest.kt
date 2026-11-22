package com.androx.app.core.engine

import com.androx.app.core.Check
import com.androx.app.core.CheckIds
import com.androx.app.core.CompareOp
import com.androx.app.core.ConstraintGroup
import com.androx.app.core.Flow
import com.androx.app.core.Op
import com.androx.app.core.OpIds
import com.androx.app.core.RunOptions
import com.androx.app.core.Step
import com.androx.app.core.StepOptions
import com.androx.app.core.Value
import com.androx.app.core.params
import com.androx.app.core.state.CompositeStateReader
import com.androx.app.core.state.DeviceStateReader
import com.androx.app.core.state.FixedStateReader
import com.androx.app.core.state.LegacyStateReader
import com.androx.app.data.JsonFile
import com.androx.app.engine.ActionException
import com.androx.app.engine.ScriptHost
import com.androx.app.engine.ScriptResult
import com.androx.app.engine.ScriptRunner
import com.androx.app.model.ExecutionRecord
import com.androx.app.model.ExecutionStatus
import com.androx.app.model.RunSource
import com.androx.app.model.VariableMode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

class FlowEngineLogicTest {
    private val utc = ZoneId.of("UTC")
    private val noScripts = object : ScriptRunner {
        override fun run(source: String, host: ScriptHost): ScriptResult = ScriptResult.Success
    }

    private class Control : FlowControl {
        val calls = mutableListOf<Pair<String, Boolean>>()
        var known: Set<String> = setOf("other")
        override suspend fun setEnabled(flowId: String, enabled: Boolean): Boolean {
            if (flowId !in known) return false
            calls += flowId to enabled
            return true
        }
    }

    private class Info(val enabled: Map<String, Boolean> = emptyMap()) : FlowInfo {
        override fun isRunning(flowId: String) = false
        override fun lastRunAt(flowId: String): Long? = null
        override fun runsSince(flowId: String, sinceMillis: Long) = 0
        override fun isEnabled(flowId: String) = enabled[flowId]
    }

    private class Rig(
        val device: EngineFakeDevice = EngineFakeDevice(),
        val vars: MapValueStore = MapValueStore(),
        flows: List<Flow> = emptyList(),
        clock: () -> Long = { ZonedDateTime.of(2026, 11, 21, 17, 5, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli() },
        extra: Map<String, ActionExecutor> = emptyMap(),
        reader: DeviceStateReader? = null,
        info: FlowInfo = NoFlowInfo,
        control: FlowControl? = null,
        random: Random = Random(1),
        scripts: ScriptRunner,
    ) {
        val engine = FlowEngine(
            device = device, variables = vars, findFlow = { id -> flows.firstOrNull { it.id == id } }, scripts = scripts,
            clock = clock, zone = { ZoneId.of("UTC") }, extraActions = extra,
            state = reader ?: LegacyStateReader(device), flowInfo = info, flowControl = control, random = random,
        )
    }

    private fun rig(
        device: EngineFakeDevice = EngineFakeDevice(),
        vars: MapValueStore = MapValueStore(),
        flows: List<Flow> = emptyList(),
        extra: Map<String, ActionExecutor> = emptyMap(),
        reader: DeviceStateReader? = null,
        info: FlowInfo = NoFlowInfo,
        control: FlowControl? = null,
        random: Random = Random(1),
    ) = Rig(device, vars, flows, extra = extra, reader = reader, info = info, control = control, random = random, scripts = noScripts)

    private fun step(id: String, typeId: String, p: JsonObject = params(), options: StepOptions = StepOptions(), blocks: Map<String, List<Step>> = emptyMap()) =
        Step(id, Op(typeId, p), options, blocks)

    private fun toast(id: String, text: String) = step(id, OpIds.TOAST, params("message" to text))
    private fun flow(vararg steps: Step, id: String = "f1", run: RunOptions = RunOptions()) =
        Flow(id = id, name = "Logic flow", steps = steps.toList(), runOptions = run)

    private fun group(typeId: String, p: JsonObject) = ConstraintGroup(rules = listOf(Check(typeId, p)))
    private fun logicParam(g: ConstraintGroup) = JsonFile.json.encodeToJsonElement(ConstraintGroup.serializer(), g)
    private fun compare(left: String, op: CompareOp, right: String) = group(CheckIds.COMPARE, params("left" to left, "op" to op, "right" to right))

    private fun ifStep(id: String, cond: ConstraintGroup, then: List<Step>, otherwise: List<Step> = emptyList()) =
        step(id, OpIds.IF, params("condition" to logicParam(cond)), blocks = mapOf(OpIds.BLOCK_THEN to then, OpIds.BLOCK_ELSE to otherwise))

    private fun forEach(id: String, source: String, body: List<Step>, itemName: String = "item") =
        step(id, OpIds.FOR_EACH, params("source" to source, "itemName" to itemName), blocks = mapOf(OpIds.BLOCK_BODY to body))

    private suspend fun run(r: Rig, f: Flow, source: RunSource = RunSource.MANUAL): ExecutionRecord = r.engine.execute(f, source)

    // ── For each ────────────────────────────────────────────────────────────────────────────

    @Test
    fun forEachOverLinesJsonAndDictionaries() = runTest {
        val a = rig()
        run(a, flow(forEach("s0", "a\nb\n\nc", listOf(toast("b0", "{{item}}:{{index}}")))))
        assertEquals(listOf("toast:a:1", "toast:b:2", "toast:c:3"), a.device.calls)

        val b = rig()
        run(b, flow(forEach("s0", """["x","y"]""", listOf(toast("b0", "{{item}}")))))
        assertEquals(listOf("toast:x", "toast:y"), b.device.calls)

        val c = rig()
        run(c, flow(forEach("s0", """{"k1":1,"k2":"v"}""", listOf(toast("b0", "{{key}}={{item}}")))))
        assertEquals(listOf("toast:k1=1", "toast:k2=v"), c.device.calls)
    }

    @Test
    fun forEachReadsAListVariableAndUsesACustomItemName() = runTest {
        val r = rig()
        r.vars.set("names", Value.Items(listOf(Value.Text("Ada"), Value.Text("Bo"))))
        run(r, flow(forEach("s0", "{{names}}", listOf(toast("b0", "hi {{person}}")), itemName = "person")))
        assertEquals(listOf("toast:hi Ada", "toast:hi Bo"), r.device.calls)
    }

    @Test
    fun breakAndContinueWorkInsideIfInsideALoop() = runTest {
        val r = rig()
        val body = listOf(
            ifStep("i1", compare("{{item}}", CompareOp.EQ, "2"), listOf(step("c", OpIds.CONTINUE))),
            ifStep("i2", compare("{{item}}", CompareOp.EQ, "4"), listOf(step("b", OpIds.BREAK))),
            toast("t", "{{item}}"),
        )
        val rec = run(r, flow(forEach("s0", "1\n2\n3\n4\n5", body)))
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertEquals(listOf("toast:1", "toast:3"), r.device.calls)
    }

    @Test
    fun breakOutsideALoopIsAReadableError() = runTest {
        val rec = run(rig(), flow(step("s0", OpIds.BREAK)))
        assertEquals(ExecutionStatus.FAILED, rec.status)
        assertTrue(rec.message!!.contains("inside a loop"))
    }

    @Test
    fun repeatCanBeLeftEarly() = runTest {
        val r = rig()
        val body = listOf(toast("t", "r{{index}}"), ifStep("i", compare("{{index}}", CompareOp.EQ, "2"), listOf(step("b", OpIds.BREAK))))
        run(r, flow(step("s0", OpIds.REPEAT, params("times" to 5), blocks = mapOf(OpIds.BLOCK_BODY to body))))
        assertEquals(listOf("toast:r1", "toast:r2"), r.device.calls)
    }

    // ── While ───────────────────────────────────────────────────────────────────────────────

    private fun setVar(id: String, name: String, mode: VariableMode, value: String) =
        step(id, OpIds.SET_VARIABLE, params("name" to name, "mode" to mode, "value" to value))

    @Test
    fun whileRunsUntilTheConditionStopsHolding() = runTest {
        val r = rig()
        r.vars.set("n", Value.Text("0"))
        val loop = step(
            "w", OpIds.WHILE, params("condition" to logicParam(compare("{{n}}", CompareOp.LT, "3")), "max" to 50),
            blocks = mapOf(OpIds.BLOCK_BODY to listOf(setVar("a", "n", VariableMode.ADD, "1"), toast("t", "n={{n}}"))),
        )
        val rec = run(r, flow(loop))
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertEquals(listOf("toast:n=1", "toast:n=2", "toast:n=3"), r.device.calls)
        assertEquals("3", r.vars.get("n")!!.render())
    }

    @Test
    fun whileAlwaysStopsAtItsMaximum() = runTest {
        val loop = step(
            "w", OpIds.WHILE, params("condition" to logicParam(compare("1", CompareOp.EQ, "1")), "max" to 5),
            blocks = mapOf(OpIds.BLOCK_BODY to listOf(step("l", OpIds.WRITE_LOG, params("message" to "tick")))),
        )
        val rec = run(rig(), flow(loop))
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertEquals(5, rec.logs.count { it.trim() == "tick" })
        assertTrue(rec.logs.any { it.contains("maximum of 5 rounds") })
    }

    // ── Stop Flow and returning values ──────────────────────────────────────────────────────

    @Test
    fun stopEndsTheFlowNormally() = runTest {
        val r = rig()
        val rec = run(r, flow(toast("a", "before"), step("s", OpIds.STOP, params("outcome" to "success")), toast("b", "after")))
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertEquals(listOf("toast:before"), r.device.calls)
    }

    @Test
    fun stopCanFailOrSkip() = runTest {
        val failed = run(rig(), flow(step("s", OpIds.STOP, params("outcome" to "failed", "message" to "no good"))))
        assertEquals(ExecutionStatus.FAILED, failed.status)
        assertEquals("no good", failed.message)
        val skipped = run(rig(), flow(step("s", OpIds.STOP, params("outcome" to "skipped", "message" to "not today"))))
        assertEquals(ExecutionStatus.SKIPPED, skipped.status)
        assertEquals("not today", skipped.message)
    }

    @Test
    fun stopIsNeverRetriedOrTreatedAsAnError() = runTest {
        val rec = run(rig(), flow(step("s", OpIds.STOP, options = StepOptions(retries = 3, continueOnError = true)), toast("b", "after")))
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertFalse(rec.logs.any { it.contains("trying again") || it.contains("carrying on") })
    }

    @Test
    fun aCalledFlowReturnsAValueAndGetsInputs() = runTest {
        val child = flow(
            toast("c0", "hi {{who}}"),
            step("c1", OpIds.STOP, params("outcome" to "success", "result" to "{{who}}!")),
            id = "child",
        )
        val parent = flow(
            step("s0", OpIds.RUN_FLOW, params("flowId" to "child", "flowName" to "Child", "inputs" to "who=Ada\nignored\nn = 2"), StepOptions(saveResultTo = "answer")),
            toast("s1", "got {{answer}}"),
            id = "parent",
        )
        val r = rig(flows = listOf(child, parent))
        val rec = run(r, parent)
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertEquals(listOf("toast:hi Ada", "toast:got Ada!"), r.device.calls)
    }

    @Test
    fun aCalledFlowThatFailsFailsTheCaller() = runTest {
        val child = flow(step("c", OpIds.STOP, params("outcome" to "failed", "message" to "child broke")), id = "child")
        val parent = flow(step("s0", OpIds.RUN_FLOW, params("flowId" to "child")), id = "parent")
        val rec = run(rig(flows = listOf(child, parent)), parent)
        assertEquals(ExecutionStatus.FAILED, rec.status)
        assertEquals("child broke", rec.message)
    }

    @Test
    fun breakInsideACalledFlowCantLeaveTheCallersLoop() = runTest {
        val child = flow(step("c", OpIds.BREAK), id = "child")
        val parent = flow(forEach("s0", "1\n2", listOf(step("r", OpIds.RUN_FLOW, params("flowId" to "child")))), id = "parent")
        val rec = run(rig(flows = listOf(child, parent)), parent)
        assertEquals(ExecutionStatus.FAILED, rec.status)
        assertTrue(rec.message!!.contains("inside a loop"))
    }

    // ── Try / on error ──────────────────────────────────────────────────────────────────────

    private val boom = ActionExecutor { _, _ -> throw ActionException("kaput") }

    private fun tryStep(id: String, body: List<Step>, rescue: List<Step>) =
        step(id, OpIds.TRY, blocks = mapOf(OpIds.BLOCK_TRY to body, OpIds.BLOCK_CATCH to rescue))

    @Test
    fun tryRunsTheCatchBlockWithTheErrorMessage() = runTest {
        val r = rig(extra = mapOf("boom" to boom))
        val rec = run(r, flow(tryStep("t", listOf(step("x", "boom")), listOf(toast("c", "fixed: {{error.message}}"))), toast("after", "carried on")))
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertEquals(listOf("toast:fixed: kaput", "toast:carried on"), r.device.calls)
    }

    @Test
    fun tryDoesNothingExtraWhenNothingFails() = runTest {
        val r = rig()
        run(r, flow(tryStep("t", listOf(toast("a", "ok")), listOf(toast("c", "rescue")))))
        assertEquals(listOf("toast:ok"), r.device.calls)
    }

    @Test
    fun aFailureInTheCatchBlockStillFailsTheFlow() = runTest {
        val rec = run(rig(extra = mapOf("boom" to boom)), flow(tryStep("t", listOf(step("x", "boom")), listOf(step("y", "boom")))))
        assertEquals(ExecutionStatus.FAILED, rec.status)
    }

    @Test
    fun tryLetsLoopExitsThrough() = runTest {
        val r = rig()
        val body = listOf(
            tryStep("t", listOf(ifStep("i", compare("{{item}}", CompareOp.EQ, "2"), listOf(step("b", OpIds.BREAK)))), listOf(toast("c", "rescued"))),
            toast("x", "{{item}}"),
        )
        run(r, flow(forEach("s0", "1\n2\n3", body)))
        assertEquals(listOf("toast:1"), r.device.calls)
    }

    // ── Wait until ──────────────────────────────────────────────────────────────────────────

    private class FlipsAfter(private val flips: Int) : DeviceStateReader {
        var reads = 0
        override fun read(id: String): Value? = if (id == "device.headphones") Value.Bool(++reads > flips) else null
    }

    private fun waitStep(onTimeout: String = "fail", timeout: Int = 10) = step(
        "w", OpIds.WAIT_UNTIL,
        params(
            "condition" to logicParam(group("state:device.headphones", params("is" to true))),
            "timeoutSeconds" to timeout, "pollSeconds" to 1, "onTimeout" to onTimeout,
        ),
    )

    private fun TestScope.virtualSeconds() = testScheduler.currentTime / 1000

    @Test
    fun waitUntilReturnsAsSoonAsTheConditionHolds() = runTest {
        val reader = FlipsAfter(flips = 2)
        val rec = run(rig(reader = reader), flow(waitStep(), run = RunOptions(timeoutSeconds = 0)))
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertEquals(2L, virtualSeconds())
    }

    @Test
    fun waitUntilGivesUpAccordingToTheSetting() = runTest {
        val never = FixedStateReader(mapOf("device.headphones" to Value.Bool(false)))
        val failed = run(rig(reader = never), flow(waitStep(), run = RunOptions(timeoutSeconds = 0)))
        assertEquals(ExecutionStatus.FAILED, failed.status)
        assertTrue(failed.message!!.startsWith("Gave up waiting after 10 s"))
        assertEquals(10L, virtualSeconds())

        val carriedOn = rig(reader = never)
        val rec = run(carriedOn, flow(waitStep(onTimeout = "continue", timeout = 3), toast("t", "went on"), run = RunOptions(timeoutSeconds = 0)))
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertEquals(listOf("toast:went on"), carriedOn.device.calls)
    }

    @Test
    fun waitUntilInASimulationDescribesInsteadOfWaiting() = runTest {
        val never = FixedStateReader(mapOf("device.headphones" to Value.Bool(false)))
        val rec = rig(reader = never).engine.simulate(flow(waitStep()))
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertTrue(rec.logs.any { it.contains("would wait up to 10 s") })
        assertEquals(0L, testScheduler.currentTime)
    }

    // ── data actions ────────────────────────────────────────────────────────────────────────

    /** Runs one step, saves its result, and returns what a following toast showed (or the failure message). */
    private suspend fun result(r: Rig, s: Step): String {
        val rec = r.engine.execute(flow(s.copy(options = StepOptions(saveResultTo = "r")), toast("show", "{{r}}")), RunSource.MANUAL)
        return if (rec.status == ExecutionStatus.FAILED) "FAILED: ${rec.message}" else r.device.calls.last().removePrefix("toast:")
    }

    @Test
    fun calculateUsesNamesAndExplainsMistakes() = runTest {
        val r = rig(device = EngineFakeDevice(battery = 42))
        assertEquals("94", result(r, step("s", OpIds.CALCULATE, params("expression" to "(battery + 5) * 2"))))
        r.vars.set("n", Value.Text("7"))
        assertEquals("21", result(r, step("s", OpIds.CALCULATE, params("expression" to "n * 3"))))
        assertEquals("8", result(r, step("s", OpIds.CALCULATE, params("expression" to "2 ^ {{n}} - 120"))))
        assertTrue(result(r, step("s", OpIds.CALCULATE, params("expression" to "1 / 0"))).contains("divide by zero"))
        assertTrue(result(r, step("s", OpIds.CALCULATE, params("expression" to "nope + 1"))).contains("nope"))
    }

    private suspend fun text(op: String, input: String, a: String = "", b: String = ""): String =
        result(rig(), step("s", OpIds.TEXT, params("operation" to op, "input" to input, "a" to a, "b" to b)))

    @Test
    fun textTools() = runTest {
        assertEquals("ABC", text("upper", "abc"))
        assertEquals("abc", text("lower", "ABC"))
        assertEquals("Hello World", text("title", "hello world"))
        assertEquals("hi", text("trim", "  hi  "))
        assertEquals("ell", text("substring", "hello", "1", "3"))
        assertEquals("a+b", text("replace", "a-b", "-", "+"))
        assertEquals("x#y#", text("regex_replace", "x12y3", "[0-9]+", "#"))
        assertEquals("""["12","34"]""", text("regex_find", "ab12cd34", "[0-9]+"))
        assertEquals("""["a","b"]""", text("split", "a,b", ","))
        assertEquals("a-b", text("join", """["a","b"]""", "-"))
        assertEquals("007", text("pad", "7", "3", "0"))
        assertEquals("5", text("length", "hello"))
        assertEquals("true", text("contains", "Hello", "ELL"))
        assertEquals("true", text("matches", "abc123", "[0-9]+"))
    }

    @Test
    fun textToolErrorsAreReadable() = runTest {
        assertTrue(text("regex_find", "x", "(").startsWith("FAILED: That pattern isn't valid"))
        assertTrue(text("substring", "hello", "one").contains("whole number"))
    }

    @Test
    fun readJsonPicksValuesAndExplainsFailures() = runTest {
        val doc = """{"user":{"items":[{"name":"x"},{"name":"y"}]}}"""
        val r = rig()
        assertEquals("y", result(r, step("s", OpIds.JSON, params("json" to doc, "path" to "user.items.1.name"))))
        assertEquals("""[{"name":"x"},{"name":"y"}]""", result(r, step("s", OpIds.JSON, params("json" to doc, "path" to "user.items"))))
        assertTrue(result(r, step("s", OpIds.JSON, params("json" to doc, "path" to "user.nope"))).contains("wasn't found"))
        assertTrue(result(r, step("s", OpIds.JSON, params("json" to "{oops"))).contains("isn't valid JSON"))
    }

    @Test
    fun formatDateUsesTheEnginesClock() = runTest {
        val r = rig()
        assertEquals("2026-11-21 17:05", result(r, step("s", OpIds.DATE_FORMAT, params("from" to "now", "pattern" to "yyyy-MM-dd HH:mm"))))
        assertEquals("21/11/2026", result(r, step("s", OpIds.DATE_FORMAT, params("pattern" to "dd/MM/yyyy"))))
        assertEquals("1970-01-01", result(r, step("s", OpIds.DATE_FORMAT, params("from" to "timestamp", "timestamp" to "0", "pattern" to "yyyy-MM-dd"))))
        assertTrue(result(r, step("s", OpIds.DATE_FORMAT, params("pattern" to "{"))).contains("isn't valid"))
        assertTrue(result(r, step("s", OpIds.DATE_FORMAT, params("from" to "timestamp", "timestamp" to "abc"))).contains("milliseconds"))
    }

    @Test
    fun randomIsInRangeAndRepeatableWithTheSameSeed() = runTest {
        fun roll(seed: Int) = result(rig(random = Random(seed)), step("s", OpIds.RANDOM, params("mode" to "number", "min" to 1, "max" to 6)))
        val first = roll(7)
        assertTrue(first.toInt() in 1..6)
        assertEquals(first, roll(7))
        val pick = result(rig(random = Random(3)), step("s", OpIds.RANDOM, params("mode" to "item", "list" to "a\nb\nc")))
        assertTrue(pick in setOf("a", "b", "c"))
        assertTrue(result(rig(), step("s", OpIds.RANDOM, params("mode" to "number", "min" to 5, "max" to 1))).startsWith("FAILED"))
        assertTrue(result(rig(), step("s", OpIds.RANDOM, params("mode" to "item", "list" to ""))).contains("empty"))
    }

    // ── lists and dictionaries ──────────────────────────────────────────────────────────────

    private fun list(op: String, value: String = "", index: Int = 1) =
        step("l$op$value", OpIds.LIST_OP, params("variable" to "fruits", "operation" to op, "value" to value, "index" to index))

    @Test
    fun listOperations() = runTest {
        val r = rig()
        run(r, flow(list("add", "banana"), list("add", "apple"), list("sort")))
        assertEquals("""["apple","banana"]""", r.vars.get("fruits")!!.render())
        assertEquals("apple", result(r, list("get", index = 1)))
        assertEquals("2", result(r, list("length")))
        assertEquals("apple,banana", result(r, list("join", ",")))
        assertEquals("true", result(r, list("contains", "BANANA")))
        run(r, flow(list("remove_at", index = 1)))
        assertEquals("""["banana"]""", r.vars.get("fruits")!!.render())
        run(r, flow(list("remove_value", "banana")))
        assertEquals("[]", r.vars.get("fruits")!!.render())
    }

    @Test
    fun listsSortNumbersAsNumbersAndKeepOrderOtherwise() = runTest {
        val r = rig()
        run(r, flow(list("add", "10"), list("add", "9"), list("add", "100"), list("sort")))
        assertEquals("[9,10,100]", r.vars.get("fruits")!!.render())
        run(r, flow(list("reverse")))
        assertEquals("[100,10,9]", r.vars.get("fruits")!!.render())
        run(r, flow(list("clear")))
        assertEquals("[]", r.vars.get("fruits")!!.render())
    }

    @Test
    fun listErrorsAreReadable() = runTest {
        val r = rig()
        run(r, flow(list("add", "x")))
        assertTrue(result(r, list("get", index = 5)).contains("no item 5"))
        r.vars.set("fruits", Value.Text("just text"))
        assertTrue(result(r, list("length")).contains("not a list"))
    }

    @Test
    fun aListSavedAsTextByOlderVersionsStillLoads() = runTest {
        val r = rig()
        r.vars.set("fruits", Value.Text("""["a","b"]"""))
        assertEquals("2", result(r, list("length")))
    }

    private fun dict(op: String, key: String = "", value: String = "") =
        step("d$op$key", OpIds.DICT_OP, params("variable" to "person", "operation" to op, "key" to key, "value" to value))

    @Test
    fun dictionaryOperations() = runTest {
        val r = rig()
        run(r, flow(dict("set", "name", "Ada"), dict("set", "age", "36")))
        assertEquals("""{"name":"Ada","age":36}""", r.vars.get("person")!!.render())
        assertEquals("Ada", result(r, dict("get", "name")))
        assertEquals("""["name","age"]""", result(r, dict("keys")))
        assertEquals("true", result(r, dict("has", "age")))
        assertEquals("false", result(r, dict("has", "zip")))
        assertEquals("2", result(r, dict("length")))
        assertTrue(result(r, dict("get", "zip")).contains("no key"))
        run(r, flow(dict("remove", "age")))
        assertEquals("""{"name":"Ada"}""", r.vars.get("person")!!.render())
        run(r, flow(dict("clear")))
        assertEquals("{}", r.vars.get("person")!!.render())
    }

    @Test
    fun aDictionaryIsReachableByPathInTemplates() = runTest {
        val r = rig()
        run(r, flow(dict("set", "name", "Ada")))
        run(r, flow(toast("t", "hello {{person.name}}")))
        assertEquals("toast:hello Ada", r.device.calls.last())
    }

    // ── typed local values ──────────────────────────────────────────────────────────────────

    @Test
    fun localValuesAreTypedAndNeverSaved() = runTest {
        val r = rig()
        val f = flow(
            step("a", OpIds.SET_LOCAL, params("name" to "x", "valueType" to "number", "value" to "5")),
            step("b", OpIds.SET_LOCAL, params("name" to "ok", "valueType" to "boolean", "value" to "yes")),
            step("c", OpIds.SET_LOCAL, params("name" to "obj", "valueType" to "json", "value" to """{"a":[1,2]}""")),
            step("d", OpIds.CALCULATE, params("expression" to "x * 2"), StepOptions(saveResultTo = "double")),
            toast("t", "{{double}} {{ok}} {{obj.a.1}}"),
        )
        assertEquals(ExecutionStatus.SUCCESS, run(r, f).status)
        assertEquals("toast:10 true 2", r.device.calls.last())
        assertNull(r.vars.get("x"))
        val bad = run(rig(), flow(step("a", OpIds.SET_LOCAL, params("name" to "x", "valueType" to "number", "value" to "abc"))))
        assertEquals(ExecutionStatus.FAILED, bad.status)
    }

    // ── turning Flows on and off ────────────────────────────────────────────────────────────

    private fun enable(action: String, id: String = "other") = step("e", OpIds.FLOW_ENABLE, params("flowId" to id, "flowName" to "Other", "action" to action))

    @Test
    fun aFlowCanSwitchAnotherOnOffOrOver() = runTest {
        val control = Control()
        val r = rig(control = control, info = Info(mapOf("other" to true)))
        run(r, flow(enable("enable"), enable("disable"), enable("toggle")))
        assertEquals(listOf("other" to true, "other" to false, "other" to false), control.calls)
    }

    @Test
    fun switchingAFlowFailsClearlyWhenItCant() = runTest {
        val missing = run(rig(control = Control(), info = Info()), flow(enable("enable", id = "gone")))
        assertEquals("Flow \"Other\" no longer exists.", missing.message)
        val unavailable = run(rig(), flow(enable("enable")))
        assertTrue(unavailable.message!!.contains("isn't available here"))
    }

    @Test
    fun simulationNeverSwitchesAnything() = runTest {
        val control = Control()
        val rec = rig(control = control).engine.simulate(flow(enable("disable")))
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertTrue(control.calls.isEmpty())
        assertTrue(rec.logs.any { it.contains("would: Turn a Flow on or off") })
    }

    // ── device state in templates, and the run log ──────────────────────────────────────────

    @Test
    fun deviceStateIsAvailableInTemplatesAndVariablesWin() = runTest {
        val reader = CompositeStateReader(FixedStateReader(mapOf("battery.temp" to Value.Num(41.0), "network.type" to Value.Text("wifi"))), LegacyStateReader(EngineFakeDevice()))
        val r = rig(reader = reader)
        run(r, flow(toast("a", "{{battery.temp}} on {{network.type}}")))
        assertEquals("toast:41 on wifi", r.device.calls.last())
        r.vars.set("network.type", Value.Text("mine"))
        run(r, flow(toast("a", "{{network.type}}")))
        assertEquals("toast:mine", r.device.calls.last())
    }

    @Test
    fun theLogSaysWhatStartedTheRun() = runTest {
        val r = rig()
        assertEquals("Started by: you (manually)", r.engine.execute(flow(), RunSource.MANUAL).logs.first())
        assertEquals("Started by: a timer", r.engine.execute(flow(), RunSource.SCHEDULE).logs.first())
        assertEquals("Started by: device start", r.engine.execute(flow(), RunSource.BOOT).logs.first())
        val rec = r.engine.execute(flow(), RunSource.MANUAL, com.androx.app.core.Event("sms"))
        assertEquals("Started by: event sms", rec.logs.first())
        assertNotNull(rec)
    }
}
