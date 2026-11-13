package com.supernova.launcher.ui

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

// The horizontally paged folder grid: pages of `columns` x `rows` side by
// side, each filled row by row, as the folder's preview reads its members.
// A horizontal GridLayoutManager fills column by column, so the same folder
// read across in its preview and down in its window. Cells are the page's
// width over `columns` and its height over `rows`; a tile centres across
// its cell and sits at its top, so icons line up whatever their labels.
// Only the pages in view are laid out.
class PagedGridLayoutManager(private val columns: Int, private val rows: Int) : RecyclerView.LayoutManager() {

    // How far the grid is scrolled; a reorder or a removal leaves it be, so
    // the page in view stays in view.
    private var offset = 0

    private val perPage: Int get() = columns * rows
    private val pageWidth: Int get() = width - paddingLeft - paddingRight
    private val cellWidth: Int get() = pageWidth / columns
    private val cellHeight: Int get() = (height - paddingTop - paddingBottom) / rows

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams =
        RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun canScrollHorizontally(): Boolean = true

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (state.itemCount == 0 || pageWidth <= 0) {
            removeAndRecycleAllViews(recycler)
            offset = 0
            return
        }
        offset = offset.coerceIn(0, maxOffset(state.itemCount))
        fill(recycler, state.itemCount)
    }

    override fun scrollHorizontallyBy(dx: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        val next = if (pageWidth > 0) (offset + dx).coerceIn(0, maxOffset(state.itemCount)) else offset
        val moved = next - offset
        if (moved != 0) {
            offset = next
            fill(recycler, state.itemCount)
            // Tiles scrolled out of view go back to the pool; after a layout
            // RecyclerView does this itself, animating the ones that leave.
            recycler.scrapList.toList().forEach { holder -> recycler.recycleView(holder.itemView) }
        }
        return moved
    }

    override fun computeHorizontalScrollOffset(state: RecyclerView.State): Int = offset

    override fun computeHorizontalScrollExtent(state: RecyclerView.State): Int = pageWidth

    override fun computeHorizontalScrollRange(state: RecyclerView.State): Int =
        if (state.itemCount == 0) 0 else maxOffset(state.itemCount) + pageWidth

    // The last page's start: one page width per page after the first.
    private fun maxOffset(itemCount: Int): Int =
        if (perPage == 0) 0 else ((itemCount - 1).coerceAtLeast(0) / perPage) * pageWidth

    // Every tile on a page that shows any part of the viewport, at its cell.
    private fun fill(recycler: RecyclerView.Recycler, itemCount: Int) {
        detachAndScrapAttachedViews(recycler)
        val firstPage = offset / pageWidth
        val lastPage = (offset + pageWidth - 1) / pageWidth
        val first = firstPage * perPage
        val last = ((lastPage + 1) * perPage).coerceAtMost(itemCount) - 1
        for (position in first..last) {
            val view = recycler.getViewForPosition(position)
            addView(view)
            place(view, position)
        }
    }

    private fun place(view: View, position: Int) {
        val page = position / perPage
        val inPage = position % perPage
        val cellLeft = paddingLeft + page * pageWidth + (inPage % columns) * cellWidth - offset
        val cellTop = paddingTop + (inPage / columns) * cellHeight
        measureChildWithMargins(view, 0, 0)
        val w = getDecoratedMeasuredWidth(view)
        val left = cellLeft + (cellWidth - w) / 2
        layoutDecoratedWithMargins(view, left, cellTop, left + w, cellTop + getDecoratedMeasuredHeight(view))
    }
}
