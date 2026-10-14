package com.supernova.launcher.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

// Stand-in mark for a search provider whose app is not installed: its first
// letter on a filled circle. Keeps the no-results row uniform without shipping
// third-party brand assets.
class MonogramDrawable(
    private val letter: String,
    private val circleColor: Int,
    private val textColor: Int
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = circleColor }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun draw(canvas: Canvas) {
        val b: Rect = bounds
        if (b.isEmpty) return
        val radius = minOf(b.width(), b.height()) / 2f
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), radius, fill)
        text.textSize = radius
        // Centre on the glyph's own box, not the font's line box.
        val offset = (text.descent() + text.ascent()) / 2f
        canvas.drawText(letter, b.exactCenterX(), b.exactCenterY() - offset, text)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        text.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        text.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
