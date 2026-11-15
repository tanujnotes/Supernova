package com.supernova.launcher.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import kotlin.math.min

// With gesture navigation a swipe in from a side edge is system Back, which
// does nothing on the home screen. Android lets an app keep up to 200dp of
// each side edge: the home pages take a strip mid-way down each side, so an
// edge swipe there flips the page as it does further in. Bottom swipes stay
// the system's. The left strip also serves the feed's edge swipe
// (FeedController), which starts in its outer 24dp: the feed once kept a
// strip of its own near the bottom instead, so a mid-height edge swipe ran
// Back, and with infinite scroll on (no page-0 pull) the feed was out of
// reach. Anything over the home screen (drawer, search, edit mode, previews,
// the feed) gives the edges back to Back, which closes it.
class HomeEdgeSwipes(
    private val pager: View,
    private val homeInFront: () -> Boolean
) {
    private var applied: List<Any>? = null

    // Checked before every frame: the overlays open and close through
    // several paths, and the check is a handful of booleans.
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        update()
        true
    }

    init {
        pager.watchTreeWhileInWindow(
            add = { addOnPreDrawListener(preDraw) },
            remove = { removeOnPreDrawListener(preDraw) }
        )
    }

    fun update() {
        val home = homeInFront()
        val key = listOf(home, pager.width, pager.height)
        if (key == applied) return
        applied = key
        val rects = if (home && pager.height > 0) {
            val strip = min(pager.context.dp(EDGE_LIMIT_DP), pager.height / 2)
            val top = (pager.height - strip) / 2
            val width = pager.context.dp(STRIP_WIDTH_DP)
            listOf(
                Rect(0, top, width, top + strip),
                Rect(pager.width - width, top, pager.width, top + strip)
            )
        } else {
            emptyList()
        }
        ViewCompat.setSystemGestureExclusionRects(pager, rects)
    }

    private companion object {
        // Android's per-edge allowance.
        const val EDGE_LIMIT_DP = 200
        // Wider than the system's Back zone at its most sensitive.
        const val STRIP_WIDTH_DP = 48
    }
}
