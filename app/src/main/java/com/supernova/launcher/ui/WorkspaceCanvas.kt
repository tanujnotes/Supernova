package com.supernova.launcher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.roundToInt

// An icon's left (or top) edge along one side of the canvas: its centre at
// `center` of `extent`, pulled back in where that would hang its `size` over
// an edge.
internal fun iconOrigin(center: Float, extent: Int, size: Int): Int =
    (center.coerceIn(0f, 1f) * extent - size / 2f).roundToInt().coerceIn(0, max(0, extent - size))

// A widget's left (or top) edge along one side of the canvas: `cell` cells of
// the `count` across `extent`, then `inset` in. Cells are whole pixels, as the
// drop maths (workspacePlacement) and the resize frame count them.
internal fun widgetOrigin(cell: Float, count: Int, extent: Int, inset: Int): Int =
    (cell * cellSize(extent, count)).roundToInt() + inset

// Its width (or height): `span` of those cells, less the inset at both ends.
internal fun widgetSize(span: Float, count: Int, extent: Int, inset: Int): Int =
    max(1, (span * cellSize(extent, count)).roundToInt() - inset * 2)

// One of the `count` whole-pixel cells across `extent`.
internal fun cellSize(extent: Int, count: Int): Int = max(1, extent / max(1, count))

// A workspace page's canvas. Items are placed against the canvas's size at
// every layout rather than the one it had at bind. The canvas can change size
// after the page binds - on a cold start the page binds before the canvas has
// a size at all, and the dock's real height lands after the first layout and
// the pager shrinks by it - and items placed once stayed where the old size
// put them: icons lower than drops land, a bottom-row icon cut off by the
// canvas edge, and widgets sized from the display rather than the canvas.
//
// Icons carry a normalized centre (IconParams) and are laid out on it here,
// at least a cell wide: an icon owns its cell, so its label runs the
// cell's width before it ellipsizes, not just the icon's. An icon bigger
// than its cell keeps its own width. Widgets carry their cells
// (WidgetParams), which become their margins and size at every measure, and
// so does the resize frame around one, with no inset.
//
// Laid out, not moved with x/y, so translation is left to the reorder
// preview: a relayout in the middle of a preview doesn't snap a shifted item
// back home.
class WorkspaceCanvas @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    // `cols` is the grid's column count, which sets the cell width an icon
    // widens to; 0 leaves it at its own.
    class IconParams(val centerX: Float, val centerY: Float, val cols: Int = 0) :
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    // `spanX` x `spanY` cells of the `cols` x `rows` grid from (`cellX`,
    // `cellY`), inset on every side. Floats so the resize frame can slide
    // itself and the widget between two snaps; a bound widget sits on whole
    // cells. LEFT, not START: cells count from the left in either layout
    // direction, as drops do.
    class WidgetParams(
        cellX: Int,
        cellY: Int,
        spanX: Int,
        spanY: Int,
        val cols: Int,
        val rows: Int,
        // Settable, so a padding being picked can preview on the widget.
        var inset: Int
    ) : FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT
    ) {
        var cellX = cellX.toFloat()
        var cellY = cellY.toFloat()
        var spanX = spanX.toFloat()
        var spanY = spanY.toFloat()
    }

    // The canvas matches its page, so it is always measured at an exact size:
    // the one the widgets' cells are worked out against, before FrameLayout
    // measures and places them.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        for (i in 0 until childCount) {
            val params = getChildAt(i).layoutParams as? WidgetParams ?: continue
            params.leftMargin = widgetOrigin(params.cellX, params.cols, width, params.inset)
            params.topMargin = widgetOrigin(params.cellY, params.rows, height, params.inset)
            params.width = widgetSize(params.spanX, params.cols, width, params.inset)
            params.height = widgetSize(params.spanY, params.rows, height, params.inset)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val params = child.layoutParams as? IconParams ?: continue
            if (params.cols <= 0 || child.visibility == GONE) continue
            val cell = cellSize(width, params.cols)
            if (child.measuredWidth >= cell) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY),
                getChildMeasureSpec(heightMeasureSpec, paddingTop + paddingBottom, params.height)
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val width = right - left
        val height = bottom - top
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val params = child.layoutParams as? IconParams ?: continue
            if (child.visibility == GONE) continue
            val x = iconOrigin(params.centerX, width, child.measuredWidth)
            val y = iconOrigin(params.centerY, height, child.measuredHeight)
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }
}
