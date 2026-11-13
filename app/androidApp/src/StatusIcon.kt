package com.salahoard.mustnotify

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.drawable.IconCompat

/**
 * Renders a short text (the battery voltage, e.g. "12.9") as the notification's status-bar icon.
 * Status-bar icons are alpha masks: only the opaque pixels matter, the system tints them itself.
 */
object StatusIcon {
    private const val SIZE = 96

    fun forText(text: String): IconCompat {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = 80f
        }
        while (paint.measureText(text) > SIZE - 4 && paint.textSize > 20f) paint.textSize -= 2f
        val baseline = SIZE / 2f - (paint.descent() + paint.ascent()) / 2f
        Canvas(bitmap).drawText(text, SIZE / 2f, baseline, paint)
        return IconCompat.createWithBitmap(bitmap)
    }
}
