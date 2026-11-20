package com.androx.app.engine

import com.androx.app.model.Action
import com.androx.app.model.Automation
import com.androx.app.model.Condition
import com.androx.app.model.ExecutionRecord
import com.androx.app.model.ExecutionStatus
import com.androx.app.model.FlashlightMode
import com.androx.app.model.RingerMode
import com.androx.app.model.RunSource
import com.androx.app.model.Templates
import com.androx.app.model.VariableMode
import com.androx.app.model.VolumeStream
import com.androx.app.model.describeDays
import com.androx.app.model.formatClock
import com.androx.app.model.label
import com.androx.app.model.ringerWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Thrown when Android (or the user's configuration) prevents an action. Always becomes a failed run. */
class ActionException(message: String) : Exception(message)

private fun unsupported(what: String): Nothing = throw ActionException("$what isn't available here.")

/**
 * Everything the engine needs from the device. Implemented on Android by AndroidDeviceBridge.
 * The newer members have defaults so small fakes (tests) only need the original three.
 */
interface DeviceBridge {
    fun batteryLevel(): Int
    fun isCharging(): Boolean

    /** Must throw [ActionException] if the notification could not be posted. */
    fun showNotification(title: String, message: String)

    fun isWifiConnected(): Boolean = false
    fun isScreenOn(): Boolean = true
    fun ringerMode(): RingerMode = RingerMode.NORMAL
    fun isPowerSaver(): Boolean = false
    fun isAirplaneMode(): Boolean = false

    fun vibrate(durationMs: Int): Unit = unsupported("Vibration")
    fun showToast(message: String): Unit = unsupported("Toasts")
    fun copyToClipboard(text: String): Unit = unsupported("The clipboard")
    fun speak(text: String): Unit = unsupported("Text to speech")

    /** Returns the new state of the flashlight (true = on). */
    fun setFlashlight(mode: FlashlightMode): Boolean = unsupported("The flashlight")
    fun setVolume(stream: VolumeStream, percent: Int): Unit = unsupported("Volume control")
    fun playNotificationSound(): Unit = unsupported("Sound")
}

interface VariableStore {
    fun get(name: String): String?
    fun set(name: String, value: String)
    fun remove(name: String)
}

/**
 * Runs an automation: checks its condition, then executes its actions in order.
 * Pure Kotlin (no Android types) so it can be unit-tested on the JVM.
 */
class ExecutionEngine(
    private val device: DeviceBridge,
    private val variables: VariableStore,
    private val findAutomation: (String) -> Automation?,
    private val scripts: ScriptRunner = RhinoScriptRunner(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val MAX_DEPTH = 4
        const val MAX_WAIT_SECONDS = 10
        const val MAX_TOTAL_WAIT_MILLIS = 30_000L
        const val MAX_REPEAT = 20
    }

    /** Mutable bookkeeping shared by one whole run (including nested automations and loops). */
    private class RunState {
        var waitedMillis = 0L
    }

    /** What an action needs to know about where it is running. */
    private data class Ctx(
        val owner: Automation,
        val logs: MutableList<String>,
        val stack: List<String>,
        val locals: Map<String, String>,
        val depth: Int,
        val state: RunState,
    ) {
        fun detail(text: String) {
            logs += "  ".repeat(depth + 1) + text
        }

        fun deeper(extra: Map<String, String> = emptyMap()) = copy(depth = depth + 1, locals = locals + extra)
    }

    suspend fun execute(automation: Automation, source: RunSource): ExecutionRecord {
        val started = clock()
        val logs = mutableListOf<String>()
        var status = ExecutionStatus.SUCCESS
        var message: String? = null
        try {
            val skipped = runInternal(automation, logs, emptyList(), RunState())
            if (skipped != null) {
                status = ExecutionStatus.SKIPPED
                message = skipped
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ActionException) {
            status = ExecutionStatus.FAILED
            message = e.message
        } catch (e: Exception) {
            status = ExecutionStatus.FAILED
            message = e.message ?: e.javaClass.simpleName
        }
        return ExecutionRecord(
            id = UUID.randomUUID().toString(),
            automationId = automation.id,
            automationName = automation.name,
            startedAt = started,
            durationMs = (clock() - started).coerceAtLeast(0),
            status = status,
            source = source,
            message = message,
            logs = logs,
        )
    }

    /** Returns a reason if the run was skipped, null if it ran. Throws on failure. */
    private suspend fun runInternal(a: Automation, logs: MutableList<String>, stack: List<String>, state: RunState): String? {
        if (a.id in stack) throw ActionException("\"${a.name}\" would call itself.")
        if (stack.size >= MAX_DEPTH) throw ActionException("Nested automations are limited to $MAX_DEPTH levels.")

        val condition = a.condition
        if (condition != null) {
            val (passed, why) = evaluate(condition)
            logs += "Condition: $why"
            if (!passed) return "Condition not met: $why"
        }
        runActions(a.actions, Ctx(a, logs, stack + a.id, emptyMap(), 0, state))
        return null
    }

    private suspend fun runActions(actions: List<Action>, ctx: Ctx) {
        for ((index, action) in actions.withIndex()) {
            currentCoroutineContext().ensureActive()
            ctx.logs += "  ".repeat(ctx.depth) + "Action ${index + 1}: ${action.label()}"
            perform(action, ctx)
        }
    }

    // ------------------------------------------------------------------ conditions

    private fun now(): ZonedDateTime = Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault())

    private fun evaluate(condition: Condition): Pair<Boolean, String> = when (condition) {
        is Condition.BatteryLevel -> {
            val level = device.batteryLevel()
            condition.op.test(level.compareTo(condition.percent)) to
                "battery is $level% (needs ${condition.op.symbol} ${condition.percent}%)"
        }
        is Condition.Charging -> {
            val now = device.isCharging()
            (now == condition.charging) to
                (if (now) "device is charging" else "device is not charging") +
                " (needs ${if (condition.charging) "charging" else "not charging"})"
        }
        is Condition.Variable -> {
            val current = variables.get(condition.name)
            val left = current ?: ""
            val shown = current ?: "not set"
            val rhs = condition.value
            condition.op.test(Templates.compare(left, rhs)) to
                "${condition.name} is $shown (needs ${condition.op.symbol} $rhs)"
        }
        is Condition.TimeWindow -> {
            val nowTime = now().toLocalTime()
            val from = LocalTime.of(condition.fromHour.coerceIn(0, 23), condition.fromMinute.coerceIn(0, 59))
            val to = LocalTime.of(condition.toHour.coerceIn(0, 23), condition.toMinute.coerceIn(0, 59))
            val inside = when {
                from == to -> true
                from < to -> nowTime >= from && nowTime < to
                else -> nowTime >= from || nowTime < to
            }
            inside to "time is ${formatClock(nowTime.hour, nowTime.minute)} " +
                "(needs between ${formatClock(from.hour, from.minute)} and ${formatClock(to.hour, to.minute)})"
        }
        is Condition.DaysOfWeek -> {
            val day = now().dayOfWeek.value
            val allowed = condition.days.filter { it in 1..7 }
            (allowed.isEmpty() || day in allowed) to
                "today is ${describeDays(listOf(day))} (needs ${describeDays(allowed)})"
        }
        is Condition.WifiConnected -> {
            val actual = device.isWifiConnected()
            (actual == condition.connected) to
                "Wi-Fi is ${if (actual) "connected" else "not connected"} (needs ${if (condition.connected) "connected" else "not connected"})"
        }
        is Condition.ScreenOn -> {
            val actual = device.isScreenOn()
            (actual == condition.on) to
                "screen is ${if (actual) "on" else "off"} (needs ${if (condition.on) "on" else "off"})"
        }
        is Condition.Ringer -> {
            val actual = device.ringerMode()
            (actual == condition.mode) to
                "ringer is ${ringerWord(actual).lowercase()} (needs ${ringerWord(condition.mode).lowercase()})"
        }
        is Condition.PowerSaver -> {
            val actual = device.isPowerSaver()
            (actual == condition.on) to
                "Battery Saver is ${if (actual) "on" else "off"} (needs ${if (condition.on) "on" else "off"})"
        }
        is Condition.AirplaneMode -> {
            val actual = device.isAirplaneMode()
            (actual == condition.on) to
                "Airplane mode is ${if (actual) "on" else "off"} (needs ${if (condition.on) "on" else "off"})"
        }
        is Condition.MatchAll -> {
            val results = condition.items.map { evaluate(it) }
            results.all { it.first } to results.joinToString(" and ") { it.second }.ifBlank { "no rules" }
        }
        is Condition.MatchAny -> {
            val results = condition.items.map { evaluate(it) }
            (results.isEmpty() || results.any { it.first }) to results.joinToString(" or ") { it.second }.ifBlank { "no rules" }
        }
    }

    // ------------------------------------------------------------------ actions

    private suspend fun perform(action: Action, ctx: Ctx) {
        val render = { text: String -> Templates.render(text, resolver(ctx)) }
        when (action) {
            is Action.ShowNotification -> device.showNotification(render(action.title), render(action.message))
            is Action.SetVariable -> setVariable(action, ctx, render)
            is Action.RunAutomation -> {
                val target = findAutomation(action.automationId)
                    ?: throw ActionException(
                        "Automation \"${action.automationName.ifBlank { action.automationId }}\" no longer exists."
                    )
                // Nested logs are appended to the same list, starting one level in.
                val skipped = runInternal(target, ctx.logs, ctx.stack, ctx.state)
                ctx.detail(if (skipped == null) "ran \"${target.name}\"" else "skipped \"${target.name}\": $skipped")
            }
            is Action.WriteLog -> ctx.detail(render(action.message))
            is Action.RunScript -> runScript(action.source, ctx)
            is Action.Vibrate -> device.vibrate(action.durationMs.coerceIn(50, 5_000))
            is Action.ShowToast -> device.showToast(render(action.message))
            is Action.CopyToClipboard -> device.copyToClipboard(render(action.text))
            is Action.Speak -> device.speak(render(action.text))
            is Action.Wait -> {
                val seconds = action.seconds.coerceIn(1, MAX_WAIT_SECONDS)
                val millis = seconds * 1_000L
                if (ctx.state.waitedMillis + millis > MAX_TOTAL_WAIT_MILLIS) {
                    throw ActionException("A run can wait at most ${MAX_TOTAL_WAIT_MILLIS / 1000} seconds in total.")
                }
                ctx.state.waitedMillis += millis
                ctx.detail("waiting $seconds s")
                delay(millis)
            }
            is Action.Flashlight -> {
                val on = device.setFlashlight(action.mode)
                ctx.detail("flashlight is ${if (on) "on" else "off"}")
            }
            is Action.SetVolume -> device.setVolume(action.stream, action.percent.coerceIn(0, 100))
            Action.PlaySound -> device.playNotificationSound()
            is Action.IfThen -> {
                val (passed, why) = evaluate(action.condition)
                ctx.detail("if $why → ${if (passed) "then" else "else"}")
                runActions(if (passed) action.then else action.otherwise, ctx.deeper())
            }
            is Action.Repeat -> {
                val times = action.times.coerceIn(1, MAX_REPEAT)
                for (round in 1..times) {
                    currentCoroutineContext().ensureActive()
                    ctx.detail("round $round of $times")
                    runActions(action.actions, ctx.deeper(mapOf("index" to round.toString())))
                }
            }
        }
    }

    private fun setVariable(action: Action.SetVariable, ctx: Ctx, render: (String) -> String) {
        val name = action.name.trim()
        if (name.isEmpty()) throw ActionException("Variable name is empty.")
        val value = render(action.value)
        when (action.mode) {
            VariableMode.SET -> variables.set(name, value)
            VariableMode.APPEND -> variables.set(name, (variables.get(name) ?: "") + value)
            VariableMode.DELETE -> variables.remove(name)
            VariableMode.ADD, VariableMode.SUBTRACT, VariableMode.MULTIPLY -> {
                val verb = when (action.mode) {
                    VariableMode.ADD -> "added to"
                    VariableMode.SUBTRACT -> "subtracted from"
                    else -> "multiplied with"
                }
                val operand = value.trim().toDoubleOrNull()
                    ?: throw ActionException("\"$value\" is not a number, so it can't be $verb $name.")
                val currentText = variables.get(name)
                val current = if (currentText == null) 0.0 else currentText.trim().toDoubleOrNull()
                    ?: throw ActionException("Variable $name holds \"$currentText\", which is not a number.")
                val result = when (action.mode) {
                    VariableMode.ADD -> current + operand
                    VariableMode.SUBTRACT -> current - operand
                    else -> current * operand
                }
                variables.set(name, Templates.formatNumber(result))
            }
        }
        ctx.detail(if (action.mode == VariableMode.DELETE) "$name removed" else "$name = ${variables.get(name)}")
    }

    private fun resolver(ctx: Ctx): (String) -> String? = { key ->
        ctx.locals[key] ?: when (key) {
            "battery" -> device.batteryLevel().toString()
            "charging" -> device.isCharging().toString()
            "time" -> formatNow("HH:mm")
            "date" -> formatNow("yyyy-MM-dd")
            "datetime" -> formatNow("yyyy-MM-dd HH:mm")
            "weekday" -> formatNow("EEEE")
            "timestamp" -> clock().toString()
            "wifi" -> device.isWifiConnected().toString()
            "screen" -> device.isScreenOn().toString()
            "ringer" -> device.ringerMode().name.lowercase()
            "random" -> (0..99).random().toString()
            "automation" -> ctx.owner.name
            else -> variables.get(key)
        }
    }

    private fun formatNow(pattern: String): String = now().format(DateTimeFormatter.ofPattern(pattern))

    private suspend fun runScript(source: String, ctx: Ctx) {
        val logs = ctx.logs
        val prefix = "  ".repeat(ctx.depth + 1)
        val host = object : ScriptHost {
            override fun log(message: String) {
                synchronized(logs) { logs += "$prefix[script] $message" }
            }

            override fun notify(title: String, message: String) = device.showNotification(title, message)
            override fun getVar(name: String): String? = variables.get(name)
            override fun setVar(name: String, value: String) = variables.set(name, value)
            override fun deleteVar(name: String) = variables.remove(name)
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
        val result = withContext(Dispatchers.Default) { scripts.run(source, host) }
        if (result is ScriptResult.Failure) throw ActionException(result.message)
    }
}
