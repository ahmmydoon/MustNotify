package com.salahoard.mustnotify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restarts monitoring after a reboot or an app update, if any inverter was being monitored. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val monitored = AppGraph.get(context).settings.data.first().devices.any { it.monitoring }
                if (monitored) MonitorService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
