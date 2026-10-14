package com.supernova.launcher.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

// The swipe indicator: a small upward chevron above the dock that says a
// swipe up opens the drawer. It is about 11 x 6dp with a 2dp stroke; it gets the same soft shadow as
// the icon swipe mark (SwipeIndicatorDrawable), since it sits on the
// wallpaper and a light stroke disappears on a pale one.
class SwipeArrowDrawable(
    private val density: Float,
    color: Int
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val shadow = Paint(paint).apply {
        this.color = Color.argb(90, 0, 0, 0)
    }

    private val path = Path()

    override fun getIntrinsicWidth(): Int = ((WIDTH_DP + STROKE_DP) * density).toInt() + 1

    override fun getIntrinsicHeight(): Int = ((HEIGHT_DP + STROKE_DP + SHADOW_DP) * density).toInt() + 1

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val halfWidth = WIDTH_DP * density / 2f
        val halfHeight = HEIGHT_DP * density / 2f
        val cx = b.exactCenterX()
        // Centred with the shadow's drop below it counted in.
        val cy = b.exactCenterY() - SHADOW_DP * density / 2f
        path.rewind()
        path.moveTo(cx - halfWidth, cy + halfHeight)
        path.lineTo(cx, cy - halfHeight)
        path.lineTo(cx + halfWidth, cy + halfHeight)
        canvas.save()
        canvas.translate(0f, SHADOW_DP * density)
        canvas.drawPath(path, shadow)
        canvas.restore()
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    // The chevron's path; the round stroke adds its own width on top, for
    // about 11 x 6dp overall.
    private companion object {
        const val WIDTH_DP = 9f
        const val HEIGHT_DP = 4.4f
        const val STROKE_DP = 1.8f
        const val SHADOW_DP = 1f
    }
}
