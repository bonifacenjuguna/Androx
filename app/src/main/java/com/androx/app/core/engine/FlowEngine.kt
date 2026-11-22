package com.androx.app.core.engine

import com.androx.app.core.ConstraintGroup
import com.androx.app.core.Expr
import com.androx.app.core.ExprException
import com.androx.app.core.StringOps
import com.androx.app.core.path
import com.androx.app.core.state.DeviceStateReader
import com.androx.app.core.state.LegacyStateReader
import com.androx.app.core.state.StateCatalog
import com.androx.app.core.Event
import com.androx.app.core.Flow
import com.androx.app.core.FlowMigration
import com.androx.app.core.FlowType
import com.androx.app.core.OnErrorPolicy
import com.androx.app.core.OpIds
import com.androx.app.core.Step
import com.androx.app.core.Value
import com.androx.app.core.WiringMode
import com.androx.app.core.bool
import com.androx.app.core.enum
import com.androx.app.core.int
import com.androx.app.core.spec.SpecCatalog
import com.androx.app.core.str
import com.androx.app.engine.ActionException
import com.androx.app.engine.DeviceBridge
import com.androx.app.engine.RhinoScriptRunner
import com.androx.app.engine.ScriptHost
import com.androx.app.engine.ScriptResult
import com.androx.app.engine.ScriptRunner
import com.androx.app.model.ExecutionRecord
import com.androx.app.model.ExecutionStatus
import com.androx.app.model.FlashlightMode
import com.androx.app.model.RunSource
import com.androx.app.model.VariableMode
import com.androx.app.model.VolumeStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.random.Random

/** What a leaf action can see and do while it runs. Kept small on purpose; new actions only need this. */
interface ActionEnv {
    val device: DeviceBridge
    val values: ValueStore
    val simulating: Boolean

    /** Fills `{{placeholders}}` in [text]. */
    fun render(text: String): String

    /** Looks one name up the way a placeholder would. */
    fun lookup(name: String): Value?

    /** Adds an indented line under the current step in the run log. */
    fun detail(text: String)

    /** Pauses, counting against the run's wait budget (spec §5.2). */
    suspend fun pause(seconds: Int)

    /** Sets a value that lives only until this run ends. */
    fun setLocal(name: String, value: Value)
}

/** One executable action. Return the step's result (saved to a variable when the step asks for it) or null. */
interface ActionExecutor {
    suspend fun run(params: JsonObject, env: ActionEnv): Value?
}

/** Builds an [ActionExecutor] from a lambda: `ActionExecutor { params, env -> … }`. */
fun ActionExecutor(block: suspend (JsonObject, ActionEnv) -> Value?): ActionExecutor = object : ActionExecutor {
    override suspend fun run(params: JsonObject, env: ActionEnv): Value? = block(params, env)
}

enum class StopOutcome { SUCCESS, FAILED, SKIPPED }

/** Loop and Flow exits. They are not errors: retries, "continue on error" and Try never swallow them. */
internal sealed class ControlSignal : RuntimeException(null, null, false, false)
internal class BreakSignal : ControlSignal()
internal class ContinueSignal : ControlSignal()
internal class StopSignal(val outcome: StopOutcome, override val message: String, val value: Value?) : ControlSignal()

/**
 * Runs a [Flow] (spec §5.2, §9): checks its constraints, then executes the step tree with per-step options
 * (disable, "only if", retries, timeout, continue on error, save result), run limits and a simulator.
 *
 * Pure Kotlin over the existing [DeviceBridge] / [com.androx.app.engine.VariableStore] interfaces, so it is
 * unit-tested on the JVM. Leaf actions live in a table; adding an action means adding one [ActionExecutor].
 */
