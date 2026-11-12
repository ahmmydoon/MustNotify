package com.salahoard.mustnotify.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salahoard.mustnotify.data.MonitoredDevice
import com.salahoard.mustnotify.data.updateDevice
import com.salahoard.mustnotify.domain.ParamSpec
import com.salahoard.mustnotify.domain.RangeMode
import com.salahoard.mustnotify.domain.RangeRule
import com.salahoard.mustnotify.poll.DeviceStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DeviceUiState(
    val device: MonitoredDevice? = null,
    val status: DeviceStatus? = null,
)

/** Everything the device screen can change. Every call is saved immediately and applies from the next poll. */
interface DeviceActions {
    fun setMonitoring(on: Boolean)
    fun setRangeEnabled(spec: ParamSpec, on: Boolean)
    fun setRange(spec: ParamSpec, low: Double, high: Double)
    fun setMode(spec: ParamSpec, mode: RangeMode)
    fun setDischargeEnabled(on: Boolean)
    fun setDischargeBudget(kWh: Double)
}

class DeviceViewModel(private val deps: AppDependencies, private val sn: String) : ViewModel(), DeviceActions {
    val state: StateFlow<DeviceUiState> = combine(deps.settings.data, deps.hub.status) { data, statuses ->
        DeviceUiState(device = data.device(sn), status = statuses[sn])
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceUiState())

    init {
        // One read-only look so the screen shows live values and the sliders get the right watt range,
        // even for an inverter that isn't being monitored yet.
        viewModelScope.launch { deps.engine.peek(sn)?.let { deps.hub.put(it) } }
    }

    override fun setMonitoring(on: Boolean) = edit { it.copy(monitoring = on) }

    override fun setRangeEnabled(spec: ParamSpec, on: Boolean) = editRange(spec) { it.copy(enabled = on) }

    override fun setRange(spec: ParamSpec, low: Double, high: Double) = editRange(spec) { it.copy(low = low, high = high) }

    override fun setMode(spec: ParamSpec, mode: RangeMode) = editRange(spec) { it.copy(mode = mode) }

    override fun setDischargeEnabled(on: Boolean) =
        edit { it.copy(rules = it.rules.copy(discharge = it.rules.discharge.copy(enabled = on))) }

    override fun setDischargeBudget(kWh: Double) =
        edit { it.copy(rules = it.rules.copy(discharge = it.rules.discharge.copy(budgetKWh = kWh))) }

    private fun editRange(spec: ParamSpec, change: (RangeRule) -> RangeRule) = edit { device ->
        device.copy(rules = device.rules.withRange(spec.id, change(device.rules.rangeFor(spec))))
    }

    private fun edit(change: (MonitoredDevice) -> MonitoredDevice) {
        viewModelScope.launch { deps.settings.update { it.updateDevice(sn, change) } }
    }
}
