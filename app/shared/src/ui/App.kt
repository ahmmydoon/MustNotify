package com.salahoard.mustnotify.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

private sealed interface Screen {
    data object Login : Screen
    data object Devices : Screen
    data class Device(val sn: String) : Screen
}

@Composable
fun App(deps: AppDependencies) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                var screen by remember { mutableStateOf<Screen?>(null) }
                LaunchedEffect(Unit) {
                    screen = if (deps.secrets.token() != null) Screen.Devices else Screen.Login
                }

                when (val current = screen) {
                    null -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                    Screen.Login -> {
                        val vm = viewModel { LoginViewModel(deps) }
                        val state by vm.state.collectAsState()
                        LoginScreen(
                            state = state,
                            onAccountChange = vm::onAccountChange,
                            onPasswordChange = vm::onPasswordChange,
                            onSubmit = { vm.submit { screen = Screen.Devices } },
                        )
                    }

                    Screen.Devices -> {
                        val vm = viewModel { DeviceListViewModel(deps) }
                        val state by vm.state.collectAsState()
                        val monitored by vm.monitored.collectAsState()
                        LaunchedEffect(Unit) { vm.refresh() }
                        LaunchedEffect(state) {
                            if (state is DeviceListUiState.LoginRequired) screen = Screen.Login
                        }
                        DeviceListScreen(
                            state = state,
                            monitored = monitored,
                            icons = deps.icons,
                            onSelect = { device -> vm.select(device) { sn -> screen = Screen.Device(sn) } },
                            onRetry = vm::refresh,
                            onLogout = { vm.logout { screen = Screen.Login } },
                        )
                    }

                    is Screen.Device -> {
                        val vm = viewModel(key = "device-${current.sn}") { DeviceViewModel(deps, current.sn) }
                        val state by vm.state.collectAsState()
                        DeviceScreen(state = state, actions = vm, background = deps.background, onBack = { screen = Screen.Devices })
                    }
                }
            }
        }
    }
}
