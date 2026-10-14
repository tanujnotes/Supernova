package com.supernova.launcher.onboarding

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.View
import com.supernova.launcher.data.IconShape
import kotlin.math.min

// One icon shape, filled flat: the Icon style page's sample icons and the
// shapes to pick from on the first run. SYSTEM draws the
// device's own adaptive-icon mask, which is what it leaves an adaptive icon
// with. `more` draws the hexagon with three dots that opens the rest of the
// shapes.
class ShapeSwatchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var shape: IconShape = IconShape.SYSTEM
        set(value) {
            if (field == value) return
            field = value
            path = null
            invalidate()
        }

    var color: Int = Color.GRAY
        set(value) {
            field = value
            paint.color = value
            invalidate()
        }

    var more: Boolean = false
        set(value) {
            field = value
            path = null
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@ShapeSwatchView.color }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var path: Path? = null
    private var pathSize = 0f

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val drawn = if (more) IconShape.HEXAGON else shape
        if (path == null || pathSize != size) {
            path = if (drawn == IconShape.SYSTEM) systemMask(size) else drawn.pathFor(size)
            pathSize = size
        }
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        canvas.save()
        canvas.translate(left, top)
        path?.let { canvas.drawPath(it, paint) } ?: canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        if (more) {
            val radius = size * DOT_RADIUS
            val gap = size * DOT_GAP
            for (i in -1..1) canvas.drawCircle(size / 2f, size / 2f + i * gap, radius, dotPaint)
        }
        canvas.restore()
    }

    // The mask this device gives adaptive icons, at this size.
    private fun systemMask(size: Float): Path? = runCatching {
        val drawable = AdaptiveIconDrawable(ColorDrawable(Color.BLACK), ColorDrawable(Color.BLACK))
        drawable.setBounds(0, 0, size.toInt(), size.toInt())
        Path(drawable.iconMask)
    }.getOrNull()

    private companion object {
        const val DOT_RADIUS = 0.055f
        const val DOT_GAP = 0.16f
    }
}
