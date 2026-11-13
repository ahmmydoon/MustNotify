package com.salahoard.mustnotify.poll

import com.salahoard.mustnotify.api.ApiResult
import com.salahoard.mustnotify.api.DeviceRef
import com.salahoard.mustnotify.api.ReadingsSource
import com.salahoard.mustnotify.data.SecretStore
import com.salahoard.mustnotify.data.SettingsStore
import com.salahoard.mustnotify.data.updateDevice
import com.salahoard.mustnotify.domain.Alert
import com.salahoard.mustnotify.domain.InverterSnapshot
import com.salahoard.mustnotify.domain.ParamId
import com.salahoard.mustnotify.domain.ParamSpecs
import com.salahoard.mustnotify.domain.RangeState
import com.salahoard.mustnotify.domain.evaluateDischarge
import com.salahoard.mustnotify.domain.evaluateRange
import com.salahoard.mustnotify.domain.isStale
import com.salahoard.mustnotify.domain.usedSinceBaselineKWh
import kotlinx.coroutines.flow.first

sealed interface PollOutcome {
    val alerts: List<Alert>

    data class Success(val readings: LiveReadings, override val alerts: List<Alert>) : PollOutcome

    /** The inverter is not (or no longer) in the settings. */
    data object NoDevice : PollOutcome {
        override val alerts: List<Alert> = emptyList()
    }

    /** Never carries alerts: being offline is not something to wake the user for. */
    data class Failed(val reason: FailureReason) : PollOutcome {
        override val alerts: List<Alert> = emptyList()
    }
}

/**
 * One poll of one inverter: read it, evaluate its rules, persist its transition state, return what to show/notify.
 * Shared by the Android foreground service and the desktop loop. It never talks to the OS itself.
 *
 * @param nowEpochSeconds wall clock in epoch seconds; injected so tests (and common code) don't need platform APIs.
 */
class PollEngine(
    private val source: ReadingsSource,
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val nowEpochSeconds: () -> Long,
    private val maxAgeSeconds: Long = 15 * 60L,
) {
    suspend fun pollOnce(sn: String): PollOutcome {
        val entry = settings.data.first().device(sn) ?: return PollOutcome.NoDevice
        val device = entry.ref
        val result = read(device)
        val snapshot = (result as? ApiResult.Ok)?.value ?: return PollOutcome.Failed(failureOf(result))

        val now = nowEpochSeconds()
        var readings = toReadings(snapshot, device, now)
        val evalTime = readings.epochSeconds ?: now // timers follow the data's clock, so a repeated stale reply can't advance them
        val ratedPower = snapshot.ratedPowerW ?: entry.ratedPowerW

        val alerts = mutableListOf<Alert>()
        var runtime = entry.runtime

        if (readings.stale) {
            if (!runtime.staleAlerted) {
                alerts += Alert("Inverter data is outdated", "No new readings for ${(readings.ageSeconds ?: 0) / 60} minutes. Thresholds are paused.")
                runtime = runtime.copy(staleAlerted = true)
            }
        } else {
            runtime = runtime.copy(staleAlerted = false)

            val rangeStates = runtime.ranges.toMutableMap()
            for (spec in ParamSpecs.forRatedPower(ratedPower)) {
                val value = when (spec.id) {
                    ParamId.BATTERY_VOLTAGE -> snapshot.batteryVoltage
                    ParamId.BATTERY_CURRENT -> snapshot.batteryCurrentA
                    ParamId.PV_POWER -> snapshot.pvPowerW
                    ParamId.LOAD_POWER -> snapshot.loadPowerW
                } ?: continue
                val (next, alert) = evaluateRange(
                    spec = spec,
                    rule = entry.rules.rangeFor(spec),
                    state = rangeStates[spec.id] ?: RangeState(),
                    value = value,
                    nowEpochSeconds = evalTime,
                )
                rangeStates[spec.id] = next
                if (alert != null) alerts += alert
            }
            runtime = runtime.copy(ranges = rangeStates)

            val counter = snapshot.dischargeTotalKWh
            val pv = snapshot.pvPowerW
            if (counter != null && pv != null) {
                val (next, alert) = evaluateDischarge(entry.rules.discharge, runtime.discharge, counter, pv, evalTime)
                runtime = runtime.copy(discharge = next)
                readings = readings.copy(usedSinceBaselineKWh = usedSinceBaselineKWh(next, counter))
                if (alert != null) alerts += alert
            }
        }

        // Replace only runtime + rated power, so a slider edited during this poll isn't overwritten.
        settings.update { data -> data.updateDevice(sn) { it.copy(runtime = runtime, ratedPowerW = ratedPower) } }
        return PollOutcome.Success(readings, alerts)
    }

    /**
     * Read-only look for the device screen: no rule evaluation and no transition state, but it remembers the rated
     * power so the watt sliders get the right range. Works for inverters that are not being monitored.
     */
    suspend fun peek(sn: String): DeviceStatus? {
        val entry = settings.data.first().device(sn) ?: return null
        val result = read(entry.ref)
        val snapshot = (result as? ApiResult.Ok)?.value
            ?: return DeviceStatus(sn, null, uncertain = true, failure = failureOf(result))
        val readings = toReadings(snapshot, entry.ref, nowEpochSeconds())
        readings.ratedPowerW?.let { rated ->
            if (rated != entry.ratedPowerW) settings.update { data -> data.updateDevice(sn) { it.copy(ratedPowerW = rated) } }
        }
        return DeviceStatus(sn, readings, uncertain = false, failure = null)
    }

    /** The readings endpoint currently needs no login. If the vendor starts requiring one, retry with the token. */
    private suspend fun read(device: DeviceRef): ApiResult<InverterSnapshot> {
        val first = source.readDevice(device, null)
        if (first !is ApiResult.AuthRequired) return first
        val token = secrets.token() ?: return first
        return source.readDevice(device, token)
    }

    private fun failureOf(result: ApiResult<*>): FailureReason = when (result) {
        is ApiResult.AuthRequired -> FailureReason.AUTH_REQUIRED
        is ApiResult.NetworkError -> FailureReason.NETWORK
        is ApiResult.BadResponse -> FailureReason.BAD_RESPONSE
        else -> FailureReason.SERVER
    }

    private fun toReadings(snapshot: InverterSnapshot, device: DeviceRef, now: Long): LiveReadings {
        val epoch = snapshot.epochSeconds(device.utcOffsetSeconds)
        return LiveReadings(
            batteryVoltage = snapshot.batteryVoltage,
            batteryCurrentA = snapshot.batteryCurrentA,
            dischargeTotalKWh = snapshot.dischargeTotalKWh,
            pvPowerW = snapshot.pvPowerW,
            loadPowerW = snapshot.loadPowerW,
            ratedPowerW = snapshot.ratedPowerW,
            workState = snapshot.workState,
            epochSeconds = epoch,
            ageSeconds = epoch?.let { now - it },
            stale = epoch != null && isStale(epoch, now, maxAgeSeconds),
        )
    }
}
