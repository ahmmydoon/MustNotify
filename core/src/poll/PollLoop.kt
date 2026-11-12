package com.salahoard.mustnotify.poll

import com.salahoard.mustnotify.data.SettingsStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Runs until cancelled: one independent polling coroutine per *monitored* inverter. When the set of monitored
 * inverters changes (the user flips a switch) the loops restart, and switched-off inverters lose their notification.
 *
 * - After a successful poll it waits until just after the data's next expected refresh ([PollSchedule]).
 * - After a failure (no internet...) it keeps the last known values, marks them uncertain ("?") and backs off.
 *   It never raises an alert for a failure.
 *
 * On Android run it from the foreground service's scope.
 */
class PollLoop(
    private val engine: PollEngine,
    private val settings: SettingsStore,
    private val notifier: Notifier,
    private val hub: LiveHub,
    /** Injectable so tests don't wait in real time. */
    private val pause: suspend (millis: Long) -> Unit = { delay(it) },
) {
    suspend fun run(): Nothing {
        var previous = emptySet<String>()
        settings.data
            .map { data -> data.devices.filter { it.monitoring }.map { it.ref.sn }.toSet() }
            .distinctUntilChanged()
            .collectLatest { current ->
                (previous - current).forEach { sn -> notifier.clear(sn) }
                previous = current
                coroutineScope { current.forEach { sn -> launch { runDevice(sn) } } }
            }
        error("settings flow ended unexpectedly")
    }

    private suspend fun runDevice(sn: String) {
        var failures = 0
        var lastKnown: LiveReadings? = null
        while (true) {
            val outcome = pollSafely(sn)
            val device = settings.data.first().device(sn)?.ref ?: return
            when (outcome) {
                is PollOutcome.NoDevice -> return
                is PollOutcome.Success -> {
                    failures = 0
                    lastKnown = outcome.readings
                    val status = DeviceStatus(sn, outcome.readings, uncertain = false, failure = null)
                    hub.put(status)
                    notifier.showStatus(device, status)
                    outcome.alerts.forEach { notifier.showAlert(device, it) }
                    pause(PollSchedule.delayAfterSuccessSeconds(outcome.readings.ageSeconds) * 1000)
                }
                is PollOutcome.Failed -> {
                    failures++
                    val status = DeviceStatus(sn, lastKnown, uncertain = true, failure = outcome.reason)
                    hub.put(status)
                    notifier.showStatus(device, status)
                    pause(PollSchedule.delayAfterFailureSeconds(failures) * 1000)
                }
            }
        }
    }

    private suspend fun pollSafely(sn: String): PollOutcome = try {
        engine.pollOnce(sn)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PollOutcome.Failed(FailureReason.UNKNOWN)
    }
}
