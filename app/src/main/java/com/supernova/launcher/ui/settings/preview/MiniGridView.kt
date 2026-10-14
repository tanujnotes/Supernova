package com.supernova.launcher.ui.settings.preview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

// rows x cols rounded cells; a set() with new counts just invalidates -
// the paint and rect are reused across draws. Shared by the GRID settings
// preview and the desktop grid dialog.
class MiniGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var cellColor = 0
        set(value) {
            field = value
            paint.color = value
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cell = RectF()
    private var rows = 5
    private var cols = 4

    fun set(rows: Int, cols: Int) {
        if (this.rows == rows && this.cols == cols) return
        this.rows = rows
        this.cols = cols
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val gap = 3f * density
        val radius = 3f * density
        val cellWidth = (width - gap * (cols - 1)) / cols
        val cellHeight = (height - gap * (rows - 1)) / rows
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val left = col * (cellWidth + gap)
                val top = row * (cellHeight + gap)
                cell.set(left, top, left + cellWidth, top + cellHeight)
                canvas.drawRoundRect(cell, radius, radius, paint)
            }
        }
    }
}
