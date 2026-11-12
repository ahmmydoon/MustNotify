package com.salahoard.mustnotify.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotTest {
    // Values taken from a real PV1800 response (identifiers removed).
    private val sample = mapOf(
        "battery_voltage" to "12.9",
        "bt_bms_battery_voltage" to "0.0",
        "battery_current" to "-15",
        "battery_energy_total_discharge" to "7.0",
        "pv_output_power" to "303",
        "load_active_power" to "17",
        "rating_power" to "2000",
        "status" to "OffGrid",
    )

    @Test fun parsesNumbersAndKeepsNonNumericAsText() {
        val s = InverterSnapshot(sample)
        assertEquals(12.9, s.batteryVoltage)
        assertEquals(15.0, s.batteryCurrentA) // reported as -15, shown as magnitude
        assertEquals(303.0, s.pvPowerW)
        assertEquals(2000.0, s.ratedPowerW)
        assertEquals("OffGrid", s.workState)
        assertNull(s.number("status"))
        assertNull(s.number("does_not_exist"))
    }

    @Test fun usesBmsVoltageOnlyWhenNonZero() {
        assertEquals(13.1, InverterSnapshot(sample + ("bt_bms_battery_voltage" to "13.1")).batteryVoltage)
        assertEquals(12.9, InverterSnapshot(sample).batteryVoltage)
    }

    @Test fun parsesDeviceTime() {
        assertEquals(1794374429L, parseDeviceTimeToEpochSeconds("2026-11-11 08:20:29", 10_800))
        assertEquals(0L, parseDeviceTimeToEpochSeconds("1970-01-01 00:00:00", 0))
        assertEquals(1709251199L, parseDeviceTimeToEpochSeconds("2024-02-29 23:59:59", 0))
        assertNull(parseDeviceTimeToEpochSeconds("garbage", 0))
        assertNull(parseDeviceTimeToEpochSeconds("2026-13-01 00:00:00", 0))
    }

    @Test fun staleness() {
        assertFalse(isStale(1_000, 1_000 + 15 * 60))
        assertTrue(isStale(1_000, 1_000 + 15 * 60 + 1))
    }
}

class SpecsAndFormattingTest {
    @Test fun specsFollowRatedPower() {
        val specs = ParamSpecs.forRatedPower(2000.0)
        assertEquals(listOf(ParamId.BATTERY_VOLTAGE, ParamId.BATTERY_CURRENT, ParamId.PV_POWER, ParamId.LOAD_POWER), specs.map { it.id })
        assertEquals(9.0, specs[0].min); assertEquals(15.0, specs[0].max)
        assertEquals(0.0, specs[1].min); assertEquals(80.0, specs[1].max)
        assertEquals(2200.0, specs[2].max); assertEquals(2200.0, specs[3].max)
        assertEquals(2200.0, ParamSpecs.forRatedPower(null)[2].max) // falls back to 2000 W rated
    }

    @Test fun snapsSliderValuesToTheGrid() {
        assertEquals(11.5, snapToStep(11.500000001, 9.0, 0.1), 1e-9)
        assertEquals(11.5, snapToStep(11.54, 9.0, 0.1), 1e-9)
        assertEquals(1500.0, snapToStep(1496.0, 0.0, 10.0), 1e-9)
        assertEquals(0.0, snapToStep(3.0, 0.0, 10.0), 1e-9)
    }

    @Test fun formatsNumbers() {
        assertEquals("12.9", formatNumber(12.9, 1))
        assertEquals("12.0", formatNumber(12.0, 1))
        assertEquals("-15", formatNumber(-15.0, 0))
        assertEquals("0.0", formatNumber(-0.04, 1))
        assertEquals("2200", formatNumber(2200.0, 0))
        assertEquals("0.05", formatNumber(0.05, 2))
    }
}

class RangeRuleTest {
    private val volt = ParamSpecs.forRatedPower(2000.0)[0]
    private val load = ParamSpecs.forRatedPower(2000.0)[3]
    private val exit = RangeRule(true, 11.5, 14.4, RangeMode.NOTIFY_ON_EXIT)
    private val enter = RangeRule(true, 0.0, 11.5, RangeMode.NOTIFY_ON_ENTER)

    private fun run(spec: ParamSpec, rule: RangeRule, values: List<Pair<Double, Long>>): List<Alert?> {
        var state = RangeState()
        return values.map { (v, t) ->
            val (next, alert) = evaluateRange(spec, rule, state, v, t)
            state = next
            alert
        }
    }

