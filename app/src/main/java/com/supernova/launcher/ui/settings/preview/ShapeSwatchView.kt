package com.supernova.launcher.ui.settings.preview

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withTranslation
import com.supernova.launcher.data.CustomShapeSpec
import com.supernova.launcher.data.IconShape
import kotlin.math.min

// One tile of the icon shape sheet: the shape's own mask, filled in color.
// System follows the phone, so it has no mask of its own and draws as a
// dashed circle; Custom draws the user's shape in a dashed outline, as the
// one that opens its editor.
class ShapeSwatchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var shape = IconShape.CIRCLE
    private var custom: CustomShapeSpec? = null
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * density
        pathEffect = DashPathEffect(floatArrayOf(DASH_DP * density, GAP_DP * density), 0f)
    }

    fun set(shape: IconShape, custom: CustomShapeSpec?, color: Int) {
        this.shape = shape
        this.custom = custom
        fill.color = color
        outline.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val inset = outline.strokeWidth / 2f
        canvas.withTranslation((width - size) / 2f, (height - size) / 2f) {
            when (shape) {
                IconShape.SYSTEM -> drawCircle(size / 2f, size / 2f, size / 2f - inset, outline)
                IconShape.CUSTOM -> withTranslation(inset, inset) {
                    IconShape.CUSTOM.pathFor(size - inset * 2, custom)?.let { drawPath(it, outline) }
                }
                else -> shape.pathFor(size)?.let { drawPath(it, fill) }
            }
        }
    }

    private companion object {
        const val STROKE_DP = 2f
        const val DASH_DP = 5f
        const val GAP_DP = 4f
    }
}
