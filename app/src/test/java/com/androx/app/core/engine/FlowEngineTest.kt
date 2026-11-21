package com.androx.app.core.engine

import com.androx.app.core.Check
import com.androx.app.core.CheckIds
import com.androx.app.core.ConstraintGroup
import com.androx.app.core.Event
import com.androx.app.core.Flow
import com.androx.app.core.FlowType
import com.androx.app.core.LogicOp
import com.androx.app.core.Op
import com.androx.app.core.OnErrorPolicy
import com.androx.app.core.OpIds
import com.androx.app.core.RunOptions
import com.androx.app.core.Step
import com.androx.app.core.StepOptions
import com.androx.app.core.Value
import com.androx.app.core.WiringMode
import com.androx.app.core.params
import com.androx.app.engine.ActionException
import com.androx.app.engine.DeviceBridge
import com.androx.app.engine.ScriptHost
import com.androx.app.engine.ScriptResult
import com.androx.app.engine.ScriptRunner
import com.androx.app.model.Comparison
import com.androx.app.model.ExecutionStatus
import com.androx.app.model.FlashlightMode
import com.androx.app.model.RunSource
import com.androx.app.model.VariableMode
import com.androx.app.model.VolumeStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

internal class EngineFakeDevice(
    var battery: Int = 80,
    var charging: Boolean = false,
    var wifi: Boolean = true,
) : DeviceBridge {
    val calls = mutableListOf<String>()
    override fun batteryLevel() = battery
    override fun isCharging() = charging
    override fun isWifiConnected() = wifi
    override fun showNotification(title: String, message: String) {
        calls += "notify:$title|$message"
    }

    override fun showToast(message: String) {
        calls += "toast:$message"
    }

    override fun vibrate(durationMs: Int) {
        calls += "vibrate:$durationMs"
    }

    override fun speak(text: String) {
        calls += "speak:$text"
    }

    override fun copyToClipboard(text: String) {
        calls += "copy:$text"
    }

    override fun playNotificationSound() {
        calls += "sound"
    }

    override fun setVolume(stream: VolumeStream, percent: Int) {
        calls += "volume:${stream.name}:$percent"
    }

    override fun setFlashlight(mode: FlashlightMode): Boolean {
        calls += "flashlight:${mode.name}"
        return mode != FlashlightMode.OFF
    }
}

class FlowEngineTest {
    private val utc = ZoneId.of("UTC")
    private fun clockAt(hour: Int, minute: Int = 0): () -> Long =
        { ZonedDateTime.of(2026, 11, 21, hour, minute, 0, 0, utc).toInstant().toEpochMilli() }

    private fun step(id: String, typeId: String, p: kotlinx.serialization.json.JsonObject = params(), options: StepOptions = StepOptions(), blocks: Map<String, List<Step>> = emptyMap()) =
        Step(id, Op(typeId, p), options, blocks)

    private fun toast(id: String, text: String, options: StepOptions = StepOptions()) = step(id, OpIds.TOAST, params("message" to text), options)

    private fun flow(
        vararg steps: Step,
        id: String = "f1",
        constraints: ConstraintGroup? = null,
        run: RunOptions = RunOptions(),
    ) = Flow(id = id, name = "Test flow", steps = steps.toList(), constraints = constraints, runOptions = run)

    private class Rig(
        val device: EngineFakeDevice = EngineFakeDevice(),
        val vars: MapValueStore = MapValueStore(),
        flows: List<Flow> = emptyList(),
        clock: () -> Long = { 1_000L },
        extra: Map<String, ActionExecutor> = emptyMap(),
        scripts: ScriptRunner = object : ScriptRunner {
            override fun run(source: String, host: ScriptHost): ScriptResult = ScriptResult.Success
        },
    ) {
        val engine = FlowEngine(device, vars, { id -> flows.firstOrNull { it.id == id } }, scripts, clock, { ZoneId.of("UTC") }, extra)
    }

