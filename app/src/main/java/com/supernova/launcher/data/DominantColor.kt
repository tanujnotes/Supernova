package com.supernova.launcher.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.get
import androidx.core.graphics.scale

// Cheap dominant color: downsample to 8x8 and average the saturated pixels,
// falling back to every opaque pixel for grayscale icons. Dynamic badges and
// the Circle launch animation take their color from it.
object DominantColor {
    private const val SAMPLE = 8
    private const val OPAQUE_ALPHA = 128
    private const val MIN_SATURATION = 0.35f
    private const val MIN_VALUE = 0.15f
    private const val HSV = 3

    fun of(bitmap: Bitmap?): Int? {
        bitmap ?: return null
        val sample = bitmap.scale(SAMPLE, SAMPLE)
        val all = Sum()
        val saturated = Sum()
        val hsv = FloatArray(HSV)
        for (y in 0 until SAMPLE) {
            for (x in 0 until SAMPLE) {
                val pixel = sample[x, y]
                if (Color.alpha(pixel) < OPAQUE_ALPHA) continue
                all.add(pixel)
                Color.colorToHSV(pixel, hsv)
                if (hsv[1] >= MIN_SATURATION && hsv[2] >= MIN_VALUE) saturated.add(pixel)
            }
        }
        sample.recycle()
        return saturated.average() ?: all.average()
    }

    private class Sum {
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0

        fun add(pixel: Int) {
            r += Color.red(pixel)
            g += Color.green(pixel)
            b += Color.blue(pixel)
            count++
        }

        fun average(): Int? =
            if (count == 0) null else Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }
}
