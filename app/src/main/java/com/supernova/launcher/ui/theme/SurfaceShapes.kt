package com.supernova.launcher.ui.theme

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

object SurfaceShapes {

    // Background for a resolved surface. radiiPx is [TL, TR, BR, BL]; null
    // radii means the surface's built-in shape (uniform defaultRadiusPx).
    fun background(color: Int, radiiPx: FloatArray?, defaultRadiusPx: Float): Drawable =
        GradientDrawable().apply {
            setColor(color)
            if (radiiPx == null) {
                cornerRadius = defaultRadiusPx
            } else {
                // GradientDrawable wants [TL, TL, TR, TR, BR, BR, BL, BL].
                cornerRadii = floatArrayOf(
                    radiiPx[0], radiiPx[0], radiiPx[1], radiiPx[1],
                    radiiPx[2], radiiPx[2], radiiPx[3], radiiPx[3]
                )
            }
        }

    // Re-colors a background made by background() in place, so callers don't
    // need to rebuild on alpha/color changes.
    fun setColor(drawable: Drawable?, color: Int) {
        (drawable as? GradientDrawable)?.setColor(color)
    }
}
