package com.supernova.launcher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

// A fixed-column grid of equal-width cells, wrapping its rows' heights. The
// search window's app tiles use it in place of a nested RecyclerView: a
// query yields a handful of tiles and the zero state a few dozen, so there
// is nothing to recycle and everything to gain from a layout that knows its
// width at measure time.
class SearchGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    var columns: Int = 4
        set(value) {
            field = value.coerceAtLeast(1)
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cell = ((width - paddingLeft - paddingRight) / columns).coerceAtLeast(1)
        var total = 0
        var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            rowHeight = maxOf(rowHeight, child.measuredHeight)
            if ((index + 1) % columns == 0 || index == childCount - 1) {
                total += rowHeight
                rowHeight = 0
            }
        }
        setMeasuredDimension(width, total + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cell = ((r - l - paddingLeft - paddingRight) / columns).coerceAtLeast(1)
        var top = paddingTop
        var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            val column = index % columns
            val left = paddingLeft + column * cell
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
            rowHeight = maxOf(rowHeight, child.measuredHeight)
            if (column == columns - 1) {
                top += rowHeight
                rowHeight = 0
            }
        }
    }
}
