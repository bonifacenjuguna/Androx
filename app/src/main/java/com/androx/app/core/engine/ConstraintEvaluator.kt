package com.androx.app.core.engine

import com.androx.app.core.Check
import com.androx.app.core.CheckIds
import com.androx.app.core.CompareOp
import com.androx.app.core.Compare
import com.androx.app.core.ConstraintGroup
import com.androx.app.core.Event
import com.androx.app.core.ExprException
import com.androx.app.core.Value
import com.androx.app.core.long
import com.androx.app.core.state.DeviceStateReader
import com.androx.app.core.state.LegacyStateReader
import com.androx.app.core.state.StateCatalog
import com.androx.app.core.state.StateType
import com.androx.app.model.RunSource
import com.androx.app.core.LogicOp
import com.androx.app.core.bool
import com.androx.app.core.enum
import com.androx.app.core.int
import com.androx.app.core.intList
import com.androx.app.core.str
import com.androx.app.engine.ActionException
import com.androx.app.engine.DeviceBridge
import com.androx.app.model.Comparison
import com.androx.app.model.RingerMode
import com.androx.app.model.Templates
import com.androx.app.model.describeDays
import com.androx.app.model.formatClock
import com.androx.app.model.ringerWord
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Result of checking constraints: whether they pass and a plain-English reason that goes into the run log. */
data class CheckResult(val passed: Boolean, val why: String)

/** What a rule may need to know about the run it gates: how to fill `{{names}}`, and what started it. */
data class CheckContext(
    val render: (String) -> String = { it },
    val source: RunSource? = null,
    val event: Event? = null,
) {
    companion object {
        val NONE = CheckContext()
    }
}

/**
 * Evaluates nested All / Any / None constraint groups with per-rule "is not" (spec §17.1). The reason text reads like
 * v0.0.1's ("battery is 12% (needs < 20%)") so existing habits and screenshots still make sense.
 */
