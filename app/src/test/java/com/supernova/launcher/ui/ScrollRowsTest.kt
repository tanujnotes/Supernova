package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

// The drawer list's rows as its scrollbar adds them up.
class ScrollRowsTest {

    // A 5-column grid: the frequent row spanning all five, then 12 apps -
    // two full rows and a short last one of two.
    private fun drawer(): ScrollRows = ScrollRows().apply {
        group(13, 5) { if (it == 0) 5 else 1 }
    }

    @Test
    fun itemsGroupIntoRowsAsTheGridLaysThemOut() {
        val rows = drawer()
        assertEquals(4, rows.rowCount)
        assertEquals(listOf(0, 1, 6, 11), (0 until 4).map(rows::firstPosition))
        assertEquals(0, rows.rowOf(0))
        assertEquals(1, rows.rowOf(5))
        assertEquals(2, rows.rowOf(6))
        assertEquals(3, rows.rowOf(12))
    }

    @Test
    fun aWideItemThatWouldOverflowStartsTheNextRow() {
        val rows = ScrollRows().apply { group(4, 3) { if (it == 2) 3 else 1 } }
        assertEquals(listOf(0, 2, 3), (0 until 3).map(rows::firstPosition))
    }

    @Test
    fun measuredRowsAddUpExactly() {
        val rows = drawer()
        rows.record(0, 300)
        rows.record(1, 100)
        rows.record(2, 100)
        rows.record(3, 100)
        assertEquals(300, rows.top(1))
        assertEquals(500, rows.top(3))
        assertEquals(600, rows.total)
    }

    @Test
    fun aRowNotSeenCountsAsTallAsMostRows() {
        // Opened at the top: the tall frequent row and two app rows seen.
        val rows = drawer()
        rows.record(0, 300)
        rows.record(1, 100)
        rows.record(2, 100)
        // The last row is guessed at an app row's height, not the average
        // with the frequent row in it.
        assertEquals(600, rows.total)
    }

    @Test
    fun theFrequentRowCountsInFullOnceSeen() {
        // Scrolled past the frequent row: everything below it is offset by
        // its whole height, not by one item's share of an average.
        val rows = drawer()
        rows.record(0, 300)
        rows.record(1, 100)
        assertEquals(300, rows.top(1))
        assertEquals(400, rows.top(2))
    }

    @Test
    fun anOffsetFindsItsRowAndTheItemAcrossIt() {
        val rows = drawer()
        listOf(300, 100, 100, 100).forEachIndexed(rows::record)
        assertEquals(0, rows.rowAt(0))
        assertEquals(0, rows.rowAt(299))
        assertEquals(1, rows.rowAt(300))
        assertEquals(3, rows.rowAt(10_000))
        // Halfway into the frequent row is still the frequent row.
        assertEquals(0, rows.positionAt(150))
        // The second app row, three fifths of the way in: its fourth item.
        assertEquals(9, rows.positionAt(460))
    }

    @Test
    fun theSameRowsKeepTheirHeights() {
        val rows = drawer()
        rows.record(0, 300)
        rows.group(13, 5) { if (it == 0) 5 else 1 }
        assertEquals(300, rows.top(1))
    }

    @Test
    fun differentRowsStartOver() {
        val rows = drawer()
        rows.record(0, 300)
        rows.record(1, 100)
        // A search: the frequent row goes, the apps regroup.
        rows.group(4, 5) { 1 }
        assertEquals(1, rows.rowCount)
        assertEquals(0, rows.total)
    }

    @Test
    fun anEmptyListHasNoRows() {
        val rows = ScrollRows().apply { group(0, 5) { 1 } }
        assertEquals(0, rows.rowCount)
        assertEquals(0, rows.total)
        assertEquals(0, rows.positionAt(100))
    }
}
