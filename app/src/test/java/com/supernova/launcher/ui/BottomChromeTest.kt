package com.supernova.launcher.ui

import com.supernova.launcher.ui.BottomChrome.BarSlot
import org.junit.Assert.assertEquals
import org.junit.Test

// At density 1 a dp is a pixel: the navigation bar is 48, the search bar 48,
// the band it centres in 90, the tallest indicator mark 12 in a 24 view.
class BottomChromeTest {

    private fun layout(
        landscape: Boolean = false,
        dockEnabled: Boolean = true,
        dockOverlay: Boolean = false,
        dockBehindNav: Boolean = false,
        dockHeight: Int = 80,
        dockIconInset: Int = 6,
        dockBare: Boolean = true,
        slot: BarSlot? = BarSlot.BELOW_DOCK,
        arrowWanted: Boolean = false,
        indicatorOn: Boolean = false,
        pagePadding: Int = 12
    ) = BottomChrome.layout(
        BottomChrome.Spec(
            density = 1f,
            landscape = landscape,
            navInset = NAV,
            dockEnabled = dockEnabled,
            dockOverlay = dockOverlay,
            dockBehindNav = dockBehindNav,
            dockFullBleed = false,
            dockHeight = dockHeight,
            dockIconInset = dockIconInset,
            dockBare = dockBare,
            slot = slot,
            arrowWanted = arrowWanted,
            arrowHeight = ARROW,
            indicatorOn = indicatorOn,
            pagePadding = pagePadding
        )
    )

    // Where the indicator view's centre line is.
    private fun BottomChrome.Result.indicatorCenter() = indicatorBottom + 12

    @Test
    fun aBarUnderTheDockCentresInItsBand() {
        val chrome = layout()
        // (90 - 48) / 2 under the bar, as over it.
        assertEquals(NAV + 21, chrome.barBottom)
        // The band, then 6 more: the dock's icons clear it by 12.
        assertEquals(NAV + 90 + 6, chrome.dockBottom)
    }

    @Test
    fun theGridSitsOnTheDockWithoutAnIndicator() {
        val chrome = layout()
        assertEquals(chrome.dockBottom + 80, chrome.pagerBottom)
    }

    @Test
    fun anIndicatorGoes12OverABareDocksIcons() {
        val chrome = layout(indicatorOn = true)
        val dockTop = chrome.dockBottom + 80
        assertEquals(dockTop - 6 + 12, chrome.indicatorCenter())
        // The page padding has room for it: the grid doesn't move.
        assertEquals(dockTop, chrome.pagerBottom)
    }

    @Test
    fun aPaddedBareDockHoldsTheIndicatorInItsPadding() {
        val chrome = layout(indicatorOn = true, dockHeight = 132, dockIconInset = 32)
        val dockTop = chrome.dockBottom + 132
        assertEquals(dockTop - 32 + 12, chrome.indicatorCenter())
        assertEquals(dockTop, chrome.pagerBottom)
    }

    @Test
    fun aDockBackgroundKeepsTheIndicatorClearOfIt() {
        val chrome = layout(indicatorOn = true, dockBare = false)
        val dockTop = chrome.dockBottom + 80
        // 4 clear of the edge, plus half the tallest mark.
        assertEquals(dockTop + 10, chrome.indicatorCenter())
        // The indicator's top is 16 up; 12 of page padding leaves 4 to find.
        assertEquals(dockTop + 4, chrome.pagerBottom)
    }

    @Test
    fun withoutPagePaddingTheGridMakesRoomForTheIndicator() {
        val chrome = layout(indicatorOn = true, pagePadding = 0)
        val dockTop = chrome.dockBottom + 80
        assertEquals(dockTop - 6 + 12 + 6, chrome.pagerBottom)
    }

    @Test
    fun aBarOverTheDockSitsInTheSameBandAndTheGridClearsIt() {
        val chrome = layout(slot = BarSlot.ABOVE_DOCK, indicatorOn = true)
        assertEquals(10 + NAV, chrome.dockBottom)
        val dockTop = chrome.dockBottom + 80
        assertEquals(dockTop + 6 + 21, chrome.barBottom)
        val bandTop = chrome.barBottom + 48 + 21
        assertEquals(bandTop, chrome.pagerBottom)
        // The hotseat top: the band's top line.
        assertEquals(bandTop, chrome.indicatorCenter())
    }

    @Test
    fun aBarWithTheDockOffSitsInTheBandAtTheBottom() {
        val chrome = layout(dockEnabled = false, slot = BarSlot.BOTTOM)
        assertEquals(NAV + 21, chrome.barBottom)
        assertEquals(chrome.barBottom + 48 + 21, chrome.pagerBottom)
    }

    @Test
    fun withNothingAtTheBottomTheGridKeepsClearOfTheNavigationBar() {
        val chrome = layout(dockEnabled = false, slot = null)
        assertEquals(10 + NAV, chrome.pagerBottom)
        val withIndicator = layout(dockEnabled = false, slot = null, indicatorOn = true)
        assertEquals(22 + NAV, withIndicator.indicatorCenter())
        assertEquals(22 + NAV + 6 - 12, withIndicator.pagerBottom)
    }

    @Test
    fun anOverlayDockReservesNothingButTheIndicatorStaysAboveIt() {
        val chrome = layout(dockOverlay = true, slot = null, indicatorOn = true)
        assertEquals(10 + NAV, chrome.pagerBottom)
        val dockTop = chrome.dockBottom + 80
        assertEquals(dockTop - 6 + 12, chrome.indicatorCenter())
    }

    @Test
    fun theArrowTakesAStripAndCentresInTheGap() {
        val chrome = layout(arrowWanted = true)
        val dockTop = chrome.dockBottom + 80
        assertEquals(dockTop + 16, chrome.pagerBottom)
        // Centred between the dock and the grid's bottom row: 16 + 12.
        assertEquals(dockTop + (16 + 12 - ARROW) / 2, chrome.arrowBottom)
    }

    @Test
    fun underAnIndicatorTheArrowKeepsToItsOwnStrip() {
        val chrome = layout(arrowWanted = true, indicatorOn = true)
        val dockTop = chrome.dockBottom + 80
        assertEquals(dockTop + (16 - ARROW) / 2, chrome.arrowBottom)
        assertEquals(dockTop + 16 + 10, chrome.indicatorCenter())
        assertEquals(dockTop + 16 + 10 + 6 - 12, chrome.pagerBottom)
    }

    @Test
    fun landscapeKeepsItsTightStack() {
        val below = layout(landscape = true)
        assertEquals(8 + NAV, below.barBottom)
        assertEquals(8 + 48 + 6 + NAV, below.dockBottom)
        val above = layout(landscape = true, slot = BarSlot.ABOVE_DOCK)
        assertEquals(above.dockBottom + 80 + 4, above.barBottom)
        assertEquals(above.barBottom + 48, above.pagerBottom)
        val bottom = layout(landscape = true, dockEnabled = false, slot = BarSlot.BOTTOM)
        assertEquals(16 + NAV, bottom.barBottom)
    }

    @Test
    fun aBehindNavDockRestsOnTheWindowEdge() {
        val chrome = layout(dockBehindNav = true, dockBare = false, slot = BarSlot.ABOVE_DOCK)
        assertEquals(0, chrome.dockBottom)
        assertEquals(80 + 6 + 21, chrome.barBottom)
    }

    private companion object {
        const val NAV = 48
        const val ARROW = 10
    }
}
