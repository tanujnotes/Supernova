package com.supernova.launcher.feed

import kotlin.math.abs

// Snap scrolling for "Fill the page" cards, kept free of views so it can be
// tested. Tops and bottoms include a card's margins; contentTop is the top
// of the list's visible area. Only full-page cards are ever snap targets:
// with none near the settle point, or none in the feed, the list scrolls
// freely, exactly as it would without snapping.
object FillSnap {

    const val NONE = -1

    data class Row(val position: Int, val top: Int, val bottom: Int, val fill: Boolean)

    // What a fling is decided against: the laid-out rows, the adapter's
    // size and which positions are full-page cards (laid out or not).
    class Frame(
        rows: List<Row>,
        val itemCount: Int,
        val isFill: (Int) -> Boolean,
        val contentTop: Int,
        val tolerance: Int
    ) {
        val laid = rows.sortedBy { it.position }
    }

    // The list came to rest (a drag let go, a free fling ended): the
    // full-page card whose top is nearest the top edge, if it is within
    // half a page of it. Between two full pages, one of them always is.
    // forward is the way the list last moved (towards later items). A snap
    // never pulls back against it except between two full pages, so normal
    // cards next to a full page (the end of the feed, say) stay where they
    // were scrolled to.
    fun settleTarget(
        rows: List<Row>,
        contentTop: Int,
        contentHeight: Int,
        forward: Boolean,
        isFill: (Int) -> Boolean
    ): Row? {
        val nearest = rows.filter { it.fill }
            .minByOrNull { abs(it.top - contentTop) }
            ?.takeIf { abs(it.top - contentTop) <= contentHeight / 2 }
        val against = nearest != null &&
            if (forward) nearest.top < contentTop else nearest.top > contentTop
        val betweenPages = nearest != null && isFill(if (forward) nearest.position + 1 else nearest.position - 1)
        return nearest?.takeIf { !against || betweenPages }
    }

    // A fling: the adapter position to snap to, or NONE for a free fling.
    // distance is the fling's natural travel (positive towards later items).
    fun flingTarget(frame: Frame, distance: Int): Int = when {
        frame.laid.isEmpty() || frame.itemCount == 0 -> NONE
        else -> pageTarget(frame, distance > 0)
            ?: if (distance > 0) reachForward(frame, distance) else reachBack(frame, distance)
    }

    // On a full page (a full-page card across the top edge), a fling turns
    // exactly one page: to the next full-page card, or back to this card's
    // top, then to the previous full-page card. Null when it doesn't apply.
    private fun pageTarget(frame: Frame, forward: Boolean): Int? {
        val edge = frame.contentTop + frame.tolerance
        val page = frame.laid.firstOrNull { it.fill && it.top <= edge && it.bottom > edge }
        return when {
            page == null -> null
            forward -> (page.position + 1).takeIf { it < frame.itemCount && frame.isFill(it) }
            page.top < frame.contentTop - frame.tolerance -> page.position
            else -> (page.position - 1).takeIf { it >= 0 && frame.isFill(it) }
        }
    }

    // The first full-page card below the top edge the fling reaches; past
    // the laid-out rows, the next full-page card at all, so a long fling
    // never skips one.
    private fun reachForward(frame: Frame, distance: Int): Int {
        val reach = frame.contentTop + distance
        val ahead = frame.laid.firstOrNull { it.fill && it.top > frame.contentTop + frame.tolerance }
        val last = frame.laid.last()
        return when {
            ahead != null -> if (ahead.top <= reach) ahead.position else NONE
            reach <= last.bottom -> NONE
            else -> (last.position + 1 until frame.itemCount).firstOrNull(frame.isFill) ?: NONE
        }
    }

    private fun reachBack(frame: Frame, distance: Int): Int {
        val reach = frame.contentTop + distance
        val above = frame.laid.lastOrNull { it.fill && it.top < frame.contentTop - frame.tolerance }
        val first = frame.laid.first()
        return when {
            above != null -> if (above.top >= reach) above.position else NONE
            reach >= first.top -> NONE
            else -> (first.position - 1 downTo 0).firstOrNull(frame.isFill) ?: NONE
        }
    }
}