    // ── basics ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun stepsRunInOrderAndTemplatesUseTypedNames() = runTest {
        val rig = Rig(device = EngineFakeDevice(battery = 42))
        rig.vars.set("user", Value.parseJson("""{"name":"Ada"}"""))
        val f = flow(
            step("s0", OpIds.NOTIFY, params("title" to "Hi {{user.name}}", "message" to "{{battery}}% on {{flow}}")),
            toast("s1", "done"),
        )
        val r = rig.engine.execute(f, RunSource.MANUAL)
        assertEquals(ExecutionStatus.SUCCESS, r.status)
        assertEquals(listOf("notify:Hi Ada|42% on Test flow", "toast:done"), rig.device.calls)
        assertEquals("f1", r.automationId)
        assertTrue(r.logs.any { it.contains("Step 1: Show notification") })
    }

    @Test
    fun theOldAutomationNameStillWorksInTemplates() = runTest {
        val rig = Rig()
        rig.engine.execute(flow(toast("s0", "from {{automation}}")), RunSource.MANUAL)
        assertEquals(listOf("toast:from Test flow"), rig.device.calls)
    }

    @Test
    fun eventValuesAreAvailableToTemplates() = runTest {
        val rig = Rig()
        val e = Event("sms", mapOf("sms.sender" to Value.Text("Mum")))
        rig.engine.execute(flow(toast("s0", "from {{sms.sender}}")), RunSource.MANUAL, e)
        assertEquals(listOf("toast:from Mum"), rig.device.calls)
    }

    @Test
    fun anUnknownStepFailsClearly() = runTest {
        val rig = Rig()
        val r = rig.engine.execute(flow(step("s0", "from_the_future")), RunSource.MANUAL)
        assertEquals(ExecutionStatus.FAILED, r.status)
        assertTrue(r.message!!.contains("isn't available"))
    }

    // ── constraints ─────────────────────────────────────────────────────────────────────────

    private fun battery(op: Comparison, percent: Int, negate: Boolean = false) =
        Check(CheckIds.BATTERY_LEVEL, params("op" to op, "percent" to percent), negate)

    @Test
    fun failedConstraintsSkipTheRunWithAReason() = runTest {
        val rig = Rig(device = EngineFakeDevice(battery = 90))
        val f = flow(toast("s0", "x"), constraints = ConstraintGroup(rules = listOf(battery(Comparison.LT, 20))))
        val r = rig.engine.execute(f, RunSource.SCHEDULE)
        assertEquals(ExecutionStatus.SKIPPED, r.status)
        assertEquals("Condition not met: battery is 90% (needs < 20%)", r.message)
        assertTrue(rig.device.calls.isEmpty())
    }

    @Test
    fun allAnyNoneAndNegate() = runTest {
        val rig = Rig(device = EngineFakeDevice(battery = 50, charging = true))
        val low = battery(Comparison.LT, 20)
        val charging = Check(CheckIds.CHARGING, params("charging" to true))
        fun passes(g: ConstraintGroup) = rig.engine.checkConstraints(g).passed
        assertFalse(passes(ConstraintGroup(LogicOp.ALL, listOf(low, charging))))
        assertTrue(passes(ConstraintGroup(LogicOp.ANY, listOf(low, charging))))
        assertTrue(passes(ConstraintGroup(LogicOp.NONE, listOf(low))))
        assertFalse(passes(ConstraintGroup(LogicOp.NONE, listOf(low, charging))))
        assertTrue(passes(ConstraintGroup(LogicOp.ALL, listOf(battery(Comparison.LT, 20, negate = true)))))
        assertTrue(passes(ConstraintGroup()))
        val nested = ConstraintGroup(LogicOp.ALL, listOf(charging), listOf(ConstraintGroup(LogicOp.ANY, listOf(low, battery(Comparison.GTE, 50)))))
        assertTrue(passes(nested))
    }

    @Test
    fun negatedRulesExplainThemselves() {
        val rig = Rig(device = EngineFakeDevice(battery = 50))
        val r = rig.engine.checkConstraints(ConstraintGroup(rules = listOf(battery(Comparison.LT, 20, negate = true))))
        assertTrue(r.passed)
        assertEquals("not (battery is 50% (needs < 20%))", r.why)
    }

    @Test
    fun timeWindowWrapsPastMidnight() {
        val window = ConstraintGroup(rules = listOf(Check(CheckIds.TIME_WINDOW, params("fromHour" to 22, "fromMinute" to 0, "toHour" to 7, "toMinute" to 0))))
        assertTrue(Rig(clock = clockAt(23)).engine.checkConstraints(window).passed)
        assertTrue(Rig(clock = clockAt(3)).engine.checkConstraints(window).passed)
        assertFalse(Rig(clock = clockAt(12)).engine.checkConstraints(window).passed)
    }

    @Test
    fun variableCheckComparesNumbersAsNumbers() {
        val rig = Rig()
        rig.vars.set("n", Value.Text("10"))
        val gt9 = ConstraintGroup(rules = listOf(Check(CheckIds.VARIABLE, params("name" to "n", "op" to Comparison.GT, "value" to "9"))))
        assertTrue(rig.engine.checkConstraints(gt9).passed)
    }

    @Test
    fun anUnknownCheckFailsTheRunInsteadOfPassingSilently() = runTest {
        val rig = Rig()
        val f = flow(toast("s0", "x"), constraints = ConstraintGroup(rules = listOf(Check("from_the_future"))))
        val r = rig.engine.execute(f, RunSource.MANUAL)
        assertEquals(ExecutionStatus.FAILED, r.status)
        assertTrue(rig.device.calls.isEmpty())
    }

    // ── control flow ────────────────────────────────────────────────────────────────────────

    private fun ifStep(id: String, cond: ConstraintGroup, then: List<Step>, otherwise: List<Step> = emptyList()): Step {
        val element = com.androx.app.data.JsonFile.json.encodeToJsonElement(ConstraintGroup.serializer(), cond)
        return step(id, OpIds.IF, params("condition" to element), blocks = mapOf(OpIds.BLOCK_THEN to then, OpIds.BLOCK_ELSE to otherwise))
    }

    @Test
    fun ifElseTakesTheRightBranch() = runTest {
        val cond = ConstraintGroup(rules = listOf(Check(CheckIds.CHARGING, params("charging" to true))))
        val f = flow(ifStep("s0", cond, listOf(toast("s0.then0", "charging")), listOf(toast("s0.else0", "unplugged"))))
        val a = Rig(device = EngineFakeDevice(charging = true))
        a.engine.execute(f, RunSource.MANUAL)
        assertEquals(listOf("toast:charging"), a.device.calls)
        val b = Rig(device = EngineFakeDevice(charging = false))
        b.engine.execute(f, RunSource.MANUAL)
        assertEquals(listOf("toast:unplugged"), b.device.calls)
    }

    @Test
    fun repeatExposesTheRoundNumber() = runTest {
        val rig = Rig()
        val f = flow(step("s0", OpIds.REPEAT, params("times" to 3), blocks = mapOf(OpIds.BLOCK_BODY to listOf(toast("s0.body0", "round {{index}}")))))
        rig.engine.execute(f, RunSource.MANUAL)
        assertEquals(listOf("toast:round 1", "toast:round 2", "toast:round 3"), rig.device.calls)
    }

    @Test
    fun runawayLoopsStopAfterTheStepLimit() = runTest {
        fun loop(id: String, inner: List<Step>) = step(id, OpIds.REPEAT, params("times" to 20), blocks = mapOf(OpIds.BLOCK_BODY to inner))
        val deep = loop("a", listOf(loop("b", listOf(loop("c", listOf(step("d", OpIds.WRITE_LOG, params("message" to "x"))))))))
        val r = Rig().engine.execute(flow(deep), RunSource.MANUAL)
        assertEquals(ExecutionStatus.FAILED, r.status)
        assertTrue(r.message!!.contains("${FlowEngine.MAX_STEPS} steps"))
    }

    @Test
    fun runFlowCallsAnotherFlowAndGuardsAgainstCycles() = runTest {
        val child = flow(toast("c0", "from child"), id = "child")
        val parent = flow(step("s0", OpIds.RUN_FLOW, params("flowId" to "child", "flowName" to "Child")), id = "parent")
        val rig = Rig(flows = listOf(child, parent))
        assertEquals(ExecutionStatus.SUCCESS, rig.engine.execute(parent, RunSource.MANUAL).status)
        assertEquals(listOf("toast:from child"), rig.device.calls)

        val a = flow(step("s0", OpIds.RUN_FLOW, params("flowId" to "b")), id = "a")
        val b = flow(step("s0", OpIds.RUN_FLOW, params("flowId" to "a")), id = "b")
        val loop = Rig(flows = listOf(a, b)).engine.execute(a, RunSource.MANUAL)
        assertEquals(ExecutionStatus.FAILED, loop.status)
        assertTrue(loop.message!!.contains("would call itself"))
    }

    @Test
    fun missingOrTrashedTargetsFailWithAReadableMessage() = runTest {
        val parent = flow(step("s0", OpIds.RUN_FLOW, params("flowId" to "gone", "flowName" to "Old one")))
        val r = Rig().engine.execute(parent, RunSource.MANUAL)
        assertEquals("Flow \"Old one\" no longer exists.", r.message)
        val trashed = flow(toast("c0", "x"), id = "t").copy(deletedAt = 5L)
        val p2 = flow(step("s0", OpIds.RUN_FLOW, params("flowId" to "t")))
        assertTrue(Rig(flows = listOf(trashed)).engine.execute(p2, RunSource.MANUAL).message!!.contains("in Trash"))
    }

    // ── variables ───────────────────────────────────────────────────────────────────────────

    private fun setVar(id: String, name: String, mode: VariableMode, value: String) =
        step(id, OpIds.SET_VARIABLE, params("name" to name, "mode" to mode, "value" to value))

    @Test
    fun variableMathAppendAndDelete() = runTest {
        val rig = Rig()
        val f = flow(
            setVar("a", "n", VariableMode.SET, "5"),
            setVar("b", "n", VariableMode.ADD, "2"),
            setVar("c", "n", VariableMode.MULTIPLY, "3"),
            setVar("d", "n", VariableMode.SUBTRACT, "1"),
            setVar("e", "s", VariableMode.APPEND, "x"),
            setVar("f", "s", VariableMode.APPEND, "y"),
            setVar("g", "gone", VariableMode.SET, "1"),
            setVar("h", "gone", VariableMode.DELETE, ""),
        )
        assertEquals(ExecutionStatus.SUCCESS, rig.engine.execute(f, RunSource.MANUAL).status)
        assertEquals("20", rig.vars.get("n")!!.render())
        assertEquals("xy", rig.vars.get("s")!!.render())
        assertNull(rig.vars.get("gone"))
    }

    @Test
    fun addingToTextExplainsTheProblem() = runTest {
        val rig = Rig()
        rig.vars.set("name", Value.Text("Ada"))
        val r = rig.engine.execute(flow(setVar("a", "name", VariableMode.ADD, "1")), RunSource.MANUAL)
        assertEquals(ExecutionStatus.FAILED, r.status)
        assertTrue(r.message!!.contains("which is not a number"))
        val r2 = rig.engine.execute(flow(setVar("a", "n", VariableMode.ADD, "abc")), RunSource.MANUAL)
        assertTrue(r2.message!!.contains("is not a number"))
    }

    @Test
    fun saveResultToMakesAStepsResultAvailableLater() = runTest {
        val rig = Rig()
        val f = flow(
            step("s0", OpIds.FLASHLIGHT, params("mode" to FlashlightMode.ON), StepOptions(saveResultTo = "light")),
            toast("s1", "light is {{light}}"),
        )
        rig.engine.execute(f, RunSource.MANUAL)
        assertEquals(listOf("flashlight:ON", "toast:light is true"), rig.device.calls)
        assertNull(rig.vars.get("light")) // results live for the run only
    }

    // ── step options ────────────────────────────────────────────────────────────────────────

    @Test
    fun disabledAndOnlyIfSteps() = runTest {
        val rig = Rig(device = EngineFakeDevice(battery = 90))
        val gate = ConstraintGroup(rules = listOf(battery(Comparison.LT, 20)))
        val f = flow(
            toast("s0", "off", StepOptions(disabled = true)),
            toast("s1", "gated", StepOptions(onlyIf = gate)),
            toast("s2", "runs"),
        )
        val r = rig.engine.execute(f, RunSource.MANUAL)
        assertEquals(listOf("toast:runs"), rig.device.calls)
        assertEquals(ExecutionStatus.SUCCESS, r.status)
        assertTrue(r.logs.any { it.contains("turned off, skipped") })
        assertTrue(r.logs.any { it.contains("skipped: battery is 90%") })
    }

    private class Flaky(private val failures: Int) : ActionExecutor {
        var attempts = 0
        override suspend fun run(params: kotlinx.serialization.json.JsonObject, env: ActionEnv): Value? {
            attempts++
            if (attempts <= failures) throw ActionException("not yet ($attempts)")
            return Value.Text("ok")
        }
    }

    @Test
    fun retriesTryAgainAndThenSucceed() = runTest {
        val flaky = Flaky(failures = 2)
        val rig = Rig(extra = mapOf("flaky" to flaky))
        val r = rig.engine.execute(flow(step("s0", "flaky", options = StepOptions(retries = 2, retryDelayMs = 100))), RunSource.MANUAL)
        assertEquals(ExecutionStatus.SUCCESS, r.status)
        assertEquals(3, flaky.attempts)
        assertTrue(r.logs.any { it.contains("trying again (2 of 2)") })
    }

    @Test
    fun retriesGiveUpAndFail() = runTest {
        val flaky = Flaky(failures = 10)
        val r = Rig(extra = mapOf("flaky" to flaky)).engine.execute(flow(step("s0", "flaky", options = StepOptions(retries = 1))), RunSource.MANUAL)
        assertEquals(ExecutionStatus.FAILED, r.status)
        assertEquals(2, flaky.attempts)
        assertEquals("not yet (2)", r.message)
    }

    @Test
    fun continueOnErrorKeepsGoing() = runTest {
        val rig = Rig(extra = mapOf("flaky" to Flaky(failures = 99)))
        val f = flow(step("s0", "flaky", options = StepOptions(continueOnError = true)), toast("s1", "after"))
        val r = rig.engine.execute(f, RunSource.MANUAL)
        assertEquals(ExecutionStatus.SUCCESS, r.status)
        assertEquals(listOf("toast:after"), rig.device.calls)
        assertTrue(r.logs.any { it.contains("(carrying on)") })
    }

    @Test
    fun flowWideContinuePolicyAppliesToEveryStep() = runTest {
        val rig = Rig(extra = mapOf("flaky" to Flaky(failures = 99)))
        val f = flow(step("s0", "flaky"), toast("s1", "after"), run = RunOptions(onError = OnErrorPolicy.CONTINUE))
        assertEquals(ExecutionStatus.SUCCESS, rig.engine.execute(f, RunSource.MANUAL).status)
        assertEquals(listOf("toast:after"), rig.device.calls)
    }

    @Test
    fun aStepTimeoutStopsAHangingStep() = runTest {
        val hang = ActionExecutor { _, _ -> delay(100_000); null }
        val rig = Rig(extra = mapOf("hang" to hang))
        val r = rig.engine.execute(flow(step("s0", "hang", options = StepOptions(timeoutSeconds = 2))), RunSource.MANUAL)
        assertEquals(ExecutionStatus.FAILED, r.status)
        assertEquals("This step took longer than 2 seconds.", r.message)
    }

    @Test
    fun aRunTimeoutStopsTheWholeRun() = runTest {
        val f = flow(step("s0", OpIds.WAIT, params("seconds" to 10)), run = RunOptions(timeoutSeconds = 5))
        val r = Rig().engine.execute(f, RunSource.MANUAL)
        assertEquals(ExecutionStatus.FAILED, r.status)
        assertTrue(r.message!!.contains("longer than 5 seconds"))
    }

    @Test
    fun waitsShareAThirtySecondBudgetUnlessRaised() = runTest {
        val tenSeconds = { id: String -> step(id, OpIds.WAIT, params("seconds" to 10)) }
        val four = flow(tenSeconds("a"), tenSeconds("b"), tenSeconds("c"), tenSeconds("d"), run = RunOptions(timeoutSeconds = 0))
        val r = Rig().engine.execute(four, RunSource.MANUAL)
        assertEquals(ExecutionStatus.FAILED, r.status)
        assertEquals("A run can wait at most 30 seconds in total.", r.message)

        val raised = four.copy(runOptions = RunOptions(timeoutSeconds = 0, waitBudgetSeconds = 60))
        assertEquals(ExecutionStatus.SUCCESS, Rig().engine.execute(raised, RunSource.MANUAL).status)
    }

    // ── scripts and Code flows ──────────────────────────────────────────────────────────────

    private class RecordingScripts(val result: ScriptResult = ScriptResult.Success) : ScriptRunner {
        val sources = mutableListOf<String>()
        override fun run(source: String, host: ScriptHost): ScriptResult {
            sources += source
            host.setVar("fromScript", "yes")
            host.log("hello")
            return result
        }
    }

    @Test
    fun codeFlowsRunTheirScript() = runTest {
        val scripts = RecordingScripts()
        val rig = Rig(scripts = scripts)
        val f = Flow(id = "c", name = "Code", type = FlowType.CODE, script = "androx.log('x')")
        val r = rig.engine.execute(f, RunSource.MANUAL)
        assertEquals(ExecutionStatus.SUCCESS, r.status)
        assertEquals(listOf("androx.log('x')"), scripts.sources)
        assertEquals("yes", rig.vars.get("fromScript")!!.render())
        assertTrue(r.logs.any { it.contains("[script] hello") })
    }

    @Test
    fun aFailingScriptFailsTheRun() = runTest {
        val rig = Rig(scripts = RecordingScripts(ScriptResult.Failure("line 3: boom")))
        val r = rig.engine.execute(Flow(id = "c", name = "Code", type = FlowType.CODE, script = "x"), RunSource.MANUAL)
        assertEquals(ExecutionStatus.FAILED, r.status)
        assertEquals("line 3: boom", r.message)
    }

    @Test
    fun freeFormCanvasFlowsSayTheyCantRunYet() = runTest {
        val f = Flow(id = "x", name = "Canvas", type = FlowType.CANVAS, wiring = WiringMode.FREE_FORM)
        val r = Rig().engine.execute(f, RunSource.MANUAL)
        assertEquals(ExecutionStatus.FAILED, r.status)
        assertTrue(r.message!!.contains("Free-form"))
    }

    // ── simulation ──────────────────────────────────────────────────────────────────────────

    @Test
    fun simulationChangesNothingButExplainsEverything() = runTest {
        val scripts = RecordingScripts()
        val rig = Rig(scripts = scripts)
        rig.vars.set("n", Value.Text("1"))
        val f = flow(
            toast("s0", "hello"),
            step("s1", OpIds.VIBRATE, params("durationMs" to 300)),
            setVar("s2", "n", VariableMode.ADD, "5"),
            toast("s3", "n is {{n}}"),
            step("s4", OpIds.RUN_SCRIPT, params("source" to "x()")),
            step("s5", OpIds.WAIT, params("seconds" to 10)),
        )
        val r = rig.engine.simulate(f)
        assertEquals(ExecutionStatus.SUCCESS, r.status)
        assertTrue(rig.device.calls.isEmpty())
        assertTrue(scripts.sources.isEmpty())
        assertEquals("1", rig.vars.get("n")!!.render())
        assertTrue(r.logs.first().startsWith("Simulation:"))
        assertTrue(r.logs.any { it.contains("would: Show toast") })
        assertTrue(r.logs.any { it.contains("n = 6") })
        assertTrue(r.logs.any { it.contains("would: Show toast (n is {{n}})") })
    }

    @Test
    fun simulatedWaitsSkipTheDelayButStillCountAgainstTheBudget() = runTest {
        val tenSeconds = { id: String -> step(id, OpIds.WAIT, params("seconds" to 10)) }
        val f = flow(tenSeconds("a"), tenSeconds("b"), tenSeconds("c"), tenSeconds("d"), run = RunOptions(timeoutSeconds = 0))
        val r = Rig().engine.simulate(f)
        assertEquals(ExecutionStatus.FAILED, r.status)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun simulationStillEvaluatesConstraints() = runTest {
        val rig = Rig(device = EngineFakeDevice(battery = 90))
        val f = flow(toast("s0", "x"), constraints = ConstraintGroup(rules = listOf(battery(Comparison.LT, 20))))
        assertEquals(ExecutionStatus.SKIPPED, rig.engine.simulate(f).status)
    }

    @Test
    fun valuesStoresBehave() {
        val base = MapValueStore(mapOf("a" to Value.Text("1")))
        val shadow = ShadowValueStore(base)
        shadow.set("b", Value.Text("2"))
        shadow.remove("a")
        assertNull(shadow.get("a"))
        assertEquals("2", shadow.get("b")!!.render())
        assertEquals("1", base.get("a")!!.render())
        assertNull(base.get("b"))
        shadow.set("a", Value.Text("3"))
        assertEquals("3", shadow.get("a")!!.render())
    }

    @Test
    fun stringBackedStoreKeepsOldVariablesWorking() {
        val old = object : com.androx.app.engine.VariableStore {
            val m = mutableMapOf("count" to "41")
            override fun get(name: String) = m[name]
            override fun set(name: String, value: String) {
                m[name] = value
            }

            override fun remove(name: String) {
                m.remove(name)
            }
        }
        val store = old.asValues()
        assertEquals(41.0, store.get("count")!!.asNumberOrNull()!!, 0.0)
        store.set("count", Value.Num(42.0))
        assertEquals("42", old.m["count"])
        store.set("flag", Value.Bool(true))
        assertEquals("true", old.m["flag"])
    }

    @Test
    fun cancellationIsNotSwallowedByRetries() = runTest {
        // A run that is cancelled mid-wait must stop, not be recorded as a failure.
        val rig = Rig()
        val job = launch {
            rig.engine.execute(flow(step("s0", OpIds.WAIT, params("seconds" to 10)), run = RunOptions(timeoutSeconds = 0)), RunSource.MANUAL)
        }
        yield()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }
}
