package com.supernova.launcher.ui

import kotlin.math.abs
import kotlin.math.floor

// The page indicator's arithmetic, kept apart from the view so it can be
// tested. Positions are in pages: 1.25 is a quarter of the way from page 1 to
// page 2. Past the last page is the infinite-scroll wrap round to the first.
internal object PageIndicatorMath {

    // Within this much of a page the stretching dot settles onto it
    // (Launcher3's SHIFT_THRESHOLD).
    const val SETTLE_ZONE = 0.1f

    // Where the DOTS style's dot is headed for a scroll position: onto a page
    // it is within SETTLE_ZONE of, stretched across both neighbours in
    // between. Wrapping round from the last page it stays on the last until
    // the move is half done, then heads for the first.
    fun dotTarget(scroll: Float, pageCount: Int): Float {
        val last = (pageCount - 1).toFloat()
        if (scroll > last) return if (scroll - last > 0.5f) 0f else last
        val left = floor(scroll)
        val fraction = scroll - left
        return when {
            fraction < SETTLE_ZONE -> left
            fraction > 1f - SETTLE_ZONE -> left + 1f
            else -> left + 0.5f
        }
    }

    // The next stop on the dot's way to its target. It moves half a page at a
    // time, so it first stretches over the next page and then lets go of the
    // one it left (Launcher3's animateToPosition), and goes straight there
    // from within half a page - or from over a page away, the wrap from one
    // end to the other, which would otherwise crawl across every page.
    fun nextDotStop(position: Float, target: Float): Float {
        val distance = abs(target - position)
        return when {
            distance <= 0.5f || distance > 1f -> target
            target > position -> position + 0.5f
            else -> position - 0.5f
        }
    }

    // The stretching dot's left and right edges, measured from the first
    // dot's centre, for a position between pages: over the first half page
    // its right edge runs on to cover the next dot, over the second its left
    // edge catches up.
    fun dotSpan(position: Float, radius: Float, spacing: Float, out: FloatArray) {
        val start = floor(position)
        val delta = position - start
        var left = start * spacing - radius
        var right = left + 2f * radius
        if (delta < 0.5f) {
            right += delta * spacing * 2f
        } else {
            right += spacing
            left += (delta - 0.5f) * spacing * 2f
        }
        out[0] = left
        out[1] = right
    }

    // A scroll position as the gliding styles draw it. Once a wrap from the
    // last page is half done the mark comes in from before the first page, so
    // it slides off one end and on at the other rather than running back
    // across the whole row.
    fun wrapped(scroll: Float, pageCount: Int): Float {
        val last = (pageCount - 1).toFloat()
        return if (scroll > last + 0.5f) scroll - pageCount else scroll
    }

    // The LINE style's left edge: a 1/pageCount share of the width, gliding
    // from the left end on the first page to the right end on the last.
    fun lineLeft(scroll: Float, pageCount: Int, width: Float): Float {
        if (pageCount <= 1) return 0f
        val segment = width / pageCount
        return wrapped(scroll, pageCount) / (pageCount - 1) * (width - segment)
    }
}
