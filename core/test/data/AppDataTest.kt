package com.salahoard.mustnotify.data

import com.salahoard.mustnotify.api.DeviceRef
import com.salahoard.mustnotify.domain.RangeMode
import com.salahoard.mustnotify.domain.RangeRule
import com.salahoard.mustnotify.domain.ParamId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppDataTest {
    private fun ref(sn: String, name: String = sn) = DeviceRef(name, "PH1800", "PN", sn, 1, 4, null, 10_800)

    @Test fun mergeAddsUnknownInvertersSwitchedOff() {
        val data = AppData().mergeAccountDevices(listOf(ref("A"), ref("B")))
        assertEquals(listOf("A", "B"), data.devices.map { it.ref.sn })
        assertTrue(data.devices.none { it.monitoring })
    }

    @Test fun mergeKeepsSwitchRulesAndStateButRefreshesNameAndIcon() {
        val rules = RuleSet().withRange(ParamId.BATTERY_VOLTAGE, RangeRule(true, 11.0, 14.0, RangeMode.NOTIFY_ON_ENTER))
        val before = AppData(devices = listOf(MonitoredDevice(ref("A", "Old name"), monitoring = true, rules = rules)))
        val after = before.mergeAccountDevices(listOf(ref("A", "New name"), ref("B")))
        val a = after.device("A")!!
        assertEquals("New name", a.ref.name)
        assertTrue(a.monitoring)
        assertEquals(rules, a.rules)
        assertFalse(after.device("B")!!.monitoring)
    }

    @Test fun mergeKeepsInvertersMissingFromTheFirstPage() {
        val data = AppData().mergeAccountDevices(listOf(ref("A"), ref("B"))).mergeAccountDevices(listOf(ref("A")))
        assertEquals(2, data.devices.size)
    }

    @Test fun firstPickSwitchesOnlyThePickedInverterOn() {
        val data = AppData().mergeAccountDevices(listOf(ref("A"), ref("B"), ref("C"))).pickDevice("B")
        assertEquals(listOf(false, true, false), data.devices.map { it.monitoring })
        assertTrue(data.firstPickDone)
    }

    @Test fun laterPicksDoNotChangeMonitoring() {
        val first = AppData().mergeAccountDevices(listOf(ref("A"), ref("B"))).pickDevice("A")
        val later = first.pickDevice("B")
        assertEquals(listOf(true, false), later.devices.map { it.monitoring })
    }

    @Test fun notificationIsOnByDefault() {
        assertTrue(MonitoredDevice(ref("A")).showNotification)
    }
}