class FlowEngine(
    private val device: DeviceBridge,
    private val variables: ValueStore,
    private val findFlow: (String) -> Flow?,
    private val scripts: ScriptRunner = RhinoScriptRunner(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    extraActions: Map<String, ActionExecutor> = emptyMap(),
    private val state: DeviceStateReader = LegacyStateReader(device),
    private val flowInfo: FlowInfo = NoFlowInfo,
    private val flowControl: FlowControl? = null,
    private val random: Random = Random.Default,
) {
    companion object {
        const val MAX_DEPTH = 4
        const val MAX_WAIT_SECONDS = 10
        const val DEFAULT_WAIT_BUDGET_MS = 30_000L
        const val MAX_REPEAT = 20
        const val MAX_STEPS = 1_000
        const val MAX_RETRIES = 5
        const val MAX_RETRY_DELAY_MS = 10_000L
        const val MAX_LOOP_ITEMS = 1_000
        const val MAX_WHILE = 1_000
    }

    private val constraints = ConstraintEvaluator(device, clock, zone, state, flowInfo)
    private val catalog get() = SpecCatalog.default

    /** Everything shared by one whole run, including nested Flows and loops. */
    private class RunState(
        val mode: RunMode,
        val values: ValueStore,
        val event: Event?,
        val waitBudgetMs: Long,
        val source: RunSource,
    ) {
        var waitedMs = 0L
        var steps = 0
        val results = HashMap<String, Value>()
    }

    private inner class Ctx(
        val flow: Flow,
        val logs: MutableList<String>,
        val stack: List<String>,
        val locals: Map<String, Value>,
        val depth: Int,
        val state: RunState,
        val loopDepth: Int = 0,
    ) : ActionEnv {
        override val device: DeviceBridge get() = this@FlowEngine.device
        override val values: ValueStore get() = state.values
        override val simulating: Boolean get() = state.mode == RunMode.SIMULATE

        override fun render(text: String): String = TemplateEngine.render(text) { lookup(it) }
        override fun lookup(name: String): Value? = lookupName(this, name)
        override fun detail(text: String) {
            logs += "  ".repeat(depth + 1) + text
        }

        override suspend fun pause(seconds: Int) {
            val secs = seconds.coerceIn(1, MAX_WAIT_SECONDS)
            val millis = secs * 1_000L
            if (state.waitedMs + millis > state.waitBudgetMs) {
                throw ActionException("A run can wait at most ${state.waitBudgetMs / 1000} seconds in total.")
            }
            state.waitedMs += millis
            detail("waiting $secs s")
            if (!simulating) delay(millis)
        }

        override fun setLocal(name: String, value: Value) {
            state.results[name] = value
        }

        fun checkContext() = CheckContext(render = { text -> render(text) }, source = state.source, event = state.event)

        fun deeper(extra: Map<String, Value> = emptyMap(), loop: Boolean = false) =
            Ctx(flow, logs, stack, locals + extra, depth + 1, state, loopDepth + if (loop) 1 else 0)
    }

    // ── public API ──────────────────────────────────────────────────────────────────────────

    /** Checks only the constraints against live values. Used by trigger gating and the Flow page's "Test constraints". */
    fun checkConstraints(group: ConstraintGroup?, cx: CheckContext = CheckContext.NONE): CheckResult = constraints.evaluate(group, variables, cx)

    /** A dry run: describes what each step would do and changes nothing on the device or in saved variables. */
    suspend fun simulate(flow: Flow, event: Event? = null): ExecutionRecord = execute(flow, RunSource.MANUAL, event, RunMode.SIMULATE)

    suspend fun execute(
        flow: Flow,
        source: RunSource,
        event: Event? = null,
        mode: RunMode = RunMode.LIVE,
    ): ExecutionRecord {
        val started = clock()
        val logs = mutableListOf<String>()
        if (mode == RunMode.SIMULATE) logs += "Simulation: nothing on the device or in your variables is changed."
        var status = ExecutionStatus.SUCCESS
        var message: String? = null
        val values = if (mode == RunMode.SIMULATE) ShadowValueStore(variables) else variables
        val budget = flow.runOptions.waitBudgetSeconds.let { if (it > 0) it * 1_000L else DEFAULT_WAIT_BUDGET_MS }
        val state = RunState(mode, values, event, budget, source)
        logs += "Started by: ${startedBy(source, event)}"
        try {
            val limitMs = flow.runOptions.timeoutSeconds * 1_000L
            val body: suspend () -> String? = { runFlow(flow, logs, emptyList(), state, emptyMap()).skipped }
            if (limitMs > 0) {
                var skipped: String? = null
                val finished = withTimeoutOrNull(limitMs) {
                    skipped = body()
                    true
                }
                if (finished == null) throw ActionException("This run took longer than ${flow.runOptions.timeoutSeconds} seconds and was stopped.")
                if (skipped != null) {
                    status = ExecutionStatus.SKIPPED
                    message = skipped
                }
            } else {
                val skipped = body()
                if (skipped != null) {
                    status = ExecutionStatus.SKIPPED
                    message = skipped
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = ExecutionStatus.FAILED
            message = e.message ?: e.javaClass.simpleName
        }
        return ExecutionRecord(
            id = UUID.randomUUID().toString(),
            automationId = flow.id,
            automationName = flow.name,
            startedAt = started,
            durationMs = (clock() - started).coerceAtLeast(0),
            status = status,
            source = source,
            message = message,
            logs = logs,
        )
    }

    // ── running a Flow ──────────────────────────────────────────────────────────────────────

    /** Returns a reason when the run was skipped, null when it ran. Throws on failure. */
    private class FlowOutcome(val skipped: String?, val value: Value? = null)

    private fun startedBy(source: RunSource, event: Event?): String = when {
        event != null -> "event ${event.typeId}"
        source == RunSource.SCHEDULE -> "a timer"
        source == RunSource.BOOT -> "device start"
        else -> "you (manually)"
    }

    private suspend fun runFlow(
        flow: Flow,
        logs: MutableList<String>,
        stack: List<String>,
        state: RunState,
        inputs: Map<String, Value>,
    ): FlowOutcome {
        if (flow.id in stack) throw ActionException("\"${flow.name}\" would call itself.")
        if (stack.size >= MAX_DEPTH) throw ActionException("Nested Flows are limited to $MAX_DEPTH levels.")

        val ctx = Ctx(flow, logs, stack + flow.id, inputs, 0, state)
        val gate = flow.constraints
        if (gate != null && !gate.isEmpty) {
            val r = constraints.evaluate(gate, state.values, ctx.checkContext())
            logs += "Condition: ${r.why}"
            if (!r.passed) return FlowOutcome("Condition not met: ${r.why}")
        }
        try {
            when {
                flow.type == FlowType.CODE -> runScript(flow.script.orEmpty(), ctx)
                flow.type == FlowType.CANVAS && flow.wiring == WiringMode.FREE_FORM ->
                    throw ActionException("Free-form Canvas Flows can't run yet.")
                else -> runSteps(flow.steps, ctx)
            }
        } catch (stop: StopSignal) {
            return when (stop.outcome) {
                StopOutcome.SUCCESS -> FlowOutcome(null, stop.value)
                StopOutcome.SKIPPED -> FlowOutcome(stop.message.ifBlank { "Stopped by a Stop step." })
                StopOutcome.FAILED -> throw ActionException(stop.message.ifBlank { "Stopped with an error." })
            }
        }
        return FlowOutcome(null)
    }

    private suspend fun runSteps(steps: List<Step>, ctx: Ctx) {
        for ((index, step) in steps.withIndex()) runStep(step, index, ctx)
    }

    private suspend fun runStep(step: Step, index: Int, ctx: Ctx) {
        currentCoroutineContext().ensureActive()
        val state = ctx.state
        if (++state.steps > MAX_STEPS) throw ActionException("This run stopped after $MAX_STEPS steps.")

        val typeId = step.op.typeId
        val spec = catalog.actions[typeId]
        val label = (spec?.name ?: "Unavailable ($typeId)") + spec?.let { s ->
            val text = catalog.actions.summarize(typeId, step.op.params)
            if (text.isNotBlank() && s.blocks.isEmpty()) ": $text" else ""
        }.orEmpty()
        ctx.logs += "  ".repeat(ctx.depth) + "Step ${index + 1}: $label"

        if (step.options.disabled) {
            ctx.detail("turned off, skipped")
            return
        }
        step.options.onlyIf?.let { gate ->
            val r = constraints.evaluate(gate, state.values, ctx.checkContext())
            if (!r.passed) {
                ctx.detail("skipped: ${r.why}")
                return
            }
        }

        val retries = step.options.retries.coerceIn(0, MAX_RETRIES)
        var attempt = 0
        while (true) {
            try {
                runOnce(step, ctx)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ControlSignal) {
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                if (attempt < retries) {
                    attempt++
                    ctx.detail("failed ($reason), trying again ($attempt of $retries)")
                    if (!ctx.simulating) delay(step.options.retryDelayMs.coerceIn(0L, MAX_RETRY_DELAY_MS))
                    continue
                }
                if (step.options.continueOnError || ctx.flow.runOptions.onError == OnErrorPolicy.CONTINUE) {
                    ctx.detail("failed: $reason (carrying on)")
                    return
                }
                throw e
            }
        }
    }

    private class Holder(val value: Value?)

    private suspend fun runOnce(step: Step, ctx: Ctx) {
        val limit = step.options.timeoutSeconds * 1_000L
        val holder: Holder? = if (limit > 0) {
            withTimeoutOrNull(limit) { Holder(dispatch(step, ctx)) }
        } else {
            Holder(dispatch(step, ctx))
        }
        if (holder == null) throw ActionException("This step took longer than ${step.options.timeoutSeconds} seconds.")
        val target = step.options.saveResultTo.trim()
        val result = holder.value
        if (target.isNotEmpty() && result != null) {
            ctx.state.results[target] = result
            ctx.detail("$target = ${result.render()}")
        }
    }

    // ── steps ───────────────────────────────────────────────────────────────────────────────

    /** Steps that change the device or the outside world. A simulation describes them instead of doing them. */
    private val effects = setOf(
        OpIds.NOTIFY, OpIds.TOAST, OpIds.VIBRATE, OpIds.SPEAK, OpIds.CLIPBOARD, OpIds.PLAY_SOUND,
        OpIds.SET_VOLUME, OpIds.FLASHLIGHT, OpIds.RUN_SCRIPT, OpIds.FLOW_ENABLE,
    )

    private val actions: Map<String, ActionExecutor> = buildMap {
        put(OpIds.NOTIFY, ActionExecutor { p, env -> env.device.showNotification(env.render(p.str("title", "Androx")), env.render(p.str("message"))); null })
        put(OpIds.TOAST, ActionExecutor { p, env -> env.device.showToast(env.render(p.str("message"))); null })
        put(OpIds.VIBRATE, ActionExecutor { p, env -> env.device.vibrate(p.int("durationMs", 400).coerceIn(50, 5_000)); null })
        put(OpIds.SPEAK, ActionExecutor { p, env -> env.device.speak(env.render(p.str("text"))); null })
        put(OpIds.CLIPBOARD, ActionExecutor { p, env -> env.device.copyToClipboard(env.render(p.str("text"))); null })
        put(OpIds.PLAY_SOUND, ActionExecutor { _, env -> env.device.playNotificationSound(); null })
        put(OpIds.SET_VOLUME, ActionExecutor { p, env ->
            env.device.setVolume(p.enum("stream", VolumeStream.MEDIA), p.int("percent", 50).coerceIn(0, 100))
            null
        })
        put(OpIds.FLASHLIGHT, ActionExecutor { p, env ->
            val on = env.device.setFlashlight(p.enum("mode", FlashlightMode.TOGGLE))
            env.detail("flashlight is ${if (on) "on" else "off"}")
            Value.Bool(on)
        })
        put(OpIds.WRITE_LOG, ActionExecutor { p, env -> env.detail(env.render(p.str("message"))); null })
        put(OpIds.WAIT, ActionExecutor { p, env -> env.pause(p.int("seconds", 2)); null })
        put(OpIds.SET_VARIABLE, ActionExecutor { p, env -> setVariable(p, env) })
        put(OpIds.CALCULATE, ActionExecutor { p, env -> calculate(p, env) })
        put(OpIds.TEXT, ActionExecutor { p, env -> textTool(p, env) })
        put(OpIds.JSON, ActionExecutor { p, env -> readJson(p, env) })
        put(OpIds.DATE_FORMAT, ActionExecutor { p, env -> formatDate(p, env) })
        put(OpIds.RANDOM, ActionExecutor { p, env -> randomValue(p, env) })
        put(OpIds.LIST_OP, ActionExecutor { p, env -> listTool(p, env) })
        put(OpIds.DICT_OP, ActionExecutor { p, env -> dictTool(p, env) })
        put(OpIds.SET_LOCAL, ActionExecutor { p, env -> setLocalValue(p, env) })
        put(OpIds.FLOW_ENABLE, ActionExecutor { p, env -> flowEnable(p, env) })
        putAll(extraActions)
    }

    private suspend fun dispatch(step: Step, ctx: Ctx): Value? {
        val typeId = step.op.typeId
        val p = step.op.params
        return when (typeId) {
            OpIds.IF -> {
                val condition = FlowMigration.ifCondition(step)
                val r = constraints.evaluate(condition, ctx.state.values, ctx.checkContext())
                ctx.detail("if ${r.why} → ${if (r.passed) "then" else "else"}")
                runSteps(step.blocks[if (r.passed) OpIds.BLOCK_THEN else OpIds.BLOCK_ELSE].orEmpty(), ctx.deeper())
                Value.Bool(r.passed)
            }
            OpIds.REPEAT -> {
                val times = p.int("times", 3).coerceIn(1, MAX_REPEAT)
                for (round in 1..times) {
                    currentCoroutineContext().ensureActive()
                    ctx.detail("round $round of $times")
                    if (runBody(step, ctx, mapOf("index" to Value.Num(round.toDouble())))) break
                }
                null
            }
            OpIds.FOR_EACH -> forEach(step, ctx)
            OpIds.WHILE -> whileLoop(step, ctx)
            OpIds.WAIT_UNTIL -> waitUntil(step, ctx)
            OpIds.TRY -> tryStep(step, ctx)
            OpIds.BREAK -> {
                if (ctx.loopDepth == 0) throw ActionException("\"Leave loop\" can only be used inside a loop.")
                throw BreakSignal()
            }
            OpIds.CONTINUE -> {
                if (ctx.loopDepth == 0) throw ActionException("\"Next round\" can only be used inside a loop.")
                throw ContinueSignal()
            }
            OpIds.STOP -> {
                val outcome = when (p.str("outcome", "success")) {
                    "failed" -> StopOutcome.FAILED
                    "skipped" -> StopOutcome.SKIPPED
                    else -> StopOutcome.SUCCESS
                }
                val message = ctx.render(p.str("message"))
                val result = p.str("result").takeIf { it.isNotEmpty() }?.let { Value.Text(ctx.render(it)) }
                ctx.detail("stopping the Flow" + if (message.isNotBlank()) ": $message" else "")
                throw StopSignal(outcome, message, result)
            }
            OpIds.RUN_FLOW -> {
                val id = p.str("flowId")
                val name = p.str("flowName")
                val target = findFlow(id) ?: throw ActionException("Flow \"${name.ifBlank { id }}\" no longer exists.")
                if (target.inTrash) throw ActionException("\"${target.name}\" is in Trash.")
                val inputs = parseInputs(ctx.render(p.str("inputs")))
                val out = runFlow(target, ctx.logs, ctx.stack, ctx.state, inputs)
                ctx.detail(if (out.skipped == null) "ran \"${target.name}\"" else "skipped \"${target.name}\": ${out.skipped}")
                out.value
            }
            OpIds.RUN_SCRIPT -> {
                if (ctx.simulating) {
                    ctx.detail("would run a script")
                    null
                } else {
                    runScript(p.str("source"), ctx)
                    null
                }
            }
            else -> {
                val exec = actions[typeId] ?: throw ActionException("\"$typeId\" isn't available in this version.")
                if (ctx.simulating && typeId in effects) {
                    ctx.detail("would: ${catalog.actions.nameOf(typeId)} (${catalog.actions.summarize(typeId, p)})")
                    null
                } else {
                    exec.run(p, ctx)
                }
            }
        }
    }


    // ── loops and structured steps ──────────────────────────────────────────────────────────

    /** Runs a loop body once. Returns true when the body asked to leave the loop. */
    private suspend fun runBody(step: Step, ctx: Ctx, locals: Map<String, Value>): Boolean {
        try {
            runSteps(step.blocks[OpIds.BLOCK_BODY].orEmpty(), ctx.deeper(locals, loop = true))
        } catch (e: ContinueSignal) {
            // Next round.
        } catch (e: BreakSignal) {
            return true
        }
        return false
    }

    /** A list or dictionary as JSON, or one item per line. Dictionary entries carry their key. */
    private fun loopEntries(raw: String): List<Pair<String?, Value>> {
        val t = raw.trim()
        if (t.isEmpty()) return emptyList()
        if (t.startsWith("[") || t.startsWith("{")) {
            val v = try {
                Value.parseJson(t)
            } catch (e: IllegalArgumentException) {
                throw ActionException(e.message ?: "That isn't a valid list.")
            }
            return when (v) {
                is Value.Items -> v.items.map { null to it }
                is Value.Dict -> v.entries.map { it.key to it.value }
                else -> listOf(null to v)
            }
        }
        return t.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { null to Value.Text(it) }
    }

    private suspend fun forEach(step: Step, ctx: Ctx): Value? {
        val entries = loopEntries(ctx.render(step.op.params.str("source")))
        if (entries.size > MAX_LOOP_ITEMS) throw ActionException("That list has more than $MAX_LOOP_ITEMS items.")
        val itemName = step.op.params.str("itemName", "item").trim().ifEmpty { "item" }
        ctx.detail("${entries.size} item${if (entries.size == 1) "" else "s"}")
        for ((i, entry) in entries.withIndex()) {
            currentCoroutineContext().ensureActive()
            val locals = mapOf(
                itemName to entry.second,
                "index" to Value.Num((i + 1).toDouble()),
                "key" to Value.Text(entry.first.orEmpty()),
            )
            if (runBody(step, ctx, locals)) break
        }
        return Value.Num(entries.size.toDouble())
    }

    private suspend fun whileLoop(step: Step, ctx: Ctx): Value? {
        val condition = FlowMigration.ifCondition(step)
        val max = step.op.params.int("max", 100).coerceIn(1, MAX_WHILE)
        var round = 0
        while (round < max) {
            currentCoroutineContext().ensureActive()
            val r = constraints.evaluate(condition, ctx.state.values, ctx.checkContext())
            if (!r.passed) {
                ctx.detail("stopped: ${r.why}")
                return Value.Num(round.toDouble())
            }
            round++
            ctx.detail("round $round")
            if (runBody(step, ctx, mapOf("index" to Value.Num(round.toDouble())))) return Value.Num(round.toDouble())
        }
        ctx.detail("stopped after the maximum of $max rounds")
        return Value.Num(round.toDouble())
    }

    private suspend fun waitUntil(step: Step, ctx: Ctx): Value? {
        val p = step.op.params
        val condition = FlowMigration.ifCondition(step)
        val timeout = p.int("timeoutSeconds", 10).coerceIn(1, MAX_WAIT_SECONDS)
        val poll = p.int("pollSeconds", 1).coerceIn(1, 5)
        var waited = 0
        var last: CheckResult
        while (true) {
            last = constraints.evaluate(condition, ctx.state.values, ctx.checkContext())
            if (last.passed) {
                ctx.detail("condition met: ${last.why}")
                return Value.Bool(true)
            }
            if (ctx.simulating) {
                ctx.detail("would wait up to $timeout s for: ${last.why}")
                return Value.Bool(false)
            }
            if (waited + poll > timeout) break
            ctx.pause(poll)
            waited += poll
        }
        if (p.str("onTimeout", "fail") == "continue") {
            ctx.detail("gave up after $waited s: ${last.why}")
            return Value.Bool(false)
        }
        throw ActionException("Gave up waiting after $timeout s (${last.why}).")
    }

    private suspend fun tryStep(step: Step, ctx: Ctx): Value? {
        try {
            runSteps(step.blocks[OpIds.BLOCK_TRY].orEmpty(), ctx.deeper())
            return Value.Bool(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ControlSignal) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            ctx.detail("caught: $message")
            val error = Value.Dict(mapOf("message" to Value.Text(message)))
            runSteps(step.blocks[OpIds.BLOCK_CATCH].orEmpty(), ctx.deeper(mapOf("error" to error)))
            return Value.Bool(false)
        }
    }

    /** `name=value` per line, for "Run Flow" inputs. Blank lines and lines without "=" are ignored. */
    private fun parseInputs(text: String): Map<String, Value> {
        val out = LinkedHashMap<String, Value>()
        for (line in text.lines()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val name = line.substring(0, eq).trim()
            if (name.isNotEmpty()) out[name] = Value.Text(line.substring(eq + 1).trim())
        }
        return out
    }

    // ── data actions ────────────────────────────────────────────────────────────────────────

    private fun calculate(p: JsonObject, env: ActionEnv): Value {
        val source = env.render(p.str("expression"))
        val result = try {
            Expr.eval(source) { name -> env.lookup(name)?.asNumberOrNull() }
        } catch (e: ExprException) {
            throw ActionException(e.message ?: "That calculation isn't valid.")
        }
        val value = Value.Num(result)
        env.detail("$source = ${value.render()}")
        return value
    }

    private fun wholeNumber(text: String, what: String): Int =
        text.trim().toIntOrNull() ?: throw ActionException("$what must be a whole number.")

    private fun textTool(p: JsonObject, env: ActionEnv): Value {
        val input = env.render(p.str("input"))
        val a = env.render(p.str("a"))
        val b = env.render(p.str("b"))
        val result: Value = try {
            when (val op = p.str("operation", "trim")) {
                "substring" -> Value.Text(StringOps.substring(input, wholeNumber(a, "The start position"), b.trim().toIntOrNull()))
                "replace" -> Value.Text(StringOps.replace(input, a, b))
                "regex_replace" -> Value.Text(StringOps.regexReplace(input, a, b))
                "regex_find" -> Value.Items(StringOps.regexFind(input, a).map { Value.Text(it) })
                "matches" -> Value.Bool(StringOps.regexMatches(input, a))
                "split" -> Value.Items(StringOps.split(input, a).map { Value.Text(it) })
                "join" -> Value.Text(StringOps.join(loopEntries(input).map { it.second.render() }, a))
                "upper", "lower", "title" -> Value.Text(StringOps.changeCase(input, op))
                "trim" -> Value.Text(StringOps.trim(input))
                "pad" -> Value.Text(StringOps.pad(input, wholeNumber(a, "The width"), b.firstOrNull() ?: ' '))
                "length" -> Value.Num(input.length.toDouble())
                "contains" -> Value.Bool(input.contains(a, ignoreCase = true))
                else -> throw ActionException("Unknown text tool \"$op\".")
            }
        } catch (e: ExprException) {
            throw ActionException(e.message ?: "That text tool couldn't run.")
        }
        env.detail("→ ${result.render().take(120)}")
        return result
    }

    private fun readJson(p: JsonObject, env: ActionEnv): Value {
        val root = try {
            Value.parseJson(env.render(p.str("json")))
        } catch (e: IllegalArgumentException) {
            throw ActionException(e.message ?: "That isn't valid JSON.")
        }
        val path = p.str("path").trim()
        val value = if (path.isEmpty()) root else root.path(path) ?: throw ActionException("\"$path\" wasn't found in the JSON.")
        env.detail("→ ${value.render().take(120)}")
        return value
    }

    private fun formatDate(p: JsonObject, env: ActionEnv): Value {
        val millis = if (p.str("from", "now") == "timestamp") {
            env.render(p.str("timestamp")).trim().toLongOrNull() ?: throw ActionException("The timestamp must be a number of milliseconds.")
        } else {
            clock()
        }
        val pattern = p.str("pattern", "yyyy-MM-dd HH:mm")
        val text = try {
            DateTimeFormatter.ofPattern(pattern).format(Instant.ofEpochMilli(millis).atZone(zone()))
        } catch (e: IllegalArgumentException) {
            throw ActionException("That date layout isn't valid.")
        } catch (e: DateTimeException) {
            throw ActionException("That date layout can't be used here.")
        }
        env.detail("→ $text")
        return Value.Text(text)
    }

    private fun randomValue(p: JsonObject, env: ActionEnv): Value {
        val result = if (p.str("mode", "number") == "item") {
            val items = loopEntries(env.render(p.str("list")))
            if (items.isEmpty()) throw ActionException("The list is empty.")
            items[random.nextInt(items.size)].second
        } else {
            val min = p.int("min", 1).toLong()
            val max = p.int("max", 100).toLong()
            if (min > max) throw ActionException("\"From\" must not be higher than \"To\".")
            Value.Num(random.nextLong(min, max + 1).toDouble())
        }
        env.detail("→ ${result.render()}")
        return result
    }

    /** Numbers and true/false typed from text, so lists sort and compare properly. */
    private fun typedItem(text: String): Value {
        Value.parseNumber(text)?.let { return Value.Num(it) }
        return when (text.trim().lowercase()) {
            "true" -> Value.Bool(true)
            "false" -> Value.Bool(false)
            else -> Value.Text(text)
        }
    }

    private fun structured(store: ValueStore, name: String): Value? {
        val v = store.get(name) ?: return null
        if (v !is Value.Text) return v
        val t = v.value.trim()
        if (t.isEmpty()) return null
        if (!(t.startsWith("[") || t.startsWith("{"))) return v
        return try {
            Value.parseJson(t)
        } catch (e: IllegalArgumentException) {
            throw ActionException("Variable $name holds text that isn't valid JSON.")
        }
    }

    private fun listTool(p: JsonObject, env: ActionEnv): Value {
        val name = p.str("variable").trim()
        if (name.isEmpty()) throw ActionException("Variable name is empty.")
        val current = structured(env.values, name)
        val list: MutableList<Value> = when (current) {
            null -> mutableListOf()
            is Value.Items -> current.items.toMutableList()
            else -> throw ActionException("Variable $name holds ${current.typeName}, not a list.")
        }
        val value = env.render(p.str("value"))
        val index = p.int("index", 1)
        fun save(): Value {
            val v = Value.Items(list.toList())
            env.values.set(name, v)
            env.detail("$name = ${v.render().take(120)}")
            return v
        }
        fun item(): Int {
            if (index < 1 || index > list.size) throw ActionException("$name has ${list.size} item${if (list.size == 1) "" else "s"}, so there is no item $index.")
            return index - 1
        }
        return when (val op = p.str("operation", "add")) {
            "create", "clear" -> {
                list.clear()
                save()
            }
            "add" -> {
                list += typedItem(value)
                save()
            }
            "remove_at" -> {
                list.removeAt(item())
                save()
            }
            "remove_value" -> {
                val at = list.indexOfFirst { it.render().equals(value, ignoreCase = true) }
                if (at >= 0) list.removeAt(at)
                save()
            }
            "get" -> list[item()].also { env.detail("→ ${it.render()}") }
            "length" -> Value.Num(list.size.toDouble()).also { env.detail("→ ${it.render()}") }
            "contains" -> Value.Bool(list.any { it.render().equals(value, ignoreCase = true) }).also { env.detail("→ ${it.render()}") }
            "sort" -> {
                val numbers = list.mapNotNull { it.asNumberOrNull() }
                val sorted = if (numbers.size == list.size && list.none { it is Value.Bool }) {
                    list.sortedBy { it.asNumberOrNull() }
                } else {
                    list.sortedBy { it.render().lowercase() }
                }
                list.clear()
                list.addAll(sorted)
                save()
            }
            "reverse" -> {
                list.reverse()
                save()
            }
            "join" -> Value.Text(list.joinToString(value) { it.render() }).also { env.detail("→ ${it.render().take(120)}") }
            else -> throw ActionException("Unknown list operation \"$op\".")
        }
    }

    private fun dictTool(p: JsonObject, env: ActionEnv): Value {
        val name = p.str("variable").trim()
        if (name.isEmpty()) throw ActionException("Variable name is empty.")
        val current = structured(env.values, name)
        val map: MutableMap<String, Value> = when (current) {
            null -> LinkedHashMap()
            is Value.Dict -> LinkedHashMap(current.entries)
            else -> throw ActionException("Variable $name holds ${current.typeName}, not a dictionary.")
        }
        val key = env.render(p.str("key")).trim()
        val value = env.render(p.str("value"))
        fun save(): Value {
            val v = Value.Dict(LinkedHashMap(map))
            env.values.set(name, v)
            env.detail("$name = ${v.render().take(120)}")
            return v
        }
        fun needKey() {
            if (key.isEmpty()) throw ActionException("Give the key to use.")
        }
        return when (val op = p.str("operation", "set")) {
            "set" -> {
                needKey()
                map[key] = typedItem(value)
                save()
            }
            "get" -> {
                needKey()
                (map[key] ?: throw ActionException("There is no key \"$key\" in $name.")).also { env.detail("→ ${it.render()}") }
            }
            "remove" -> {
                needKey()
                map.remove(key)
                save()
            }
            "has" -> {
                needKey()
                Value.Bool(key in map).also { env.detail("→ ${it.render()}") }
            }
            "keys" -> Value.Items(map.keys.map { Value.Text(it) }).also { env.detail("→ ${it.render().take(120)}") }
            "length" -> Value.Num(map.size.toDouble()).also { env.detail("→ ${it.render()}") }
            "clear" -> {
                map.clear()
                save()
            }
            else -> throw ActionException("Unknown dictionary operation \"$op\".")
        }
    }

    private fun setLocalValue(p: JsonObject, env: ActionEnv): Value {
        val name = p.str("name").trim()
        if (name.isEmpty()) throw ActionException("Name is empty.")
        val raw = env.render(p.str("value"))
        val value: Value = when (p.str("valueType", "text")) {
            "number" -> Value.Num(Value.parseNumber(raw) ?: throw ActionException("\"$raw\" is not a number."))
            "boolean" -> Value.Bool(Value.Text(raw).asBoolean())
            "json" -> try {
                Value.parseJson(raw)
            } catch (e: IllegalArgumentException) {
                throw ActionException(e.message ?: "That isn't valid JSON.")
            }
            else -> Value.Text(raw)
        }
        env.setLocal(name, value)
        env.detail("$name = ${value.render().take(120)}")
        return value
    }

    private suspend fun flowEnable(p: JsonObject, env: ActionEnv): Value {
        val control = flowControl ?: throw ActionException("Turning Flows on and off isn't available here.")
        val id = p.str("flowId")
        if (id.isBlank()) throw ActionException("Choose a Flow.")
        val name = p.str("flowName").ifBlank { id.take(8) }
        val enable = when (p.str("action", "enable")) {
            "disable" -> false
            "toggle" -> !(flowInfo.isEnabled(id) ?: throw ActionException("Flow \"$name\" no longer exists."))
            else -> true
        }
        if (!control.setEnabled(id, enable)) throw ActionException("Flow \"$name\" no longer exists.")
        env.detail("\"$name\" is now ${if (enable) "on" else "off"}")
        return Value.Bool(enable)
    }

    private fun setVariable(p: JsonObject, env: ActionEnv): Value? {
        val name = p.str("name").trim()
        if (name.isEmpty()) throw ActionException("Variable name is empty.")
        val value = env.render(p.str("value"))
        val store = env.values
        val stored: Value? = when (p.enum("mode", VariableMode.SET)) {
            VariableMode.SET -> Value.Text(value).also { store.set(name, it) }
            VariableMode.APPEND -> Value.Text((store.get(name)?.render() ?: "") + value).also { store.set(name, it) }
            VariableMode.DELETE -> {
                store.remove(name)
                null
            }
            VariableMode.ADD, VariableMode.SUBTRACT, VariableMode.MULTIPLY -> {
                val mode = p.enum("mode", VariableMode.ADD)
                val verb = when (mode) {
                    VariableMode.ADD -> "added to"
                    VariableMode.SUBTRACT -> "subtracted from"
                    else -> "multiplied with"
                }
                val operand = Value.parseNumber(value)
                    ?: throw ActionException("\"$value\" is not a number, so it can't be $verb $name.")
                val existing = store.get(name)
                val current = if (existing == null) 0.0 else existing.asNumberOrNull()
                    ?: throw ActionException("Variable $name holds \"${existing.render()}\", which is not a number.")
                val result = when (mode) {
                    VariableMode.ADD -> current + operand
                    VariableMode.SUBTRACT -> current - operand
                    else -> current * operand
                }
                Value.Num(result).also { store.set(name, it) }
            }
        }
        env.detail(if (stored == null) "$name removed" else "$name = ${stored.render()}")
        return stored
    }

    private suspend fun runScript(source: String, ctx: Ctx) {
        val logs = ctx.logs
        val prefix = "  ".repeat(ctx.depth + 1)
        val values = ctx.state.values
        val host = object : ScriptHost {
            override fun log(message: String) {
                synchronized(logs) { logs += "$prefix[script] $message" }
            }

            override fun notify(title: String, message: String) = device.showNotification(title, message)
            override fun getVar(name: String): String? = values.get(name)?.render()
            override fun setVar(name: String, value: String) = values.set(name, Value.Text(value))
            override fun deleteVar(name: String) = values.remove(name)
            override fun batteryLevel(): Int = device.batteryLevel()
            override fun isCharging(): Boolean = device.isCharging()
            override fun nowMillis(): Long = clock()
            override fun toast(message: String) = device.showToast(message)
            override fun vibrate(durationMs: Int) = device.vibrate(durationMs.coerceIn(50, 5_000))
            override fun copy(text: String) = device.copyToClipboard(text)
            override fun speak(text: String) = device.speak(text)
            override fun isWifiConnected(): Boolean = device.isWifiConnected()
            override fun isScreenOn(): Boolean = device.isScreenOn()
            override fun ringer(): String = device.ringerMode().name.lowercase()
        }
        if (ctx.simulating) {
            ctx.detail("would run the script")
            return
        }
        val result = withContext(Dispatchers.Default) { scripts.run(source, host) }
        if (result is ScriptResult.Failure) throw ActionException(result.message)
    }

    // ── names available to {{placeholders}} ─────────────────────────────────────────────────

    private fun now() = Instant.ofEpochMilli(clock()).atZone(zone())
    private fun fmt(pattern: String): String = now().format(DateTimeFormatter.ofPattern(pattern))

    private fun lookupName(ctx: Ctx, key: String): Value? {
        ctx.locals[key]?.let { return it }
        ctx.state.results[key]?.let { return it }
        ctx.state.event?.values?.get(key)?.let { return it }
        builtin(ctx, key)?.let { return it }
        ctx.state.values.get(key)?.let { return it }
        // Device state such as {{battery.temp}} or {{network.type}}. Your own variables win over these.
        return if (StateCatalog[key] != null) state.read(key) else null
    }

    private fun builtin(ctx: Ctx, key: String): Value? = when (key) {
        "battery" -> Value.Num(device.batteryLevel().toDouble())
        "charging" -> Value.Bool(device.isCharging())
        "time" -> Value.Text(fmt("HH:mm"))
        "date" -> Value.Text(fmt("yyyy-MM-dd"))
        "datetime" -> Value.Text(fmt("yyyy-MM-dd HH:mm"))
        "weekday" -> Value.Text(fmt("EEEE"))
        "timestamp" -> Value.Num(clock().toDouble())
        "wifi" -> Value.Bool(device.isWifiConnected())
        "screen" -> Value.Bool(device.isScreenOn())
        "ringer" -> Value.Text(device.ringerMode().name.lowercase())
        "random" -> Value.Num((0..99).random().toDouble())
        // "automation" is the v0.0.1 name and keeps working in templates saved by older versions.
        "flow", "automation" -> Value.Text(ctx.flow.name)
        else -> null
    }
}
