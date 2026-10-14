package com.supernova.launcher.ui

import android.content.Context
import kotlin.math.min

// A reading column for the screens that are lists and forms - settings,
// search, onboarding. On a phone the column is the screen. On a tablet, or a
// phone turned sideways, a row stretched across the whole window leaves its
// label and its value the better part of a screen apart and every search
// result becomes a stripe, so the column is capped and centred, and the
// window keeps its own background out to the edges.
object WideScreen {

    // Portrait: a tablet's column stops at the width a large phone would
    // have and a bit. Landscape: the column may be wider than the window is
    // tall, which on a phone is the whole width and on a tablet is not.
    private const val PORTRAIT_COLUMN_DP = 640
    private const val LANDSCAPE_COLUMN_OF_HEIGHT = 1.35f
    private const val LANDSCAPE_COLUMN_MAX_DP = 760

    // The horizontal padding to add on each side of content that has
    // `availableWidthPx` of window to fill; zero on any screen narrower than
    // the column.
    fun gutterPx(context: Context, availableWidthPx: Int): Int {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val landscape = metrics.widthPixels > metrics.heightPixels
        val column = if (landscape) {
            min(
                (min(metrics.widthPixels, metrics.heightPixels) * LANDSCAPE_COLUMN_OF_HEIGHT).toInt(),
                (LANDSCAPE_COLUMN_MAX_DP * density).toInt()
            )
        } else {
            (PORTRAIT_COLUMN_DP * density).toInt()
        }
        return ((availableWidthPx - column) / 2).coerceAtLeast(0)
    }
}
