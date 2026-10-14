package com.supernova.launcher.ui

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

// The drawer list's layout: a grid, one column wide for the list style,
// whose scrollbar adds up real rows. RecyclerView's own estimate takes the
// average height per item on screen and multiplies it out, so the frequent
// row - one item, two rows tall - and a short last row each shifted it as
// they came and went, and the fast scrollbar's thumb jumped. Here each row
// is measured as it is laid out and remembered (ScrollRows), and the scroll
// position is the rows above the top one plus how far that one has gone.
// The ends come out exact whatever a row never seen is guessed at: at the
// top nothing is above, and at the bottom the same guess is in the total.
class DrawerLayoutManager(context: Context, spanCount: Int) : GridLayoutManager(context, spanCount) {

    private val rows = ScrollRows()
    // Every change to the items or the columns ends in a layout pass, and
    // the children on screen match the rows grouped after it; in between,
    // they still match the old ones.
    private var regroup = true
    private var rowsWidth = 0

    override fun onLayoutCompleted(state: RecyclerView.State?) {
        super.onLayoutCompleted(state)
        regroup = true
    }

    override fun computeVerticalScrollRange(state: RecyclerView.State): Int {
        if (syncRows() == null) return 0
        return paddingTop + rows.total + paddingBottom
    }

    override fun computeVerticalScrollExtent(state: RecyclerView.State): Int =
        if (childCount == 0) 0 else height

    override fun computeVerticalScrollOffset(state: RecyclerView.State): Int {
        val top = syncRows() ?: return 0
        val params = top.layoutParams as RecyclerView.LayoutParams
        val row = rows.rowOf(params.viewLayoutPosition)
        return (rows.top(row) + paddingTop - (getDecoratedTop(top) - params.topMargin)).coerceAtLeast(0)
    }

    // Puts the list `offset` down from its top by the same rows the
    // scrollbar reads, so a thumb dragged there lands the list where the
    // thumb is. Takes effect on the next layout pass.
    fun scrollToOffset(offset: Int) {
        syncRows()
        if (rows.rowCount == 0) return
        val row = rows.rowAt(offset)
        scrollToPositionWithOffset(rows.firstPosition(row), rows.top(row) - offset)
    }

    // The item `offset` down the list.
    fun positionAt(offset: Int): Int {
        syncRows()
        return rows.positionAt(offset)
    }

    // Brings the rows up to date with the items and measures the ones laid
    // out; returns the topmost child, null when there is none.
    private fun syncRows(): View? {
        if (regroup) {
            regroup = false
            rows.group(itemCount, spanCount) { spanSizeLookup.getSpanSize(it) }
        }
        if (width != rowsWidth) {
            rowsWidth = width
            rows.forgetHeights()
        }
        var top: View? = null
        var topPosition = Int.MAX_VALUE
        for (index in 0 until childCount) {
            val child = getChildAt(index) ?: continue
            val params = child.layoutParams as RecyclerView.LayoutParams
            val position = params.viewLayoutPosition
            if (!params.isItemRemoved && position in 0 until rows.itemCount) {
                val height = getDecoratedMeasuredHeight(child) + params.topMargin + params.bottomMargin
                rows.record(rows.rowOf(position), height)
                if (position < topPosition) {
                    topPosition = position
                    top = child
                }
            }
        }
        return top
    }
}
