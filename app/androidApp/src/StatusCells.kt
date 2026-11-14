package com.salahoard.mustnotify

import android.widget.RemoteViews
import com.salahoard.mustnotify.domain.formatNumber
import com.salahoard.mustnotify.poll.LiveReadings

/**
 * Fills the five-column row (battery, current, drawn, solar, load). The notification and the home-screen widget use
 * different layouts that share the same view ids, so both are filled by this one function.
 * A "?" marks values that may be outdated (e.g. no internet).
 */
object StatusCells {
    fun fill(views: RemoteViews, readings: LiveReadings?, uncertain: Boolean) {
        views.setTextViewText(R.id.value_voltage, cell(readings?.batteryVoltage, 1, "V", uncertain))
        views.setTextViewText(R.id.value_current, cell(readings?.batteryCurrentA, 0, "A", uncertain))
        // Energy drawn since sunset is more telling than the lifetime counter; fall back to the counter if unknown.
        views.setTextViewText(R.id.value_drawn, cell(readings?.usedSinceBaselineKWh ?: readings?.dischargeTotalKWh, 1, "kWh", uncertain))
        views.setTextViewText(R.id.value_solar, cell(readings?.pvPowerW, 0, "W", uncertain))
        views.setTextViewText(R.id.value_load, cell(readings?.loadPowerW, 0, "W", uncertain))
    }

    fun cell(value: Double?, decimals: Int, unit: String, uncertain: Boolean): String = when {
        value == null -> if (uncertain) "?" else "–"
        else -> "${formatNumber(value, decimals)} $unit${if (uncertain) " ?" else ""}"
    }
}
