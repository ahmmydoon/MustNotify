package com.salahoard.mustnotify.data

import com.salahoard.mustnotify.api.DeviceRef
import com.salahoard.mustnotify.domain.DischargeRule
import com.salahoard.mustnotify.domain.DischargeState
import com.salahoard.mustnotify.domain.ParamId
import com.salahoard.mustnotify.domain.ParamSpec
import com.salahoard.mustnotify.domain.RangeMode
import com.salahoard.mustnotify.domain.RangeRule
import com.salahoard.mustnotify.domain.RangeState
import kotlinx.serialization.Serializable

/** What the user configured with the sliders and checkboxes for one inverter. */
@Serializable
data class RuleSet(
    val ranges: Map<ParamId, RangeRule> = emptyMap(),
    val discharge: DischargeRule = DischargeRule(enabled = false, budgetKWh = 0.5),
) {
    /** Default for a parameter the user hasn't touched: off, full range. */
    fun rangeFor(spec: ParamSpec): RangeRule =
        ranges[spec.id] ?: RangeRule(enabled = false, low = spec.min, high = spec.max, mode = RangeMode.NOTIFY_ON_EXIT)

    fun withRange(id: ParamId, rule: RangeRule): RuleSet = copy(ranges = ranges + (id to rule))
}

/** What the poller remembers between polls (transition tracking, baselines). Never edited by the UI. */
@Serializable
data class RuntimeState(
    val ranges: Map<ParamId, RangeState> = emptyMap(),
    val discharge: DischargeState = DischargeState(),
    /** True while a "data is outdated" alert is active, so it fires once per outage. */
    val staleAlerted: Boolean = false,
)

/** One inverter of the account, with its own switch, rules and poll state. */
@Serializable
data class MonitoredDevice(
    val ref: DeviceRef,
    /** Persistent notification + alerts + background polling for this inverter. */
    val monitoring: Boolean = false,
    /** Whether the always-visible notification is shown while monitoring. Alerts and widgets work either way. */
    val showNotification: Boolean = true,
    /** Last known `rating_power`; it decides the range of the watt sliders even before the first poll. */
    val ratedPowerW: Double? = null,
    val rules: RuleSet = RuleSet(),
    val runtime: RuntimeState = RuntimeState(),
)

/**
 * Everything persisted in settings.json. It contains pn/sn, so it must live in app-private storage
 * and never be logged or backed up. The login token is NOT in here (see [SecretStore]).
 */
@Serializable
data class AppData(
    val devices: List<MonitoredDevice> = emptyList(),
    /** False until the user picks their first inverter after the first login. */
    val firstPickDone: Boolean = false,
) {
    fun device(sn: String): MonitoredDevice? = devices.firstOrNull { it.ref.sn == sn }
}

fun AppData.updateDevice(sn: String, transform: (MonitoredDevice) -> MonitoredDevice): AppData =
    copy(devices = devices.map { if (it.ref.sn == sn) transform(it) else it })

/**
 * Merge the account's device list into the settings. Unknown devices are added with monitoring OFF; known ones keep
 * their switch, rules and state and only get a refreshed name/icon. Devices missing from [fresh] are kept, because
 * the list call only fetches the first page.
 */
fun AppData.mergeAccountDevices(fresh: List<DeviceRef>): AppData {
    val merged = devices.toMutableList()
    for (ref in fresh) {
        val index = merged.indexOfFirst { it.ref.sn == ref.sn }
        if (index >= 0) merged[index] = merged[index].copy(ref = ref) else merged += MonitoredDevice(ref)
    }
    return copy(devices = merged)
}

/**
 * Called when the user taps an inverter in the list. On the very first pick that inverter gets monitoring switched on;
 * every other inverter stays off until the user enables it on its own screen. Later picks change nothing.
 */
fun AppData.pickDevice(sn: String): AppData =
    if (firstPickDone) this
    else copy(firstPickDone = true).updateDevice(sn) { it.copy(monitoring = true) }
