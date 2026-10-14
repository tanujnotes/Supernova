package com.supernova.launcher.ui

// The vertical layout of the home screen's bottom chrome: the dock, the
// desktop search bar, the swipe arrow and the page indicator, and the
// floor the workspace ends on. Every number is a height above the window's
// bottom edge in pixels, ready to be a view's bottom margin.
// LauncherActivity.applyDockChrome measures the dock, resolves the prefs and
// applies what comes back; the rules live here so they can be tested apart
// from the views.
internal object BottomChrome {

    // Where the desktop search bar landed after resolving its placement
    // against the current dock state.
    enum class BarSlot { ABOVE_DOCK, BELOW_DOCK, BOTTOM }

    class Spec(
        val density: Float,
        val landscape: Boolean,
        // The navigation bar's height: the chrome sits above it while the
        // wallpaper keeps running underneath.
        val navInset: Int,
        val dockEnabled: Boolean,
        // An overlay dock draws over the workspace and reserves nothing.
        val dockOverlay: Boolean,
        // The dock's background runs to the window's bottom edge, the
        // navigation bar folded into its own padding.
        val dockBehindNav: Boolean,
        // The dock's background rests on the navigation bar.
        val dockFullBleed: Boolean,
        // As laid out, padding included.
        val dockHeight: Int,
        // From the dock's top edge down to its icons' top.
        val dockIconInset: Int,
        // No background: the dock's padding is empty space.
        val dockBare: Boolean,
        val slot: BarSlot?,
        val arrowWanted: Boolean,
        val arrowHeight: Int,
        val indicatorOn: Boolean,
        // The page padding under the grid's bottom row.
        val pagePadding: Int
    )

    class Result(
        val dockBottom: Int,
        val barBottom: Int,
        val arrowBottom: Int,
        val indicatorBottom: Int,
        val pagerBottom: Int
    )

