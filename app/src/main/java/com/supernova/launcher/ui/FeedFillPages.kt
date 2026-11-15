package com.supernova.launcher.ui

import android.util.Size
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SnapHelper
import com.supernova.launcher.feed.FillSnap

// "Fill the page" cards in the feed list: their widget is sized to the
// list's content box (CardViewBinder.fillSpace), they are bound again when
// that box changes (rotation, a resize, the header rows above the list
// shown or hidden, the bottom inset), and the list snaps to them as it
// settles (FillSnap). Normal cards scroll freely; with no full-page card
// the snap never targets anything.
class FeedFillPages(
    private val adapter: FeedAdapter,
    private val binder: CardViewBinder,
    // A card is being dragged to a new place: no snapping under it.
    private val cardHeld: () -> Boolean
) {
    private var applied: Size? = null
    // The way the list last moved: towards later items or back.
    private var forward = true

    fun attach(list: RecyclerView) {
        binder.fillSpace = { contentBox(list) }
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val space = contentBox(list)
            if (space != applied) {
                applied = space
                // Not from inside this layout pass.
                list.post(::rebindFilled)
            }
        }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) forward = dy > 0
            }
        })
        Snap().attachToRecyclerView(list)
    }

    private fun rebindFilled() {
        adapter.rowsShown.forEachIndexed { index, row ->
            if (row is FeedAdapter.Row.Custom && row.card.fillPage) adapter.notifyItemChanged(index)
        }
    }

    private fun isFill(position: Int): Boolean =
        (adapter.rowsShown.getOrNull(position) as? FeedAdapter.Row.Custom)?.card?.fillPage == true

    private fun contentBox(list: RecyclerView) = Size(
        list.width - list.paddingLeft - list.paddingRight,
        list.height - list.paddingTop - list.paddingBottom
    )

    private inner class Snap : SnapHelper() {

        private fun rows(lm: RecyclerView.LayoutManager): List<Pair<FillSnap.Row, View>> =
            (0 until lm.childCount).mapNotNull { lm.getChildAt(it) }.map { child ->
                val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
                val position = lm.getPosition(child)
                FillSnap.Row(
                    position,
                    lm.getDecoratedTop(child) - (margins?.topMargin ?: 0),
                    lm.getDecoratedBottom(child) + (margins?.bottomMargin ?: 0),
                    isFill(position)
                ) to child
            }

        override fun calculateDistanceToFinalSnap(lm: RecyclerView.LayoutManager, targetView: View): IntArray {
            val margins = targetView.layoutParams as? ViewGroup.MarginLayoutParams
            val top = lm.getDecoratedTop(targetView) - (margins?.topMargin ?: 0)
            return intArrayOf(0, top - lm.paddingTop)
        }

        override fun findSnapView(lm: RecyclerView.LayoutManager): View? {
            val rows = if (cardHeld()) emptyList() else rows(lm)
            val target = FillSnap.settleTarget(
                rows.map { it.first }, lm.paddingTop, lm.height - lm.paddingTop - lm.paddingBottom,
                forward, ::isFill
            )
            return rows.firstOrNull { it.first == target }?.second
        }

        override fun findTargetSnapPosition(lm: RecyclerView.LayoutManager, velocityX: Int, velocityY: Int): Int {
            val rows = rows(lm).map { it.first }
            // Nothing to snap to: the fling is the list's own, as always.
            if (cardHeld() || rows.isEmpty() || adapter.rowsShown.indices.none(::isFill)) {
                return RecyclerView.NO_POSITION
            }
            val distance = calculateScrollDistance(velocityX, velocityY)[1]
            val density = lm.getChildAt(0)?.resources?.displayMetrics?.density ?: 1f
            val frame = FillSnap.Frame(rows, lm.itemCount, ::isFill, lm.paddingTop, (TOLERANCE_DP * density).toInt())
            val target = FillSnap.flingTarget(frame, distance)
            return if (target == FillSnap.NONE) RecyclerView.NO_POSITION else target
        }
    }

    private companion object {
        const val TOLERANCE_DP = 2
    }
}
