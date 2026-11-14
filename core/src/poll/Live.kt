package com.salahoard.mustnotify.poll

import com.salahoard.mustnotify.api.DeviceRef
import com.salahoard.mustnotify.domain.Alert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The values the persistent notification and the UI show. */
data class LiveReadings(
    val batteryVoltage: Double?,
    val batteryCurrentA: Double?,
    val dischargeTotalKWh: Double?,
    val pvPowerW: Double?,
    val loadPowerW: Double?,
    val ratedPowerW: Double?,
    val workState: String?,
    /** Device timestamp as epoch seconds, if the API sent one. */
    val epochSeconds: Long?,
    /** How old the data was when we fetched it. */
    val ageSeconds: Long?,
    /** True when the newest reading is too old to trust; rules are not evaluated then. */
    val stale: Boolean,
    /** Battery energy drawn since the last sunset (counter minus baseline). Only known after a monitored poll. */
    val usedSinceBaselineKWh: Double? = null,
)

enum class FailureReason { NETWORK, AUTH_REQUIRED, SERVER, BAD_RESPONSE, UNKNOWN }

/**
 * What to show for one inverter right now.
 * [uncertain] is true when the last attempt failed (e.g. no internet): the values are the last known ones and
 * should be shown with a "?" next to them. No alert is ever raised for that.
 */
data class DeviceStatus(
    val sn: String,
    val readings: LiveReadings?,
    val uncertain: Boolean,
    val failure: FailureReason?,
)

/** Latest status per inverter, written by the poller (and by one-off reads on the device screen), read by the UI. */
class LiveHub {
    private val state = MutableStateFlow<Map<String, DeviceStatus>>(emptyMap())
    val status: StateFlow<Map<String, DeviceStatus>> = state.asStateFlow()
    fun put(status: DeviceStatus) = state.update { it + (status.sn to status) }
}

/** Implemented per platform: Android notifications, desktop tray/popups. One persistent entry per monitored inverter. */
interface Notifier {
    /**
     * New values for one monitored inverter. Update its widgets always; show or refresh its persistent notification
     * only when [persistent] is true, and remove it when it is false (the user switched it off).
     */
    fun showStatus(device: DeviceRef, status: DeviceStatus, persistent: Boolean)

    /** A threshold alert; a separate, dismissible notification. */
    fun showAlert(device: DeviceRef, alert: Alert)

    /** Monitoring was switched off for this inverter: remove its persistent notification and blank its widgets. */
    fun clear(sn: String)
}
