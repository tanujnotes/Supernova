package com.supernova.launcher.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

// The mark under an icon that has a swipe action bound to it. An icon swipe is
// otherwise invisible - nothing about the icon says a flick will do anything -
// so a short bar marks it: a rounded line across the bottom of the icon box,
// drawn over the icon as its foreground so it costs no layout and never moves
// the label.
//
// Direction is not encoded. Up, down and both look the same: the mark says
// "this icon has a swipe", and the popup menu says which way it goes.
class SwipeIndicatorDrawable(
    density: Float,
    color: Int
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    // A shadow, because the mark is drawn over the wallpaper as often as over
    // an icon and a plain light bar disappears on a pale one.
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.argb(90, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val thickness = 2f * density
    private val inset = 1f * density
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        // A third of the icon's width, centred: wide enough to read as
        // deliberate, narrow enough not to look like an underline.
        val width = b.width() / 3f
        val left = b.exactCenterX() - width / 2f
        val top = b.bottom - thickness - inset
        rect.set(left, top, left + width, top + thickness)
        val radius = thickness / 2f
        canvas.drawRoundRect(
            rect.left, rect.top + inset, rect.right, rect.bottom + inset, radius, radius, shadow
        )
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