    fun layout(s: Spec): Result {
        fun dp(value: Int): Int = (value * s.density).toInt()

        val dockReservesSpace = s.dockEnabled && !s.dockOverlay
        // A bar at the bottom sits in the middle of a band over the
        // navigation bar, and a below-dock bar pushes the dock up over that
        // band. The band is 90dp, and the dock's
        // icons keep 12dp clear of it - 6dp of that is the padding under a
        // dock icon column. It used to be 8dp under the bar and 6dp over it,
        // which sat the dock right on the bar. Landscape keeps that tight
        // stack: every dp of it comes out of the grid's rows.
        val barMargin = if (s.landscape) dp(8) else (dp(BAR_BAND_DP) - dp(SEARCH_BAR_HEIGHT_DP)) / 2
        val barToDock = if (s.landscape) dp(6) else barMargin + dp(BAR_BAND_TO_DOCK_DP)

        // Behind-nav mode is the one place the bottom inset becomes padding
        // instead of margin: the slab runs to the window edge, the icons stay
        // above the bar.
        val dockBottom = when {
            s.slot == BarSlot.BELOW_DOCK -> barMargin + dp(SEARCH_BAR_HEIGHT_DP) + barToDock + s.navInset
            s.dockBehindNav -> 0
            s.dockFullBleed -> s.navInset
            else -> dp(10) + s.navInset
        }
        // Where the dock's top edge sits, and the one number everything
        // stacked on the dock is spaced from.
        val dockTop = dockBottom + s.dockHeight

        // Every bottom margin carries the navigation bar on top of its design
        // value, except the above-dock bar's: it clears the dock, which
        // carries the inset already.
        val barBottom = when (s.slot) {
            // Over the dock, the band sits on the dock as the dock sits on it
            // below: the layout is the same two rows in either order.
            BarSlot.ABOVE_DOCK ->
                dockTop + if (s.landscape) dp(DOCK_SEARCH_BAR_GAP_DP) else dp(BAR_BAND_TO_DOCK_DP) + barMargin
            BarSlot.BELOW_DOCK -> barMargin + s.navInset
            BarSlot.BOTTOM -> (if (s.landscape) dp(16) else barMargin) + s.navInset
            null -> dp(16) + s.navInset
        }
        val barEdge = barBottom + dp(SEARCH_BAR_HEIGHT_DP)

        // The workspace and indicator clear whichever of the dock and the
        // search bar ends up on top - which one that is depends on the slot,
        // and a below-dock bar has already pushed the dock up rather than
        // adding a band of its own. An overlay dock keeps its
        // content-underneath philosophy, so it reserves nothing, and neither
        // does its bar. A bar on top clears the grid by its band, not its
        // edge.
        val barReservesSpace = s.slot != null && (dockReservesSpace || !s.dockEnabled)
        val bandAbove = if (s.landscape) 0 else barMargin
        val barTop = if (barReservesSpace) barEdge + bandAbove else 0
        val chromeTop = maxOf(if (dockReservesSpace) dockTop else 0, barTop)
        // The top of the chrome as drawn: what the workspace clears, or the
        // overlay dock (or a bar above it) the workspace runs under. 0 with
        // nothing at the bottom at all.
        val overlayDock = s.dockEnabled && !dockReservesSpace
        val visibleTop = when {
            chromeTop > 0 -> chromeTop
            overlayDock -> maxOf(dockTop, if (s.slot == BarSlot.ABOVE_DOCK) barEdge else 0)
            else -> 0
        }

        // The swipe arrow takes a strip of its own above the dock: the
        // workspace and the page indicator move up to make
        // room. Over an overlay dock it reserves nothing, like the dock
        // itself.
        val arrowStrip = if (s.arrowWanted && dockReservesSpace) {
            dp(if (s.landscape) SWIPE_ARROW_STRIP_LAND_DP else SWIPE_ARROW_STRIP_DP)
        } else {
            0
        }
        // The height over visibleTop the arrow takes.
        val arrowSpace = when {
            !s.arrowWanted -> 0
            dockReservesSpace -> arrowStrip
            else -> dp(if (s.landscape) OVERLAY_ARROW_BAND_LAND_DP else OVERLAY_ARROW_BAND_DP)
        }
        // The middle of the gap between the dock and the grid, or of its own
        // strip when the page indicator sits over it.
        val arrowBand = if (dockReservesSpace && !s.indicatorOn) arrowSpace + s.pagePadding else arrowSpace
        val arrowBottom = visibleTop + (arrowBand - s.arrowHeight) / 2

        // The page indicator's centre line: 12dp over the dock's icons, the
        // middle of the strip kept between the dock and the grid. A dock
        // without a background leaves its padding empty, and the indicator
        // sits in it; a dock background, a search
        // bar or the arrow's strip under it keeps it 4dp clear of their top
        // edge instead. Over a bar on top it rides the top of the bar's band
        // - the dock's top, as 12dp over the icons is below it - for as
        // long as the band leaves that clearance.
        val markHalf = (PageIndicatorView.MARK_HEIGHT_DP * s.density / 2).toInt()
        val bareDockUnder = s.dockEnabled && visibleTop == dockTop && arrowSpace == 0 && s.dockBare
        val barOnTop = barReservesSpace && barTop == chromeTop && arrowSpace == 0
        val clearance = dp(INDICATOR_CLEARANCE_DP) + markHalf
        val indicatorCenter = when {
            bareDockUnder -> dockTop - s.dockIconInset + dp(INDICATOR_OVER_ICONS_DP)
            barOnTop -> chromeTop + maxOf(0, clearance - bandAbove)
            visibleTop > 0 -> visibleTop + arrowSpace + clearance
            else -> dp(INDICATOR_BARE_CENTER_DP) + s.navInset
        }

        // The grid sits on what is under it and moves up for the indicator
        // only as far as the page padding under its bottom row falls short.
        // It used to keep a 12dp band over the dock whether or not there was
        // an indicator to go in it, which with the page padding and the
        // dock's own made the gap between them far too wide. Over an
        // overlay dock it stays put: the workspace runs under that dock.
        val pagerFloor = if (chromeTop > 0) chromeTop + arrowStrip else dp(10) + s.navInset
        val pagerBottom = if (s.indicatorOn && !overlayDock) {
            maxOf(pagerFloor, indicatorCenter + markHalf - s.pagePadding)
        } else {
            pagerFloor
        }
        val indicatorBottom = indicatorCenter - (PageIndicatorView.HEIGHT_DP * s.density / 2).toInt()
        return Result(dockBottom, barBottom, arrowBottom, indicatorBottom, pagerBottom)
    }

    // The desktop search bar's own height, matching activity_launcher.xml.
    const val SEARCH_BAR_HEIGHT_DP = 44

    // The band a bar at the bottom centres in
    // and how much more a below-dock bar's dock clears it by.
    private const val BAR_BAND_DP = 90
    private const val BAR_BAND_TO_DOCK_DP = 6
    // The gap between the dock and an above-dock search bar in landscape.
    private const val DOCK_SEARCH_BAR_GAP_DP = 4
    // The strip the swipe arrow adds above the dock.
    private const val SWIPE_ARROW_STRIP_DP = 16
    private const val SWIPE_ARROW_STRIP_LAND_DP = 10
    // The band the swipe arrow centres in over an overlay dock, which
    // reserves no strip for it.
    private const val OVERLAY_ARROW_BAND_DP = 12
    private const val OVERLAY_ARROW_BAND_LAND_DP = 6
    // Where the page indicator's centre goes: this far over a bare dock's
    // icons, this far plus half its tallest mark clear of a dock
    // background, a bar or the arrow's strip, and here with nothing at the
    // bottom at all.
    private const val INDICATOR_OVER_ICONS_DP = 12
    private const val INDICATOR_CLEARANCE_DP = 4
    private const val INDICATOR_BARE_CENTER_DP = 22
}
