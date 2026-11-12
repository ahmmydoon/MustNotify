package com.salahoard.mustnotify.poll

import com.salahoard.mustnotify.api.ApiResult
import com.salahoard.mustnotify.api.DeviceRef
import com.salahoard.mustnotify.data.AppData
import com.salahoard.mustnotify.data.InMemorySecretStore
import com.salahoard.mustnotify.data.InMemorySettingsStore
import com.salahoard.mustnotify.data.MonitoredDevice
import com.salahoard.mustnotify.data.RuleSet
import com.salahoard.mustnotify.data.updateDevice
import com.salahoard.mustnotify.domain.Alert
import com.salahoard.mustnotify.domain.ParamId
import com.salahoard.mustnotify.domain.RangeMode
import com.salahoard.mustnotify.domain.RangeRule
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RecordingNotifier : Notifier {
    val statuses = mutableListOf<Pair<String, DeviceStatus>>()
    val alerts = mutableListOf<Pair<String, Alert>>()
    val cleared = mutableListOf<String>()
    override fun showStatus(device: DeviceRef, status: DeviceStatus) { statuses += device.sn to status }
    override fun showAlert(device: DeviceRef, alert: Alert) { alerts += device.sn to alert }
    override fun clear(sn: String) { cleared += sn }
}

class PollLoopTest {
    private val now = freshEpoch + 120 // data is 2 minutes old -> next poll in 3.5 minutes

    private fun engine(source: FakeSource, store: InMemorySettingsStore) =
        PollEngine(source, store, InMemorySecretStore(), { now })

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) yield() }

    @Test fun pollsEveryMonitoredInverterAndSchedulesRightAfterTheNextUpdate() = runBlocking<Unit> {
        val second = testDevice.copy(name = "Second", sn = "SN2")
        val store = InMemorySettingsStore(
            AppData(devices = listOf(
                MonitoredDevice(testDevice, monitoring = true),
                MonitoredDevice(second, monitoring = true),
                MonitoredDevice(testDevice.copy(name = "Off", sn = "SN3"), monitoring = false),
            )),
        )
        val source = FakeSource(ApiResult.Ok(snap()))
        val notifier = RecordingNotifier()
        val pauses = mutableListOf<Long>()
        val loop = PollLoop(engine(source, store), store, notifier, LiveHub(), pause = { ms -> pauses += ms; awaitCancellation() })
        val job = launch { loop.run() }
        waitUntil { pauses.size == 2 }
        job.cancelAndJoin()

        assertEquals(listOf(210_000L, 210_000L), pauses)                       // age 120 s -> wait 330 - 120 = 210 s
        assertEquals(setOf("SN", "SN2"), notifier.statuses.map { it.first }.toSet())
        assertEquals(2, source.calls)                                          // the switched-off inverter is never polled
    }

    @Test fun offlineKeepsLastValuesMarksThemUncertainBacksOffAndNeverAlerts() = runBlocking<Unit> {
        val rule = RangeRule(true, 11.5, 14.4, RangeMode.NOTIFY_ON_EXIT)
        val store = InMemorySettingsStore(appWith(RuleSet(ranges = mapOf(ParamId.BATTERY_VOLTAGE to rule))))
        val source = FakeSource(ApiResult.Ok(snap(voltage = 12.5)))
        val notifier = RecordingNotifier()
        val hub = LiveHub()
        val pauses = mutableListOf<Long>()
        val loop = PollLoop(engine(source, store), store, notifier, hub, pause = { ms ->
            pauses += ms
            source.next = ApiResult.NetworkError(RuntimeException("offline"))
            if (pauses.size >= 3) awaitCancellation()
        })
        val job = launch { loop.run() }
        waitUntil { pauses.size >= 3 }
        job.cancelAndJoin()

        assertEquals(listOf(210_000L, 60_000L, 120_000L), pauses)             // success schedule, then 60 s, 120 s back-off
        val statuses = notifier.statuses.map { it.second }
        assertEquals(false, statuses[0].uncertain)
        assertEquals(true, statuses[1].uncertain)
        assertEquals(12.5, statuses[1].readings!!.batteryVoltage)              // last known values are kept, shown with "?"
        assertEquals(FailureReason.NETWORK, statuses[1].failure)
        assertTrue(notifier.alerts.isEmpty())
        assertEquals(true, hub.status.value["SN"]!!.uncertain)
    }

    @Test fun switchingMonitoringOffRemovesItsNotification() = runBlocking<Unit> {
        val store = InMemorySettingsStore(appWith())
        val notifier = RecordingNotifier()
        val pauses = mutableListOf<Long>()
        val loop = PollLoop(engine(FakeSource(ApiResult.Ok(snap())), store), store, notifier, LiveHub(), pause = { ms -> pauses += ms; awaitCancellation() })
        val job = launch { loop.run() }
        waitUntil { pauses.size == 1 }
        store.update { it.updateDevice("SN") { d -> d.copy(monitoring = false) } }
        waitUntil { notifier.cleared.isNotEmpty() }
        job.cancelAndJoin()
        assertEquals(listOf("SN"), notifier.cleared)
    }
}
