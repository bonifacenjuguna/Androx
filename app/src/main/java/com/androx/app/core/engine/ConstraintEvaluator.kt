package com.androx.app.core.engine

import com.androx.app.core.Check
import com.androx.app.core.CheckIds
import com.androx.app.core.ConstraintGroup
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

/**
 * Evaluates nested All / Any / None constraint groups with per-rule "is not" (spec §17.1). The reason text reads like
 * v0.0.1's ("battery is 12% (needs < 20%)") so existing habits and screenshots still make sense.
 */
class ConstraintEvaluator(
    private val device: DeviceBridge,
    private val clock: () -> Long,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /** A null or empty group always passes. */
    fun evaluate(group: ConstraintGroup?, values: ValueStore): CheckResult {
        if (group == null) return CheckResult(true, "no rules")
        return group(group, values)
    }

    private fun group(g: ConstraintGroup, values: ValueStore): CheckResult {
        val parts = ArrayList<CheckResult>()
        for (rule in g.rules) parts += rule(rule, values)
        for (nested in g.groups) {
            val r = group(nested, values)
            parts += if (nested.rules.size + nested.groups.size > 1) r.copy(why = "(${r.why})") else r
        }
        if (parts.isEmpty()) return CheckResult(true, "no rules")
        return when (g.op) {
            LogicOp.ALL -> CheckResult(parts.all { it.passed }, parts.joinToString(" and ") { it.why })
            LogicOp.ANY -> CheckResult(parts.any { it.passed }, parts.joinToString(" or ") { it.why })
            LogicOp.NONE -> CheckResult(parts.none { it.passed }, "none of: " + parts.joinToString(" or ") { it.why })
        }
    }

    private fun rule(check: Check, values: ValueStore): CheckResult {
        val r = leaf(check, values)
        return if (check.negate) CheckResult(!r.passed, "not (${r.why})") else r
    }

    private fun now() = Instant.ofEpochMilli(clock()).atZone(zone())

    private fun yn(flag: Boolean, yes: String, no: String) = if (flag) yes else no

    private fun leaf(c: Check, values: ValueStore): CheckResult {
        val p = c.params
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
            else -> throw ActionException("The check \"${c.typeId}\" isn't available in this version.")
        }
    }
}
