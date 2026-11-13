package com.salahoard.mustnotify

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.salahoard.mustnotify.ui.App
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // The service works either way; without the permission Android just hides the notifications.
    private val askForNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val deps = AppGraph.get(this)

        // Whenever at least one inverter is monitored (and the app is visible, so Android allows it), run the service.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                deps.settings.data
                    .map { data -> data.devices.any { it.monitoring } }
                    .distinctUntilChanged()
                    .collect { monitored ->
                        if (monitored) {
                            requestNotificationPermissionIfNeeded()
                            MonitorService.start(this@MainActivity)
                        }
                    }
            }
        }
        setContent { App(deps) }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
