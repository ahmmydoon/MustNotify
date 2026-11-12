package com.salahoard.mustnotify.domain

import kotlin.math.abs
import kotlin.math.round
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Slider definitions
// ---------------------------------------------------------------------------

enum class ParamId { BATTERY_VOLTAGE, BATTERY_CURRENT, PV_POWER, LOAD_POWER }

/** Everything the UI needs to draw one range-slider pair, and the engine needs to word its messages. */
data class ParamSpec(
    val id: ParamId,
    val label: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val step: Double,
    val decimals: Int,
    val hint: String? = null,
)

object ParamSpecs {
    const val POWER_OVERSHOOT = 1.10 // watt sliders may exceed rated power by 10 %
    const val MAX_BATTERY_CURRENT_A = 80.0
    const val FALLBACK_RATED_POWER_W = 2000.0

    /** In UI order: voltage, current, PV, load. The cumulative discharge rule is separate (see [DischargeRule]). */
    fun forRatedPower(ratedPowerW: Double?): List<ParamSpec> {
        val rated = ratedPowerW?.takeIf { it > 0 } ?: FALLBACK_RATED_POWER_W
        val powerMax = round(rated * POWER_OVERSHOOT / 10.0) * 10.0
        return listOf(
            ParamSpec(ParamId.BATTERY_VOLTAGE, "Battery voltage", "V", 9.0, 15.0, 0.1, 1),
            ParamSpec(ParamId.BATTERY_CURRENT, "Battery current", "A", 0.0, MAX_BATTERY_CURRENT_A, 1.0, 0),
            ParamSpec(ParamId.PV_POWER, "Solar power", "W", 0.0, powerMax, 10.0, 0),
            ParamSpec(ParamId.LOAD_POWER, "Load", "W", 0.0, powerMax, 10.0, 0),
        )
    }
}

// ---------------------------------------------------------------------------
// Range rules (non-accumulative values)
// ---------------------------------------------------------------------------

enum class RangeMode { NOTIFY_ON_ENTER, NOTIFY_ON_EXIT }

@Serializable
data class RangeRule(
    val enabled: Boolean,
    val low: Double,
    val high: Double,
    val mode: RangeMode,
) {
    /** Two sliders can cross each other; this makes low <= high. */
    fun normalized(): RangeRule = if (low <= high) this else copy(low = high, high = low)

    fun contains(value: Double): Boolean = normalized().let { value >= it.low && value <= it.high }

    /** Stable across process restarts (enum/data-class hashCodes are not). */
    fun key(): Int = normalized().let { "${it.enabled}|${it.low}|${it.high}|${it.mode.name}" }.hashCode()
}

@Serializable
data class RangeState(
    val inRange: Boolean? = null,
    val ruleKey: Int = 0,
    val lastNotifiedAt: Long? = null,
)

data class Alert(val title: String, val message: String)

/** Minimum time between two alerts for the same parameter, so a value hovering at a limit can't spam you. */
const val DEFAULT_MIN_ALERT_GAP_SECONDS = 15 * 60L

/**
 * Pure transition logic. Call once per poll per parameter and persist the returned state.
 *
 * - The first sample, a disabled rule, or a rule that was just edited is learned silently
 *   (moving a slider must never fire an alert by itself).
 * - Alerts fire only on a transition into (ENTER) or out of (EXIT) the range, never repeatedly.
 */
fun evaluateRange(
    spec: ParamSpec,
    rule: RangeRule,
    state: RangeState,
    value: Double,
    nowEpochSeconds: Long,
    minGapSeconds: Long = DEFAULT_MIN_ALERT_GAP_SECONDS,
): Pair<RangeState, Alert?> {
    val r = rule.normalized()
    val key = r.key()
    val inRange = r.contains(value)

    if (!r.enabled) return RangeState(null, key, state.lastNotifiedAt) to null
    if (state.inRange == null || state.ruleKey != key) return state.copy(inRange = inRange, ruleKey = key) to null
    if (state.inRange == inRange) return state to null

    val next = state.copy(inRange = inRange)
    val wantsAlert = when (r.mode) {
        RangeMode.NOTIFY_ON_ENTER -> inRange
        RangeMode.NOTIFY_ON_EXIT -> !inRange
    }
    if (!wantsAlert) return next to null
    val last = state.lastNotifiedAt
    if (last != null && nowEpochSeconds - last < minGapSeconds) return next to null

    return next.copy(lastNotifiedAt = nowEpochSeconds) to buildRangeAlert(spec, r, inRange, value)
}

