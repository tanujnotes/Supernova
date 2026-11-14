package com.supernova.launcher.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

// A paused work app's icon: grey, lifted halfway to white and a little see
// through, the way the system launcher shows apps it can't open until work
// apps are turned back on. Drawn through a layer, so it works on any icon
// (adaptive, pack, folder plate, monogram) without touching the shared,
// cached drawable underneath.
object PausedIcon {

    fun of(icon: Drawable?, paused: Boolean): Drawable? =
        if (icon == null || !paused) icon else PausedIconDrawable(icon)

    fun of(icon: Drawable?, state: LauncherUiState, userSerial: Long): Drawable? =
        of(icon, userSerial in state.pausedWorkProfiles)

    // An item's part of a bind signature (home pages, the dock): pausing or
    // turning work apps back on changes only how their icons look.
    fun mark(state: LauncherUiState, userSerial: Long): String =
        if (userSerial in state.pausedWorkProfiles) ":paused" else ""

    // Grey first, then each channel halfway to white, then the alpha.
    internal fun matrix(): ColorMatrix {
        val scale = 1f - BRIGHTNESS
        val add = FULL_CHANNEL * BRIGHTNESS
        val lift = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, add,
                0f, scale, 0f, 0f, add,
                0f, 0f, scale, 0f, add,
                0f, 0f, 0f, ALPHA, 0f
            )
        )
        return ColorMatrix().apply {
            setSaturation(0f)
            postConcat(lift)
        }
    }

    private const val FULL_CHANNEL = 255f
    private const val BRIGHTNESS = 0.5f
    private const val ALPHA = 0.8f
}

private class PausedIconDrawable(private val icon: Drawable) : Drawable() {

    private val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(PausedIcon.matrix()) }

    override fun draw(canvas: Canvas) {
        // The icon may be the catalog's shared instance, drawn elsewhere at
        // other bounds: borrow them for this draw only.
        val saved = icon.copyBounds()
        icon.bounds = bounds
        val layer = canvas.saveLayer(null, paint)
        icon.draw(canvas)
        canvas.restoreToCount(layer)
        icon.bounds = saved
    }

    override fun getIntrinsicWidth(): Int = icon.intrinsicWidth

    override fun getIntrinsicHeight(): Int = icon.intrinsicHeight

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    // The grey look is the filter; another one would undo it.
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
