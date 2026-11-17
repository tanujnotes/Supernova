package com.supernova.launcher.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import kotlin.math.min

// With gesture navigation a swipe in from a side edge is system Back, which
// does nothing on the home screen. The home pages keep both side edges, so an
// edge swipe flips the page as it does further in. Android caps an app at
// 200dp per edge but lets the home app (the default launcher, opened as home)
// keep the whole edge; anywhere else SN keeps a 200dp strip mid-way down. The
// left edge also serves the feed's edge swipe (FeedController), which starts
// in its outer 24dp. Bottom swipes stay the system's. Anything over the home
// screen (drawer, search, edit mode, previews, the feed) gives the edges back
// to Back, which closes it.
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
            val (top, bottom) = if (isDefaultHome()) {
                0 to pager.height
            } else {
                val strip = min(pager.context.dp(EDGE_LIMIT_DP), pager.height / 2)
                val top = (pager.height - strip) / 2
                top to top + strip
            }
            val width = pager.context.dp(STRIP_WIDTH_DP)
            listOf(
                Rect(0, top, width, bottom),
                Rect(pager.width - width, top, pager.width, bottom)
            )
        } else {
            emptyList()
        }
        ViewCompat.setSystemGestureExclusionRects(pager, rects)
    }

    // The system skips the 200dp cap only for an activity opened as home;
    // asked when home comes back to the front, so a change of default
    // launcher is picked up.
    private fun isDefaultHome(): Boolean {
        val context = pager.context
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val home = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return home?.activityInfo?.packageName == context.packageName
    }

    private companion object {
        // Android's per-edge allowance for apps other than the home app.
        const val EDGE_LIMIT_DP = 200
        // Wider than the system's Back zone at its most sensitive.
        const val STRIP_WIDTH_DP = 48
    }
}