private fun buildRangeAlert(spec: ParamSpec, r: RangeRule, inRange: Boolean, value: Double): Alert {
    val now = fmt(spec, value)
    val bounds = fmtRange(spec, r.low, r.high)
    return if (inRange) {
        Alert("${spec.label} is back in range", "Entered $bounds, now $now.")
    } else {
        val direction = if (value < r.low) "dropped below ${fmt(spec, r.low)}" else "rose above ${fmt(spec, r.high)}"
        Alert("${spec.label} out of range", "${spec.label} $direction, now $now.")
    }
}

/** The grey note shown under each slider pair: what the current configuration will actually do. */
fun describeRangeRule(spec: ParamSpec, rule: RangeRule): String {
    val r = rule.normalized()
    if (!r.enabled) return "Notifications are off for ${spec.label.lowercase()}."
    val atBottom = r.low <= spec.min
    val atTop = r.high >= spec.max
    val label = spec.label
    if (atBottom && atTop) return "This range covers every possible value, so you will never be notified."
    val bounds = fmtRange(spec, r.low, r.high)
    return when (r.mode) {
        RangeMode.NOTIFY_ON_ENTER -> when {
            atBottom -> "You'll be notified when $label falls to ${fmt(spec, r.high)} or lower."
            atTop -> "You'll be notified when $label rises to ${fmt(spec, r.low)} or higher."
            else -> "You'll be notified when $label enters $bounds."
        }
        RangeMode.NOTIFY_ON_EXIT -> when {
            atBottom -> "You'll be notified when $label rises above ${fmt(spec, r.high)}."
            atTop -> "You'll be notified when $label drops below ${fmt(spec, r.low)}."
            else -> "You'll be notified when $label leaves $bounds: below ${fmt(spec, r.low)} or above ${fmt(spec, r.high)}."
        }
    }
}

// ---------------------------------------------------------------------------
// Cumulative battery discharge ("night consumption")
// ---------------------------------------------------------------------------

const val PV_ACTIVE_WATTS = 50.0

/** How long PV must stay above / at-or-below [PV_ACTIVE_WATTS] before it counts as day / night. */
const val PV_CONFIRM_SECONDS = 30 * 60L

/** Upper end of the budget slider: the slider spans baseline .. baseline + this. */
const val DISCHARGE_MAX_BUDGET_KWH = 10.0

/**
 * Notify once when the battery counter has grown by [budgetKWh] since the baseline.
 * In the UI this is one slider on the counter's own scale: min = baseline, value = baseline + budget.
 */
@Serializable
data class DischargeRule(val enabled: Boolean, val budgetKWh: Double)

@Serializable
data class DischargeState(
    /** Counter value at the last sunset (or at the first poll). This is the slider's minimum. */
    val baselineKWh: Double? = null,
    val lowPvSince: Long? = null,
    /** Counter value when PV most recently dropped to <= 50 W; becomes the new baseline if the night is confirmed. */
    val counterAtLowStart: Double? = null,
    val highPvSince: Long? = null,
    /** True once a real day (PV > 50 W for 30+ min) has been seen since the last baseline reset. */
    val armed: Boolean = false,
    val notified: Boolean = false,
)

/** Energy drawn since the baseline, for the marker/label next to the slider. */
fun usedSinceBaselineKWh(state: DischargeState, counterKWh: Double): Double =
    (counterKWh - (state.baselineKWh ?: counterKWh)).coerceAtLeast(0.0)

/** Slider value on the counter's scale: baseline + budget (e.g. baseline 7.7 and budget 0.5 -> 8.2). */
fun dischargeTargetKWh(state: DischargeState, rule: DischargeRule, counterKWh: Double): Double =
    (state.baselineKWh ?: counterKWh) + rule.budgetKWh

/** Inverse of [dischargeTargetKWh]: turn a dragged slider value back into the stored budget. */
fun budgetForTargetKWh(state: DischargeState, counterKWh: Double, targetKWh: Double): Double =
    (targetKWh - (state.baselineKWh ?: counterKWh)).coerceAtLeast(0.0)