    @Test fun firstSampleIsSilent() {
        assertNull(run(volt, exit, listOf(10.0 to 0L))[0])
    }

    @Test fun exitFiresOnceOnTransitionNotRepeatedly() {
        val a = run(volt, exit, listOf(12.5 to 0L, 11.2 to 300L, 11.1 to 600L, 11.0 to 900L))
        assertNull(a[0]); assertNotNull(a[1]); assertNull(a[2]); assertNull(a[3])
        assertEquals("Battery voltage out of range", a[1]!!.title)
        assertTrue(a[1]!!.message.contains("dropped below 11.5 V"))
    }

    @Test fun exitDoesNotFireWhenReturningToRange() {
        val a = run(volt, exit, listOf(12.5 to 0L, 11.2 to 300L, 12.0 to 600L))
        assertNull(a[2])
    }

    @Test fun enterFiresOnlyWhenEnteringRange() {
        val a = run(volt, enter, listOf(12.5 to 0L, 11.4 to 300L, 11.3 to 600L, 12.0 to 900L, 11.0 to 1200L))
        assertNull(a[0]); assertNotNull(a[1]); assertNull(a[2]); assertNull(a[3])
        assertNotNull(a[4]) // re-entered exactly one gap (900 s) after the first alert -> allowed
    }

    @Test fun cooldownSuppressesFlapping() {
        val a = run(volt, exit, listOf(12.5 to 0L, 11.2 to 300L, 12.5 to 600L, 11.2 to 900L, 12.5 to 1200L, 11.2 to 2000L))
        assertNull(a[0]); assertNotNull(a[1]); assertNull(a[3]) // 900-300 = 600 s < gap
        assertNotNull(a[5]) // 2000-300 = 1700 s >= gap
    }

    @Test fun editingTheRuleNeverFiresByItself() {
        var state = RangeState()
        state = evaluateRange(volt, exit, state, 12.5, 0).first
        // user drags the slider so that the current value is suddenly outside the range
        val edited = exit.copy(low = 13.0)
        val (next, alert) = evaluateRange(volt, edited, state, 12.5, 300)
        assertNull(alert)
        assertEquals(false, next.inRange)
    }

    @Test fun disabledRuleNeverFiresAndRelearnsWhenReenabled() {
        var state = RangeState()
        state = evaluateRange(volt, exit.copy(enabled = false), state, 12.5, 0).first
        val (s2, a2) = evaluateRange(volt, exit.copy(enabled = false), state, 10.0, 300)
        assertNull(a2)
        val (_, a3) = evaluateRange(volt, exit, s2, 10.0, 600)
        assertNull(a3)
    }

    @Test fun crossedSlidersAreNormalized() {
        val crossed = RangeRule(true, 14.4, 11.5, RangeMode.NOTIFY_ON_EXIT)
        assertTrue(crossed.contains(12.0))
        assertEquals(crossed.key(), RangeRule(true, 11.5, 14.4, RangeMode.NOTIFY_ON_EXIT).key())
    }

    @Test fun notesDescribeTheConfiguration() {
        assertEquals(
            "You'll be notified when Battery voltage leaves 11.5–14.4 V: below 11.5 V or above 14.4 V.",
            describeRangeRule(volt, exit),
        )
        assertEquals(
            "You'll be notified when Battery voltage falls to 11.5 V or lower.",
            describeRangeRule(volt, RangeRule(true, 9.0, 11.5, RangeMode.NOTIFY_ON_ENTER)),
        )
        assertEquals(
            "You'll be notified when Load rises above 1500 W.",
            describeRangeRule(load, RangeRule(true, 0.0, 1500.0, RangeMode.NOTIFY_ON_EXIT)),
        )
        assertEquals(
            "You'll be notified when Load rises to 1500 W or higher.",
            describeRangeRule(load, RangeRule(true, 1500.0, 2200.0, RangeMode.NOTIFY_ON_ENTER)),
        )
        assertEquals(
            "This range covers every possible value, so you will never be notified.",
            describeRangeRule(load, RangeRule(true, 0.0, 2200.0, RangeMode.NOTIFY_ON_EXIT)),
        )
        assertEquals("Notifications are off for battery voltage.", describeRangeRule(volt, exit.copy(enabled = false)))
    }
}

class DischargeRuleTest {
    private val rule = DischargeRule(true, 0.5)
    private val min = 60L

