package com.salahoard.mustnotify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.salahoard.mustnotify.poll.PollLoop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service (type "specialUse": Android has no standard type for this, and unlike dataSync it has no
 * 6-hour limit and may be started after boot). It runs the shared [PollLoop] and stops itself when no inverter is
 * monitored any more.
 *
 * It holds a partial wake lock while running. Without one the CPU can sleep through the 5-minute timer when the
 * screen is off. That costs some battery; see the README notes on battery use.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notifier: AndroidNotifier

    override fun onCreate() {
        super.onCreate()
        notifier = AndroidNotifier(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notifier.ensureForeground() // must happen within seconds of startForegroundService()
        if (running == null) {
            val deps = AppGraph.get(this)
            acquireWakeLock()
            running = scope.launch {
                launch { PollLoop(deps.engine, deps.settings, notifier, deps.hub).run() }
                launch {
                    deps.settings.data.map { data -> data.devices.any { it.monitoring } }.distinctUntilChanged().first { monitored -> !monitored }
                    notifier.shutdown()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MustNotify::monitor").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    companion object {
        /** Safe to call repeatedly. Never throws: if Android refuses a background start, the next app launch retries. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException
            }
        }
    }
}
