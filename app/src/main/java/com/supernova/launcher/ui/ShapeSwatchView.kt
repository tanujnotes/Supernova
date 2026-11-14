package com.supernova.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.view.View
import androidx.core.graphics.drawable.toDrawable
import com.supernova.launcher.data.CustomShapeSpec
import com.supernova.launcher.data.IconShape

// One icon shape in Edit's row of shapes: the shape itself, filled, in a
// box that rings when it is the pick. `outlined` draws it as an outline
// instead - Default, which follows the launcher's shape.
class ShapeSwatchView(context: Context) : View(context) {

    var shape: IconShape = IconShape.SYSTEM
    var custom: CustomShapeSpec? = null
    var outlined: Boolean = false
    var fillColor: Int = Color.GRAY
    var ringColor: Int = Color.BLUE

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RING_DP * density
    }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height).toFloat()
        val glyph = SHAPE_DP * density
        val path = pathOf(glyph)
        val left = (width - glyph) / 2f
        val top = (height - glyph) / 2f
        path.offset(left, top)
        fill.color = fillColor
        fill.style = if (outlined) Paint.Style.STROKE else Paint.Style.FILL
        fill.strokeWidth = OUTLINE_DP * density
        canvas.drawPath(path, fill)
        if (isSelected) {
            val inset = ring.strokeWidth / 2f
            box.set((width - side) / 2f + inset, (height - side) / 2f + inset,
                (width + side) / 2f - inset, (height + side) / 2f - inset)
            ring.color = ringColor
            val corner = CORNER_DP * density
            canvas.drawRoundRect(box, corner, corner, ring)
        }
    }

    // The shape at size x size; the platform's mask for System.
    private fun pathOf(size: Float): Path {
        shape.pathFor(size, custom)?.let { return it }
        val mask = AdaptiveIconDrawable(Color.BLACK.toDrawable(), null).iconMask
        val scale = size / MASK_SIZE
        return Path(mask).apply { transform(Matrix().apply { setScale(scale, scale) }) }
    }

    private companion object {
        const val SHAPE_DP = 28f
        const val RING_DP = 2f
        const val OUTLINE_DP = 2f
        const val CORNER_DP = 14f

        // AdaptiveIconDrawable's mask is drawn in a 100 x 100 box.
        const val MASK_SIZE = 100f
    }
}
