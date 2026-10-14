package com.supernova.launcher.ui.settings.preview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.supernova.launcher.R
import com.supernova.launcher.data.CustomShapeSpec
import com.supernova.launcher.data.IconShape
import kotlin.math.min

// Live preview for the custom icon shape editor: draws the CUSTOM path filled
// with the accent color, re-rendered whenever the spec changes.
class ShapePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var spec: CustomShapeSpec = CustomShapeSpec()
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resolveAccent()
    }

    private fun resolveAccent(): Int {
        val value = TypedValue()
        val resolved = context.theme.resolveAttribute(
            androidx.appcompat.R.attr.colorAccent, value, true
        )
        return if (resolved && value.resourceId != 0) {
            context.getColor(value.resourceId)
        } else if (resolved) {
            value.data
        } else {
            context.getColor(R.color.accent)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val path = IconShape.CUSTOM.pathFor(size, spec) ?: return
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}
