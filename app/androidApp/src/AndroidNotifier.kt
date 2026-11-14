package com.salahoard.mustnotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.salahoard.mustnotify.api.DeviceRef
import com.salahoard.mustnotify.domain.Alert
import com.salahoard.mustnotify.domain.formatNumber
import com.salahoard.mustnotify.poll.DeviceStatus
import com.salahoard.mustnotify.poll.LiveReadings
import com.salahoard.mustnotify.poll.Notifier

/**
 * One persistent notification per monitored inverter (unless the user switched it off), separate dismissible alert
 * notifications, and a refresh of that inverter's home-screen widgets after every poll.
 *
 * Android wants exactly one notification tied to the foreground service. That is the first inverter's; if it is
 * switched off, another inverter's takes over. When no inverter shows a notification (all switched off, or the
 * first poll hasn't finished) a minimal, silent "monitoring in the background" notification fills the role, because
 * Android does not allow a foreground service without one. Calls come from several coroutines, hence @Synchronized.
 */
class AndroidNotifier(private val service: Service) : Notifier {
    private val context: Context = service
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val statusCache = LinkedHashMap<String, Notification>()
    private val names = HashMap<String, String>()
    private var foregroundSn: String? = null

    init { createChannels() }

    /** Call from onStartCommand: (re)asserts a foreground notification so Android's 5-second rule is always met. */
    @Synchronized
    fun ensureForeground() {
        val sn = foregroundSn
        val cached = sn?.let { statusCache[it] }
        if (sn != null && cached != null) startForeground(statusId(sn), cached) else startForeground(PLACEHOLDER_ID, placeholder())
    }

    @Synchronized
    override fun showStatus(device: DeviceRef, status: DeviceStatus, persistent: Boolean) {
        names[device.sn] = device.name
        InverterWidgets.update(context, device, status)
        if (!persistent) {
            removePersistent(device.sn)
            return
        }
        val notification = buildStatus(device, status)
        statusCache[device.sn] = notification
        if (foregroundSn == null) promote(device.sn, notification) else manager.notify(statusId(device.sn), notification)
    }

    @Synchronized
    override fun showAlert(device: DeviceRef, alert: Alert) {
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_inverter)
            .setContentTitle(alert.title)
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setSubText(device.name)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        // Same inverter + same kind of alert replaces its previous notification instead of piling up.
        manager.notify("alert:${device.sn}:${alert.title}".hashCode(), notification)
    }

    @Synchronized
    override fun clear(sn: String) {
        removePersistent(sn)
        InverterWidgets.markOff(context, sn, names[sn] ?: "Inverter")
        names.remove(sn)
    }

    /** Remove everything; the service calls this right before it stops. */
    @Synchronized
    fun shutdown() {
        names.forEach { (sn, name) -> InverterWidgets.markOff(context, sn, name) }
        statusCache.keys.forEach { manager.cancel(statusId(it)) }
        statusCache.clear()
        names.clear()
        foregroundSn = null
        manager.cancel(PLACEHOLDER_ID)
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    /** Takes one inverter's persistent notification away and, if it was the foreground one, hands that role on. */
    private fun removePersistent(sn: String) {
        val hadOne = statusCache.remove(sn) != null
        if (sn != foregroundSn) {
            if (hadOne) manager.cancel(statusId(sn))
            return
        }
        val next = statusCache.keys.firstOrNull()
        if (next != null) {
            promote(next, statusCache.getValue(next))
        } else {
            foregroundSn = null
            startForeground(PLACEHOLDER_ID, placeholder()) // silent stand-in; the service stops itself when nothing is monitored
        }
        manager.cancel(statusId(sn))
    }

    private fun promote(sn: String, notification: Notification) {
        startForeground(statusId(sn), notification)
        foregroundSn = sn
        manager.cancel(PLACEHOLDER_ID)
    }

    private fun startForeground(id: Int, notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(service, id, notification, type)
    }

    // --- building -------------------------------------------------------------------------------------------

    private fun buildStatus(device: DeviceRef, status: DeviceStatus): Notification {
        val readings = status.readings
        val uncertain = status.uncertain
        val iconText = readings?.batteryVoltage?.let { formatNumber(it, 1) + if (uncertain) "?" else "" } ?: "?"
        val builder = NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(StatusIcon.forText(iconText)) // the status bar shows the battery voltage
            .setSubText(if (readings?.stale == true) "${device.name} · outdated" else device.name)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(statusRow(readings, uncertain))
            .setCustomBigContentView(statusRow(readings, uncertain))
            .setContentTitle(device.name) // fallback for screen readers; the custom row replaces it visually
            .setContentText(summary(readings, uncertain))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
        readings?.epochSeconds?.let { builder.setWhen(it * 1000).setShowWhen(true) } // header shows when the data is from
        return builder.build()
    }

    /** The one-row table: five columns, a small label above each value. A "?" marks values that may be outdated. */
    private fun statusRow(readings: LiveReadings?, uncertain: Boolean): RemoteViews =
        RemoteViews(context.packageName, R.layout.notification_status).also { StatusCells.fill(it, readings, uncertain) }

    private fun summary(readings: LiveReadings?, uncertain: Boolean): String {
        if (readings == null) return "No data yet ?"
        val q = if (uncertain) " ?" else ""
        return "Battery ${readings.batteryVoltage?.let { formatNumber(it, 1) } ?: "–"} V$q · " +
            "Solar ${readings.pvPowerW?.let { formatNumber(it, 0) } ?: "–"} W$q · " +
            "Load ${readings.loadPowerW?.let { formatNumber(it, 0) } ?: "–"} W$q"
    }

    /** Minimal, silent: only there because Android requires a notification for a running foreground service. */
    private fun placeholder(): Notification = NotificationCompat.Builder(context, BACKGROUND_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_inverter)
        .setContentTitle("Must Notify")
        .setContentText("Monitoring your inverter in the background")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(openApp())
        .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        manager.createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL, "Inverter status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Always-visible live values of each monitored inverter"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When a value you set a threshold for leaves or enters its range"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(BACKGROUND_CHANNEL, "Background monitoring", NotificationManager.IMPORTANCE_MIN).apply {
                description = "A minimal notification Android requires while monitoring runs and no inverter shows its own"
                setShowBadge(false)
            },
        )
    }

    private fun statusId(sn: String): Int = "status:$sn".hashCode()

    private companion object {
        const val STATUS_CHANNEL = "status"
        const val ALERT_CHANNEL = "alerts"
        const val BACKGROUND_CHANNEL = "background"
        const val PLACEHOLDER_ID = 1
    }
}
