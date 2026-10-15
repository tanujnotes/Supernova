package com.supernova.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import com.supernova.launcher.data.GlassPainter
import com.supernova.launcher.data.IconGlass

// The home search bar as a glass pill (DesktopSearchBarConfig.glass): the
// bar's own colour as a pane, lit like the glass icons. Painted once per
// size into a bitmap, so a frame only draws that.
class GlassPillDrawable(
    private val glass: IconGlass,
    private val color: Int,
    private val radius: Float
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var cache: Bitmap? = null

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        val bitmap = cache?.takeIf { it.width == bounds.width() && it.height == bounds.height() }
            ?: render(bounds.width(), bounds.height()).also {
                cache?.recycle()
                cache = it
            }
        canvas.drawBitmap(bitmap, bounds.left.toFloat(), bounds.top.toFloat(), paint)
    }

    private fun render(width: Int, height: Int): Bitmap {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val path = Path().apply {
            val corner = radius.coerceAtMost(minOf(width, height) / 2f)
            addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), corner, corner, Path.Direction.CW)
        }
        canvas.withClip(path) { painter.drawPane(this, glass, color, width, height) }
        painter.drawEdge(canvas, path, glass, width, height)
        return bitmap
    }

    // The pill's outline, for the ripple and the view's clip.
    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius.coerceAtMost(minOf(bounds.width(), bounds.height()) / 2f))
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        if (paint.alpha == alpha) return
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = paint.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        // Stateless apart from its grain tile, which every pill can share.
        val painter = GlassPainter()
    }
}
