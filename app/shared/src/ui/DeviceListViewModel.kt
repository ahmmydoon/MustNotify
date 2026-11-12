package com.salahoard.mustnotify.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salahoard.mustnotify.api.ApiResult
import com.salahoard.mustnotify.api.DeviceRef
import com.salahoard.mustnotify.api.toRef
import com.salahoard.mustnotify.data.mergeAccountDevices
import com.salahoard.mustnotify.data.pickDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DeviceListUiState {
    data object Loading : DeviceListUiState
    data class Loaded(val devices: List<DeviceRef>) : DeviceListUiState
    data class Error(val message: String) : DeviceListUiState
    data object LoginRequired : DeviceListUiState
}

class DeviceListViewModel(private val deps: AppDependencies) : ViewModel() {
    private val _state = MutableStateFlow<DeviceListUiState>(DeviceListUiState.Loading)
    val state: StateFlow<DeviceListUiState> = _state.asStateFlow()

    /** Serial numbers of the inverters whose monitoring is switched on, for the "Monitoring" tag in the list. */
    val monitored: StateFlow<Set<String>> = deps.settings.data
        .map { data -> data.devices.filter { it.monitoring }.map { it.ref.sn }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Call when the screen is shown (not from init, so coming back after a login reloads the list). */
    fun refresh() {
        viewModelScope.launch {
            _state.value = DeviceListUiState.Loading
            val token = deps.secrets.token()
            if (token == null) {
                _state.value = DeviceListUiState.LoginRequired
                return@launch
            }
            _state.value = when (val result = deps.api.listDevices(token)) {
                is ApiResult.Ok -> {
                    val devices = result.value.map { it.toRef() }
                    // Every inverter of the account becomes known, but switched OFF until the user enables it.
                    deps.settings.update { it.mergeAccountDevices(devices) }
                    DeviceListUiState.Loaded(devices)
                }
                is ApiResult.AuthRequired -> {
                    deps.secrets.saveToken(null) // expired or revoked: ask for a fresh login
                    DeviceListUiState.LoginRequired
                }
                is ApiResult.NetworkError -> DeviceListUiState.Error("Couldn't reach the server. Check your connection.")
                is ApiResult.ServerError -> DeviceListUiState.Error("The server returned an error (code ${result.code}).")
                is ApiResult.BadCredentials, is ApiResult.BadResponse -> DeviceListUiState.Error("Unexpected response from the server.")
            }
        }
    }

    /** On the very first pick this inverter gets monitoring switched on (and only this one); later picks just open it. */
    fun select(device: DeviceRef, onSelected: (sn: String) -> Unit) {
        viewModelScope.launch {
            deps.settings.update { it.mergeAccountDevices(listOf(device)).pickDevice(device.sn) }
            onSelected(device.sn)
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            deps.secrets.saveToken(null)
            onDone()
        }
    }
}