/**
 * The inverter's counter is a lifetime total that also grows during the day, so we keep our own baseline:
 * - first poll: baseline = current counter value (slider minimum starts there);
 * - sunset: when PV falls to <= 50 W and stays there for 30 min, the baseline moves to the counter value
 *   *at the moment PV dropped* (the budget stays the same, so the slider value moves with it);
 * - only a "real" day arms the reset, so dawn flicker and short cloud dips can't reset the baseline;
 * - a counter that goes backwards (device reset) re-baselines.
 * One alert per baseline; raising the budget re-arms it.
 *
 * Pass the snapshot's own timestamp as [nowEpochSeconds] (not the wall clock), so a repeated stale
 * response doesn't advance the timers.
 */
fun evaluateDischarge(
    rule: DischargeRule,
    state: DischargeState,
    counterKWh: Double,
    pvPowerW: Double,
    nowEpochSeconds: Long,
): Pair<DischargeState, Alert?> {
    var s = state
    val baseline = s.baselineKWh
    if (baseline == null || counterKWh < baseline) {
        s = s.copy(baselineKWh = counterKWh, notified = false)
    }

    if (pvPowerW <= PV_ACTIVE_WATTS) {
        if (s.lowPvSince == null) s = s.copy(lowPvSince = nowEpochSeconds, counterAtLowStart = counterKWh)
        s = s.copy(highPvSince = null)
        val since = s.lowPvSince
        val candidate = s.counterAtLowStart
        if (s.armed && since != null && candidate != null && nowEpochSeconds - since >= PV_CONFIRM_SECONDS) {
            s = s.copy(baselineKWh = candidate, armed = false, notified = false)
        }
    } else {
        if (s.highPvSince == null) s = s.copy(highPvSince = nowEpochSeconds)
        s = s.copy(lowPvSince = null, counterAtLowStart = null)
        val high = s.highPvSince
        if (!s.armed && high != null && nowEpochSeconds - high >= PV_CONFIRM_SECONDS) s = s.copy(armed = true)
    }

    if (!rule.enabled || rule.budgetKWh <= 0.0) return s to null
    val used = usedSinceBaselineKWh(s, counterKWh)
    if (used < rule.budgetKWh) return s.copy(notified = false) to null
    if (s.notified) return s to null

    val alert = Alert(
        "Battery consumption reached ${formatNumber(rule.budgetKWh, 1)} kWh",
        "${formatNumber(used, 2)} kWh drawn from the battery since the last sunset.",
    )
    return s.copy(notified = true) to alert
}

fun describeDischargeRule(rule: DischargeRule, state: DischargeState, counterKWh: Double): String {
    if (!rule.enabled || rule.budgetKWh <= 0.0) return "Notifications are off for battery consumption."
    val target = formatNumber(dischargeTargetKWh(state, rule, counterKWh), 1)
    val base = formatNumber(state.baselineKWh ?: counterKWh, 1)
    return "You'll be notified when the battery counter reaches $target kWh " +
        "(${formatNumber(rule.budgetKWh, 1)} kWh above $base kWh). The start point moves up at sunset, " +
        "when solar power stays below ${formatNumber(PV_ACTIVE_WATTS, 0)} W for 30 minutes."
}

/** Snap a dragged slider value to the grid min + n*step and strip float noise (11.500000001 -> 11.5). */
fun snapToStep(value: Double, min: Double, step: Double): Double {
    val snapped = min + round((value - min) / step) * step
    return round(snapped * 1000.0) / 1000.0
}

// ---------------------------------------------------------------------------
// Formatting without String.format (not available in common code)
// ---------------------------------------------------------------------------

fun fmt(spec: ParamSpec, value: Double): String = "${formatNumber(value, spec.decimals)} ${spec.unit}"

fun fmtRange(spec: ParamSpec, low: Double, high: Double): String =
    "${formatNumber(low, spec.decimals)}–${formatNumber(high, spec.decimals)} ${spec.unit}"

fun formatNumber(value: Double, decimals: Int): String {
    require(decimals in 0..6) { "decimals must be 0..6" }
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = round(abs(value) * factor).toLong()
    val negative = value < 0 && scaled != 0L
    val sb = StringBuilder()
    if (negative) sb.append('-')
    sb.append(scaled / factor)
    if (decimals > 0) {
        sb.append('.')
        sb.append((scaled % factor).toString().padStart(decimals, '0'))
    }
    return sb.toString()
}
