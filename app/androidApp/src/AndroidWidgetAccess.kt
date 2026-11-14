package com.salahoard.mustnotify

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.salahoard.mustnotify.ui.WidgetAccess

class AndroidWidgetAccess(private val context: Context) : WidgetAccess {
    /**
     * Opens the launcher's "add widget" prompt for this inverter. Returns false when the launcher can't do that from
     * inside an app (Android 7 or some launchers); the user then adds it from the launcher's widget list, and the
     * widget still picks up this inverter if that happens within two minutes.
     */
    override fun requestPin(sn: String): Boolean {
        InverterWidgets.setPending(context, sn)
        if (Build.VERSION.SDK_INT < 26) return false
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false
        return manager.requestPinAppWidget(ComponentName(context, InverterWidgetProvider::class.java), null, null)
    }
}
