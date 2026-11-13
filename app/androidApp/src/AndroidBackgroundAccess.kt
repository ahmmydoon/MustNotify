package com.salahoard.mustnotify

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import com.salahoard.mustnotify.ui.BackgroundAccess

/**
 * While the phone sits unplugged and still with the screen off, Android's Doze mode suspends network access for
 * apps under battery optimization. Exempting the app is what makes overnight monitoring reliable.
 * Uses the plain settings screen, which needs no special permission.
 */
class AndroidBackgroundAccess(private val context: Context) : BackgroundAccess {
    override fun isRestricted(): Boolean =
        !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    override fun openSettings() {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