    private fun step(s: DischargeState, counter: Double, pv: Double, t: Long, r: DischargeRule = rule) =
        evaluateDischarge(r, s, counter, pv, t)

    /** Daytime start, then 40 min of sun so the state is armed for the coming sunset. */
    private fun armedState(counter: Double = 7.0): DischargeState {
        var s = step(DischargeState(), counter, 800.0, 0).first
        for (m in 5..40 step 5) s = step(s, counter, 800.0, m * min).first
        assertTrue(s.armed)
        return s
    }

    @Test fun firstPollSetsBaselineAndStaysSilent() {
        val (s, a) = step(DischargeState(), 7.0, 0.0, 0)
        assertEquals(7.0, s.baselineKWh); assertNull(a)
        assertEquals(0.0, usedSinceBaselineKWh(s, 7.0))
    }

    @Test fun alertsOnceWhenBudgetReached() {
        val s0 = step(DischargeState(), 7.0, 0.0, 0).first
        val (s1, a1) = step(s0, 7.4, 0.0, 300); assertNull(a1)
        val (s2, a2) = step(s1, 7.5, 0.0, 600); assertNotNull(a2)
        val (_, a3) = step(s2, 7.7, 0.0, 900); assertNull(a3)
    }

    @Test fun raisingTheBudgetRearms() {
        var s = step(DischargeState(), 7.0, 0.0, 0).first
        s = step(s, 7.6, 0.0, 300).first
        assertTrue(s.notified)
        val bigger = DischargeRule(true, 1.0)
        val (s2, a2) = step(s, 7.6, 0.0, 600, bigger)
        assertNull(a2); assertFalse(s2.notified)
        assertNotNull(step(s2, 8.0, 0.0, 900, bigger).second)
    }

    @Test fun sliderStaysOnTheCountersScaleAndKeepsItsOffsetAtSunset() {
        // baseline 7.0, slider at 7.5
        var s = armedState(7.0)
        assertEquals(7.5, dischargeTargetKWh(s, rule, 7.0), 1e-9)
        assertEquals(0.5, budgetForTargetKWh(s, 7.0, 7.5), 1e-9)
        // sun goes down at 17:00 with the counter at 7.7; stays dark
        s = step(s, 7.7, 20.0, 50 * min).first
        s = step(s, 7.8, 10.0, 60 * min).first
        assertEquals(7.0, s.baselineKWh)                              // not confirmed yet
        s = step(s, 7.9, 0.0, 80 * min).first                          // 30 min after the drop -> confirmed
        assertEquals(7.7, s.baselineKWh)                              // baseline = value at the moment PV dropped
        assertEquals(8.2, dischargeTargetKWh(s, rule, 7.9), 1e-9)     // slider min 7.7, value 8.2
        assertFalse(s.armed)
    }

    @Test fun shortCloudDipDoesNotMoveTheBaseline() {
        var s = armedState(7.0)
        s = step(s, 7.1, 10.0, 50 * min).first
        s = step(s, 7.2, 700.0, 60 * min).first                        // sun is back after 10 min
        s = step(s, 7.3, 800.0, 120 * min).first
        assertEquals(7.0, s.baselineKWh)
        assertTrue(s.armed)
    }

    @Test fun dawnFlickerAtNightStartDoesNotResetBeforeARealDay() {
        var s = step(DischargeState(), 7.0, 0.0, 0).first              // started watching at night
        s = step(s, 7.5, 80.0, 7 * 60 * min).first                     // 10 min of weak light at dawn
        s = step(s, 7.6, 0.0, 7 * 60 * min + 10 * min).first
        s = step(s, 7.8, 0.0, 9 * 60 * min).first                      // dark again for hours
        assertEquals(7.0, s.baselineKWh)
        assertFalse(s.armed)
    }

    @Test fun counterGoingBackwardsRebaselines() {
        var s = step(DischargeState(), 7.0, 0.0, 0).first
        s = step(s, 0.2, 0.0, 300).first
        assertEquals(0.2, s.baselineKWh)
    }

    @Test fun disabledRuleStillTracksBaseline() {
        val (s, a) = step(DischargeState(), 7.0, 0.0, 0, DischargeRule(false, 0.5))
        assertNull(a); assertEquals(7.0, s.baselineKWh)
    }

    @Test fun noteMentionsTargetAndSunset() {
        val s = DischargeState(baselineKWh = 7.7)
        val text = describeDischargeRule(rule, s, 7.9)
        assertTrue(text.contains("8.2 kWh"))
        assertTrue(text.contains("sunset"))
    }
}
