package com.supernova.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import androidx.core.view.children
import androidx.core.view.isEmpty
import androidx.recyclerview.widget.RecyclerView

// A list whose rows fade out towards its top and bottom edges rather than
// being cut off there.
//
// The platform's own fading edge is no use to the drawer: it sits inside the
// list's padding, and this list scrolls through its bottom padding (under the
// navigation bar), so rows would fade out and then show again below the fade.
// Here the fade runs to the view's own edges, and each can be a different
// length - the bottom one takes in whatever the list scrolls under.
//
// The rows are faded themselves, to nothing, so whatever is behind the list
// shows through: there is no color to match.
//
// An edge fades only while there is more list beyond it, and grows in over
// the first stretch of scrolling, so the first and last rows at rest are
// never dimmed.
class EdgeFadeRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    var fadeTop: Int = 0
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var fadeBottom: Int = 0
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    // Opaque at 0, clear at 1 along y; DST_OUT takes that much of the rows
    // away, so 0 goes on the edge itself. One unit tall, stretched and placed
    // per edge by the matrix.
    private val gradient = LinearGradient(0f, 0f, 0f, 1f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val matrix = Matrix()
    private val paint = Paint().apply {
        shader = gradient
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    override fun draw(canvas: Canvas) {
        val top = fadeTop * strength(hiddenAbove(), fadeTop)
        val bottom = fadeBottom * strength(hiddenBelow(), fadeBottom)
        if (top < 1f && bottom < 1f) {
            super.draw(canvas)
            return
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        super.draw(canvas)
        if (top >= 1f) {
            // Clear at the edge, whole a fade's length in.
            matrix.setScale(1f, top)
            gradient.setLocalMatrix(matrix)
            // Set again after each matrix, as View's own fading edges do:
            // the paint picks the shader's new state up here.
            paint.shader = gradient
            canvas.drawRect(0f, 0f, w, top, paint)
        }
        if (bottom >= 1f) {
            // The same, upside down from the bottom edge.
            matrix.setScale(1f, -bottom)
            matrix.postTranslate(0f, h)
            gradient.setLocalMatrix(matrix)
            paint.shader = gradient
            canvas.drawRect(0f, h - bottom, w, h, paint)
        }
        canvas.restoreToCount(layer)
    }

    // How much of the list is out of sight past the top: the first item's
    // travel above its resting place, or more than any fade once it has gone.
    private fun hiddenAbove(): Int {
        val first = children.firstOrNull { getChildAdapterPosition(it) == 0 }
        return when {
            first != null -> paddingTop - (layoutManager?.getDecoratedTop(first) ?: paddingTop)
            isEmpty() -> 0
            else -> Int.MAX_VALUE
        }
    }

    // The same past the bottom, where the list comes to rest above its
    // padding.
    private fun hiddenBelow(): Int {
        val lastPosition = (adapter?.itemCount ?: 0) - 1
        val last = children.lastOrNull { getChildAdapterPosition(it) == lastPosition }
        val rest = height - paddingBottom
        return when {
            last != null -> (layoutManager?.getDecoratedBottom(last) ?: rest) - rest
            isEmpty() -> 0
            else -> Int.MAX_VALUE
        }
    }

    companion object {
        // 0..1: how much of an edge's fade shows with this much list hidden
        // past it. Full once a fade's length is hidden.
        fun strength(hidden: Int, length: Int): Float = when {
            length <= 0 || hidden <= 0 -> 0f
            hidden >= length -> 1f
            else -> hidden.toFloat() / length
        }
    }
}
