package com.androx.app.core.engine

import com.androx.app.core.ConstraintGroup
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

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
}

/** One executable action. Return the step's result (saved to a variable when the step asks for it) or null. */
interface ActionExecutor {
    suspend fun run(params: JsonObject, env: ActionEnv): Value?
}

/** Builds an [ActionExecutor] from a lambda: `ActionExecutor { params, env -> … }`. */
fun ActionExecutor(block: suspend (JsonObject, ActionEnv) -> Value?): ActionExecutor = object : ActionExecutor {
    override suspend fun run(params: JsonObject, env: ActionEnv): Value? = block(params, env)
}

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
) {
    companion object {
        const val MAX_DEPTH = 4
        const val MAX_WAIT_SECONDS = 10
        const val DEFAULT_WAIT_BUDGET_MS = 30_000L
        const val MAX_REPEAT = 20
        const val MAX_STEPS = 1_000
        const val MAX_RETRIES = 5
        const val MAX_RETRY_DELAY_MS = 10_000L
    }

    private val constraints = ConstraintEvaluator(device, clock, zone)
    private val catalog get() = SpecCatalog.default

    /** Everything shared by one whole run, including nested Flows and loops. */
    private class RunState(
        val mode: RunMode,
        val values: ValueStore,
        val event: Event?,
        val waitBudgetMs: Long,
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

        fun deeper(extra: Map<String, Value> = emptyMap()) = Ctx(flow, logs, stack, locals + extra, depth + 1, state)
    }

    // ── public API ──────────────────────────────────────────────────────────────────────────

    /** Checks only the constraints against live values. Used by trigger gating and the Flow page's "Test constraints". */
    fun checkConstraints(group: ConstraintGroup?): CheckResult = constraints.evaluate(group, variables)

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
        val state = RunState(mode, values, event, budget)
        try {
            val limitMs = flow.runOptions.timeoutSeconds * 1_000L
            val body: suspend () -> String? = { runFlow(flow, logs, emptyList(), state) }
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
    private suspend fun runFlow(flow: Flow, logs: MutableList<String>, stack: List<String>, state: RunState): String? {
        if (flow.id in stack) throw ActionException("\"${flow.name}\" would call itself.")
        if (stack.size >= MAX_DEPTH) throw ActionException("Nested Flows are limited to $MAX_DEPTH levels.")

        val ctx = Ctx(flow, logs, stack + flow.id, emptyMap(), 0, state)
        val gate = flow.constraints
        if (gate != null && !gate.isEmpty) {
            val r = constraints.evaluate(gate, state.values)
            logs += "Condition: ${r.why}"
            if (!r.passed) return "Condition not met: ${r.why}"
        }
        when {
            flow.type == FlowType.CODE -> runScript(flow.script.orEmpty(), ctx)
            flow.type == FlowType.CANVAS && flow.wiring == WiringMode.FREE_FORM ->
                throw ActionException("Free-form Canvas Flows can't run yet.")
            else -> runSteps(flow.steps, ctx)
        }
        return null
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
            val r = constraints.evaluate(gate, state.values)
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
        OpIds.SET_VOLUME, OpIds.FLASHLIGHT, OpIds.RUN_SCRIPT,
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
        putAll(extraActions)
    }

    private suspend fun dispatch(step: Step, ctx: Ctx): Value? {
        val typeId = step.op.typeId
        val p = step.op.params
        return when (typeId) {
            OpIds.IF -> {
                val condition = FlowMigration.ifCondition(step)
                val r = constraints.evaluate(condition, ctx.state.values)
                ctx.detail("if ${r.why} → ${if (r.passed) "then" else "else"}")
                runSteps(step.blocks[if (r.passed) OpIds.BLOCK_THEN else OpIds.BLOCK_ELSE].orEmpty(), ctx.deeper())
                Value.Bool(r.passed)
            }
            OpIds.REPEAT -> {
                val times = p.int("times", 3).coerceIn(1, MAX_REPEAT)
                for (round in 1..times) {
                    currentCoroutineContext().ensureActive()
                    ctx.detail("round $round of $times")
                    runSteps(step.blocks[OpIds.BLOCK_BODY].orEmpty(), ctx.deeper(mapOf("index" to Value.Num(round.toDouble()))))
                }
                null
            }
            OpIds.RUN_FLOW -> {
                val id = p.str("flowId")
                val name = p.str("flowName")
                val target = findFlow(id) ?: throw ActionException("Flow \"${name.ifBlank { id }}\" no longer exists.")
                if (target.inTrash) throw ActionException("\"${target.name}\" is in Trash.")
                val skipped = runFlow(target, ctx.logs, ctx.stack, ctx.state)
                ctx.detail(if (skipped == null) "ran \"${target.name}\"" else "skipped \"${target.name}\": $skipped")
                null
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
        return ctx.state.values.get(key)
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
