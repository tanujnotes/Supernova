package com.supernova.launcher.feed

import com.supernova.launcher.feed.FillSnap.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// A 1000px page with its visible top at 0. Full-page cards are 1000px tall.
class FillSnapTest {

    private val page = 1000
    private val tol = 4

    private fun fling(rows: List<Row>, fills: Set<Int>, count: Int, distance: Int) =
        FillSnap.flingTarget(FillSnap.Frame(rows, count, { it in fills }, contentTop = 0, tolerance = tol), distance)

    @Test
    fun withNoFullPageCardNothingSnaps() {
        val rows = listOf(Row(0, -100, 300, false), Row(1, 300, 700, false), Row(2, 700, 1100, false))
        assertNull(FillSnap.settleTarget(rows, 0, page, true) { false })
        assertEquals(FillSnap.NONE, fling(rows, emptySet(), 6, 5000))
        assertEquals(FillSnap.NONE, fling(rows, emptySet(), 6, -5000))
    }

    @Test
    fun settlesOnTheNearestFullPageWithinHalfAPage() {
        val fills = setOf(0, 1)
        // Two full pages, dragged 70% into the second: on to it.
        val rows = listOf(Row(0, -700, 300, true), Row(1, 300, 1300, true))
        assertEquals(1, FillSnap.settleTarget(rows, 0, page, true) { it in fills }?.position)
        // Only 30%: back to the first, against the drag, as it is between pages.
        val back = listOf(Row(0, -300, 700, true), Row(1, 700, 1700, true))
        assertEquals(0, FillSnap.settleTarget(back, 0, page, true) { it in fills }?.position)
        // And the same coming back up.
        assertEquals(1, FillSnap.settleTarget(rows, 0, page, false) { it in fills }?.position)
    }

    // A full page followed by normal cards (the end of the feed): scrolled
    // on to see them, the list stays; it never snaps back to the page.
    @Test
    fun normalCardsAfterAPageStayInView() {
        val rows = listOf(Row(0, -400, 600, true), Row(1, 600, 800, false), Row(2, 800, 950, false))
        assertNull(FillSnap.settleTarget(rows, 0, page, true) { it == 0 })
        // Moving back up towards the page it does snap to it.
        assertEquals(0, FillSnap.settleTarget(rows, 0, page, false) { it == 0 }?.position)
    }

    // Normal cards above a page, scrolled back up to them: no pull down.
    @Test
    fun normalCardsAboveAPageStayInView() {
        val rows = listOf(Row(0, 0, 300, false), Row(1, 300, 1300, true))
        assertNull(FillSnap.settleTarget(rows, 0, page, false) { it == 1 })
        assertEquals(1, FillSnap.settleTarget(rows, 0, page, true) { it == 1 }?.position)
    }

    @Test
    fun aFullPageFarFromTheTopIsLeftAlone() {
        // Normal cards above, the full page starting 70% down.
        val rows = listOf(Row(0, 0, 400, false), Row(1, 400, 700, false), Row(2, 700, 1700, true))
        assertNull(FillSnap.settleTarget(rows, 0, page, true) { it == 2 })
    }

    @Test
    fun aFlingBetweenFullPagesTurnsOnePage() {
        // On page 1 of three in a row; a huge fling still stops at page 2.
        val rows = listOf(Row(1, 0, 1000, true), Row(2, 1000, 2000, true))
        assertEquals(2, fling(rows, setOf(0, 1, 2, 3), 4, 50_000))
        // Even a weak one.
        assertEquals(2, fling(rows, setOf(0, 1, 2, 3), 4, 50))
        // Back: to page 0 (not laid out), not further.
        assertEquals(0, fling(rows, setOf(0, 1, 2, 3), 4, -50_000))
    }

    @Test
    fun partWayIntoAPageAFlingBackReturnsToItsTop() {
        val rows = listOf(Row(1, -200, 800, true), Row(2, 800, 1800, true))
        assertEquals(1, fling(rows, setOf(1, 2), 3, -3000))
        assertEquals(2, fling(rows, setOf(1, 2), 3, 3000))
    }

    @Test
    fun fromAFullPageNormalCardsBelowScrollFreely() {
        val rows = listOf(Row(0, 0, 1000, true), Row(1, 1000, 1300, false))
        // A short fling into the normal cards: free.
        assertEquals(FillSnap.NONE, fling(rows, setOf(0), 4, 400))
    }

    @Test
    fun aLongFlingNeverSkipsAFullPage() {
        // Normal cards on screen, a full page further down, not laid out.
        val rows = listOf(Row(0, 0, 400, false), Row(1, 400, 800, false), Row(2, 800, 1200, false))
        assertEquals(5, fling(rows, setOf(5, 7), 8, 20_000))
        // One that doesn't get past the laid-out rows is free.
        assertEquals(FillSnap.NONE, fling(rows, setOf(5, 7), 8, 100))
    }

    @Test
    fun aFlingThatReachesAVisibleFullPageSnapsToIt() {
        val rows = listOf(Row(0, 0, 400, false), Row(1, 400, 1400, true))
        assertEquals(1, fling(rows, setOf(1), 3, 600))
        // Not reaching it: free, the settle decides.
        assertEquals(FillSnap.NONE, fling(rows, setOf(1), 3, 200))
    }

    @Test
    fun flingingUpFromNormalCardsStopsAtTheFullPageAbove() {
        val rows = listOf(Row(1, -300, 0, true).copy(top = -1300, bottom = 0), Row(2, 0, 400, false))
        assertEquals(1, fling(rows, setOf(1), 4, -1500))
        assertEquals(FillSnap.NONE, fling(rows, setOf(1), 4, -500))
        // Above the laid-out rows: the nearest full page, not a further one.
        val low = listOf(Row(4, 0, 400, false), Row(5, 400, 800, false))
        assertEquals(2, fling(low, setOf(0, 2), 6, -9000))
    }
}
