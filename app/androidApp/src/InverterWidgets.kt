package com.salahoard.mustnotify

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.widget.RemoteViews
import com.salahoard.mustnotify.api.DeviceRef
import com.salahoard.mustnotify.data.AppData
import com.salahoard.mustnotify.poll.DeviceStatus
import com.salahoard.mustnotify.poll.FailureReason
import kotlinx.coroutines.flow.first
import java.util.Date

/**
 * Home-screen widgets: one widget shows one inverter, with the same five-column row as the persistent notification.
 *
 * Which inverter a widget shows is remembered in a small private preferences file (widget id -> serial number).
 * When the user taps "Home-screen widget" in the app, the inverter is stored as "pending" for two minutes; the next
 * widget the launcher adds claims it. A widget added from the launcher's own list with nothing pending is bound
 * automatically if the account has exactly one inverter, otherwise it asks the user to pick one in the app.
 *
 * Widgets are refreshed by the monitoring service after every poll (updatePeriodMillis is 0), so they only show
 * live values while that inverter is being monitored.
 */
object InverterWidgets {
    private const val PREFS = "inverter_widgets"
    private const val PENDING_SN = "pending_sn"
    private const val PENDING_AT = "pending_at"
    private const val PENDING_TTL_MS = 2 * 60 * 1000L

    // --- bindings ---------------------------------------------------------------------------------------------

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setPending(context: Context, sn: String) {
        prefs(context).edit().putString(PENDING_SN, sn).putLong(PENDING_AT, System.currentTimeMillis()).apply()
    }

    private fun takePending(context: Context): String? {
        val p = prefs(context)
        val sn = p.getString(PENDING_SN, null) ?: return null
        val fresh = System.currentTimeMillis() - p.getLong(PENDING_AT, 0L) <= PENDING_TTL_MS
        p.edit().remove(PENDING_SN).remove(PENDING_AT).apply()
        return if (fresh) sn else null
    }

    private fun snFor(context: Context, widgetId: Int): String? = prefs(context).getString("w$widgetId", null)

    private fun bind(context: Context, widgetId: Int, sn: String) {
        prefs(context).edit().putString("w$widgetId", sn).apply()
    }

    fun remove(context: Context, widgetIds: IntArray) {
        val edit = prefs(context).edit()
        widgetIds.forEach { edit.remove("w$it") }
        edit.apply()
    }

    private fun idsFor(context: Context, sn: String): List<Int> =
        prefs(context).all.entries.filter { it.key.startsWith("w") && it.value == sn }.mapNotNull { it.key.drop(1).toIntOrNull() }

    // --- updating ---------------------------------------------------------------------------------------------

    /** Called by the launcher (new widget, reboot, resize) via the provider. */
    suspend fun refresh(context: Context, widgetIds: IntArray) {
        val deps = AppGraph.get(context)
        val data = deps.settings.data.first()
        val manager = AppWidgetManager.getInstance(context)
        for (id in widgetIds) {
            val sn = snFor(context, id) ?: autoBind(context, id, data)
            val entry = sn?.let { data.device(it) }
            val views = if (entry == null) {
                render(context, "Must Notify", "Tap to choose an inverter", status = null, monitoring = false)
            } else {
                render(context, entry.ref.name, subtitle(context, deps.hub.status.value[entry.ref.sn], entry.monitoring), deps.hub.status.value[entry.ref.sn], entry.monitoring)
            }
            manager.updateAppWidget(id, views)
        }
    }

    /** Called by the notifier after every poll of a monitored inverter. */
    fun update(context: Context, device: DeviceRef, status: DeviceStatus) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = idsFor(context, device.sn)
        if (ids.isEmpty()) return
        val views = render(context, device.name, subtitle(context, status, monitoring = true), status, monitoring = true)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    /** Monitoring stopped for this inverter: its widgets say so instead of showing frozen values. */
    fun markOff(context: Context, sn: String, name: String) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = idsFor(context, sn)
        if (ids.isEmpty()) return
        val views = render(context, name, "Monitoring off", status = null, monitoring = false)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun autoBind(context: Context, widgetId: Int, data: AppData): String? {
        val sn = takePending(context) ?: data.devices.singleOrNull()?.ref?.sn ?: return null
        bind(context, widgetId, sn)
        return sn
    }

    // --- rendering --------------------------------------------------------------------------------------------

    private fun render(context: Context, title: String, subtitle: String, status: DeviceStatus?, monitoring: Boolean): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_status).apply {
            setTextViewText(R.id.widget_title, title)
            setTextViewText(R.id.widget_subtitle, subtitle)
            StatusCells.fill(this, if (monitoring) status?.readings else null, uncertain = monitoring && status?.uncertain == true)
            setOnClickPendingIntent(R.id.widget_root, openApp(context))
        }

    private fun subtitle(context: Context, status: DeviceStatus?, monitoring: Boolean): String {
        if (!monitoring) return "Monitoring off"
        if (status == null) return "Waiting for data…"
        val time = status.readings?.epochSeconds?.let { DateFormat.getTimeFormat(context).format(Date(it * 1000)) }
        return when {
            status.failure == FailureReason.NETWORK -> if (time != null) "Offline · last update $time" else "Offline"
            status.uncertain -> if (time != null) "Can't reach the inverter · last update $time" else "Can't reach the inverter"
            status.readings?.stale == true -> "Outdated · last update ${time ?: "unknown"}"
            time != null -> "Updated $time"
            else -> "Updated"
        }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        1,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