class ConstraintEvaluator(
    private val device: DeviceBridge,
    private val clock: () -> Long,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val state: DeviceStateReader = LegacyStateReader(device),
    private val flows: FlowInfo = NoFlowInfo,
) {
    /** A null or empty group always passes. */
    fun evaluate(group: ConstraintGroup?, values: ValueStore, cx: CheckContext = CheckContext.NONE): CheckResult {
        if (group == null) return CheckResult(true, "no rules")
        return group(group, values, cx)
    }

    private fun group(g: ConstraintGroup, values: ValueStore, cx: CheckContext): CheckResult {
        val parts = ArrayList<CheckResult>()
        for (rule in g.rules) parts += rule(rule, values, cx)
        for (nested in g.groups) {
            val r = group(nested, values, cx)
            parts += if (nested.rules.size + nested.groups.size > 1) r.copy(why = "(${r.why})") else r
        }
        if (parts.isEmpty()) return CheckResult(true, "no rules")
        return when (g.op) {
            LogicOp.ALL -> CheckResult(parts.all { it.passed }, parts.joinToString(" and ") { it.why })
            LogicOp.ANY -> CheckResult(parts.any { it.passed }, parts.joinToString(" or ") { it.why })
            LogicOp.NONE -> CheckResult(parts.none { it.passed }, "none of: " + parts.joinToString(" or ") { it.why })
        }
    }

    private fun rule(check: Check, values: ValueStore, cx: CheckContext): CheckResult {
        val r = leaf(check, values, cx)
        return if (check.negate) CheckResult(!r.passed, "not (${r.why})") else r
    }

    private fun now() = Instant.ofEpochMilli(clock()).atZone(zone())

    private fun yn(flag: Boolean, yes: String, no: String) = if (flag) yes else no

    private fun leaf(c: Check, values: ValueStore, cx: CheckContext): CheckResult {
        val p = c.params
        if (c.typeId.startsWith(CheckIds.STATE_PREFIX)) return stateLeaf(c, cx)
        return when (c.typeId) {
            CheckIds.BATTERY_LEVEL -> {
                val op = p.enum("op", Comparison.LT)
                val need = p.int("percent", 20)
                val level = device.batteryLevel()
                CheckResult(op.test(level.compareTo(need)), "battery is $level% (needs ${op.symbol} $need%)")
            }
            CheckIds.CHARGING -> {
                val want = p.bool("charging", true)
                val actual = device.isCharging()
                CheckResult(
                    actual == want,
                    "device is ${yn(actual, "charging", "not charging")} (needs ${yn(want, "charging", "not charging")})",
                )
            }
            CheckIds.VARIABLE -> {
                val name = p.str("name")
                val op = p.enum("op", Comparison.EQ)
                val rhs = p.str("value")
                val current = values.get(name)
                val left = current?.render() ?: ""
                CheckResult(
                    op.test(Templates.compare(left, rhs)),
                    "$name is ${current?.render() ?: "not set"} (needs ${op.symbol} $rhs)",
                )
            }
            CheckIds.TIME_WINDOW -> {
                val t = now().toLocalTime()
                val from = LocalTime.of(p.int("fromHour", 22).coerceIn(0, 23), p.int("fromMinute", 0).coerceIn(0, 59))
                val to = LocalTime.of(p.int("toHour", 7).coerceIn(0, 23), p.int("toMinute", 0).coerceIn(0, 59))
                val inside = when {
                    from == to -> true
                    from < to -> t >= from && t < to
                    else -> t >= from || t < to
                }
                CheckResult(
                    inside,
                    "time is ${formatClock(t.hour, t.minute)} (needs between ${formatClock(from.hour, from.minute)} and ${formatClock(to.hour, to.minute)})",
                )
            }
            CheckIds.DAYS_OF_WEEK -> {
                val day = now().dayOfWeek.value
                val allowed = p.intList("days").filter { it in 1..7 }
                CheckResult(
                    allowed.isEmpty() || day in allowed,
                    "today is ${describeDays(listOf(day))} (needs ${describeDays(allowed)})",
                )
            }
            CheckIds.WIFI_CONNECTED -> {
                val want = p.bool("connected", true)
                val actual = device.isWifiConnected()
                CheckResult(
                    actual == want,
                    "Wi-Fi is ${yn(actual, "connected", "not connected")} (needs ${yn(want, "connected", "not connected")})",
                )
            }
            CheckIds.SCREEN_ON -> {
                val want = p.bool("on", true)
                val actual = device.isScreenOn()
                CheckResult(actual == want, "screen is ${yn(actual, "on", "off")} (needs ${yn(want, "on", "off")})")
            }
            CheckIds.RINGER_MODE -> {
                val want = p.enum("mode", RingerMode.SILENT)
                val actual = device.ringerMode()
                CheckResult(
                    actual == want,
                    "ringer is ${ringerWord(actual).lowercase()} (needs ${ringerWord(want).lowercase()})",
                )
            }
            CheckIds.BATTERY_SAVER -> {
                val want = p.bool("on", true)
                val actual = device.isPowerSaver()
                CheckResult(actual == want, "Battery Saver is ${yn(actual, "on", "off")} (needs ${yn(want, "on", "off")})")
            }
            CheckIds.AIRPLANE_MODE -> {
                val want = p.bool("on", true)
                val actual = device.isAirplaneMode()
                CheckResult(actual == want, "Airplane mode is ${yn(actual, "on", "off")} (needs ${yn(want, "on", "off")})")
            }
            CheckIds.COMPARE -> {
                val left = cx.render(p.str("left"))
                val op = p.enum("op", CompareOp.EQ)
                val right = cx.render(p.str("right"))
                val right2 = cx.render(p.str("right2"))
                val ok = try {
                    Compare.test(Value.Text(left), op, right, right2)
                } catch (e: ExprException) {
                    throw ActionException(e.message ?: "That comparison isn't valid.")
                }
                CheckResult(ok, "\"$left\" ${if (ok) "is" else "is not"} ${Compare.describe(op, right, right2)}")
            }
            CheckIds.FLOW_STATE -> flowState(p)
            CheckIds.TRIGGERED_BY -> triggeredBy(p, cx)
            CheckIds.DATE_RANGE -> {
                val today = now().toLocalDate()
                val from = p.long("fromMillis", 0L).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone()).toLocalDate() }
                val to = p.long("toMillis", 0L).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone()).toLocalDate() }
                val ok = (from == null || !today.isBefore(from)) && (to == null || !today.isAfter(to))
                CheckResult(ok, "today is $today (needs ${from ?: "any date"} to ${to ?: "any date"})")
            }
            CheckIds.DAY_OF_MONTH -> {
                val date = now().toLocalDate()
                val days = p.intList("days")
                val last = p.bool("last") && date.dayOfMonth == date.lengthOfMonth()
                CheckResult(date.dayOfMonth in days || last, "day of month is ${date.dayOfMonth} (needs ${days.sorted().joinToString(", ")}${if (p.bool("last")) ", last" else ""})")
            }
            CheckIds.MONTH_OF_YEAR -> {
                val month = now().monthValue
                val months = p.intList("months")
                CheckResult(month in months, "month is $month (needs ${months.sorted().joinToString(", ")})")
            }
            else -> throw ActionException("The check \"${c.typeId}\" isn't available in this version.")
        }
    }

    // ── device state ────────────────────────────────────────────────────────────────────────

    private fun stateLeaf(c: Check, cx: CheckContext): CheckResult {
        val id = c.typeId.removePrefix(CheckIds.STATE_PREFIX)
        val provider = StateCatalog[id] ?: throw ActionException("The check \"${c.typeId}\" isn't available in this version.")
        val actual = state.read(id) ?: return CheckResult(false, "${provider.name.lowercase()} can't be read right now")
        val p = c.params
        val shown = provider.display(actual)
        return when (provider.type) {
            StateType.BOOLEAN -> {
                val want = p.bool("is", true)
                CheckResult(actual.asBoolean() == want, "${provider.name.lowercase()}: $shown (needs ${if (want) provider.yes else provider.no})")
            }
            else -> {
                val op = p.enum("op", if (provider.type == StateType.NUMBER) CompareOp.LT else CompareOp.EQ)
                val rhs = p.str("value")
                val rhs2 = p.str("value2")
                val ok = try {
                    Compare.test(actual, op, rhs, rhs2)
                } catch (e: ExprException) {
                    throw ActionException(e.message ?: "That comparison isn't valid.")
                }
                val needs = if (provider.type == StateType.CHOICE) {
                    "${op.symbol} ${provider.choices.firstOrNull { it.value == rhs }?.label ?: rhs}"
                } else {
                    Compare.describe(op, rhs, rhs2, provider.unit)
                }
                CheckResult(ok, "${provider.name.lowercase()} is $shown (needs $needs)")
            }
        }
    }

    private fun flowState(p: kotlinx.serialization.json.JsonObject): CheckResult {
        val id = p.str("flowId")
        if (id.isBlank()) throw ActionException("Choose a Flow for the \"Flow state\" check.")
        val name = p.str("flowName").ifBlank { id.take(8) }
        return when (p.str("what", "running")) {
            "enabled" -> {
                val on = flows.isEnabled(id) ?: throw ActionException("Flow \"$name\" no longer exists.")
                CheckResult(on, "$name is ${if (on) "turned on" else "turned off"} (needs turned on)")
            }
            "ran_recently" -> {
                val minutes = p.int("minutes", 60).coerceAtLeast(1)
                val last = flows.lastRunAt(id)
                val ok = last != null && clock() - last <= minutes * 60_000L
                CheckResult(ok, "$name ${if (ok) "ran" else "didn't run"} in the last $minutes min")
            }
            "runs_today" -> {
                val op = p.enum("op", CompareOp.GTE)
                val need = p.int("count", 1)
                val start = now().toLocalDate().atStartOfDay(zone()).toInstant().toEpochMilli()
                val n = flows.runsSince(id, start)
                CheckResult(Compare.test(Value.Num(n.toDouble()), op, need.toString()), "$name ran $n time${if (n == 1) "" else "s"} today (needs ${Compare.describe(op, need.toString())})")
            }
            else -> {
                val running = flows.isRunning(id)
                CheckResult(running, "$name is ${if (running) "running" else "not running"} (needs running)")
            }
        }
    }

    private fun triggeredBy(p: kotlinx.serialization.json.JsonObject, cx: CheckContext): CheckResult {
        val actual = when {
            cx.event != null -> "event"
            cx.source == RunSource.SCHEDULE -> "timer"
            cx.source == RunSource.BOOT -> "boot"
            else -> "manual"
        }
        val want = p.str("by", "manual")
        val wantType = p.str("eventType").trim()
        val typeOk = want != "event" || wantType.isEmpty() || cx.event?.typeId.equals(wantType, ignoreCase = true)
        val label = if (actual == "event") "event ${cx.event?.typeId.orEmpty()}".trim() else actual
        return CheckResult(actual == want && typeOk, "started by $label (needs $want${if (want == "event" && wantType.isNotEmpty()) " $wantType" else ""})")
    }
}
