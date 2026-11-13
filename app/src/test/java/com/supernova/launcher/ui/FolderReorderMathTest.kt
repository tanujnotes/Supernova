package com.supernova.launcher.ui

import com.supernova.launcher.ui.FolderReorderMath.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

// Moving an app inside an open folder: the order a move makes, the tile a
// finger aims at, and the scroll near a grid's ends.
class FolderReorderMathTest {

    @Test
    fun aMoveForwardShiftsTheOthersBack() {
        assertEquals(listOf("b", "c", "a", "d"), FolderReorderMath.moved(listOf("a", "b", "c", "d"), 0, 2))
    }

    @Test
    fun aMoveBackShiftsTheOthersForward() {
        assertEquals(listOf("d", "a", "b", "c"), FolderReorderMath.moved(listOf("a", "b", "c", "d"), 3, 0))
    }

    @Test
    fun nothingToMoveIsTheSameList() {
        val list = listOf("a", "b", "c")
        assertSame(list, FolderReorderMath.moved(list, 1, 1))
        assertSame(list, FolderReorderMath.moved(list, -1, 1))
        assertSame(list, FolderReorderMath.moved(list, 0, 3))
    }

    // A grid of 100x120 tiles filled row by row, `columns` wide.
    private fun rows(count: Int, columns: Int, heights: Map<Int, Float> = emptyMap()) = (0 until count).map { p ->
        val left = (p % columns) * 100f
        val top = (p / columns) * 120f
        Slot(p, left, top, left + 100f, top + (heights[p] ?: 120f))
    }

    // A paged folder: pages `columns` x `rows`, 300 wide, side by side,
    // each filled row by row.
    private fun pages(count: Int, columns: Int, rows: Int) = (0 until count).map { p ->
        val inPage = p % (columns * rows)
        val left = (p / (columns * rows)) * columns * 100f + (inPage % columns) * 100f
        val top = (inPage / columns) * 120f
        Slot(p, left, top, left + 100f, top + 120f)
    }

    @Test
    fun aFingerOnATileAimsAtIt() {
        val slots = rows(7, 3)
        assertEquals(4, FolderReorderMath.targetSlot(slots, 150f, 130f))
        assertEquals(0, FolderReorderMath.targetSlot(slots, 1f, 1f))
    }

    @Test
    fun theEmptyEndOfTheLastRowAimsAtTheLastApp() {
        // Row 2 holds only app 6; the place two columns over is empty, and
        // app 5 right above it is not what the finger is in line with.
        assertEquals(6, FolderReorderMath.targetSlot(rows(7, 3), 250f, 300f))
    }

    @Test
    fun belowTheGridAimsAtTheLastRow() {
        // Over the title row, under the last apps.
        assertEquals(6, FolderReorderMath.targetSlot(rows(7, 3), 20f, 420f))
        assertEquals(5, FolderReorderMath.targetSlot(rows(6, 3), 280f, 500f))
    }

    @Test
    fun underAShortTileInATallRowAimsAtThatTile() {
        // App 1's label wraps to two lines, so row 0 is 150 tall; under app 0
        // (one line, 120) is still row 0, column 0.
        val slots = rows(6, 3, heights = mapOf(1 to 150f)).map { slot ->
            if (slot.position >= 3) slot.copy(top = slot.top + 30f, bottom = slot.bottom + 30f) else slot
        }
        assertEquals(0, FolderReorderMath.targetSlot(slots, 50f, 135f))
        assertEquals(3, FolderReorderMath.targetSlot(slots, 50f, 155f))
    }

    @Test
    fun aPagedGridAimsAtThePageInView() {
        // 3 x 2 pages, 8 apps: page 2 (scrolled into view, so shifted 300
        // left) holds apps 6 and 7 in its first row. The empty row under
        // them aims at app 7, not at app 4 or 5 on page 1 beside it.
        val slots = pages(8, 3, 2).map { it.copy(left = it.left - 300f, right = it.right - 300f) }
        assertEquals(7, FolderReorderMath.targetSlot(slots, 250f, 200f, width = 300f))
        assertEquals(6, FolderReorderMath.targetSlot(slots, 50f, 200f, width = 300f))
        assertEquals(1, FolderReorderMath.targetSlot(pages(8, 3, 2), 150f, 60f, width = 300f))
    }

    @Test
    fun noTilesAimNowhere() {
        assertEquals(-1, FolderReorderMath.targetSlot(emptyList(), 0f, 0f))
    }

    @Test
    fun edgeScrollIsZeroAwayFromTheEnds() {
        assertEquals(0f, FolderReorderMath.edgeScrollStep(500f, 0f, 1000f, 50f, 10f))
        assertEquals(0f, FolderReorderMath.edgeScrollStep(50f, 0f, 1000f, 50f, 10f))
    }

    @Test
    fun edgeScrollGrowsTowardTheEdgeAndStopsGrowingPastIt() {
        assertEquals(-5f, FolderReorderMath.edgeScrollStep(25f, 0f, 1000f, 50f, 10f))
        assertEquals(-10f, FolderReorderMath.edgeScrollStep(0f, 0f, 1000f, 50f, 10f))
        assertEquals(-10f, FolderReorderMath.edgeScrollStep(-80f, 0f, 1000f, 50f, 10f))
        assertEquals(5f, FolderReorderMath.edgeScrollStep(975f, 0f, 1000f, 50f, 10f))
        assertEquals(10f, FolderReorderMath.edgeScrollStep(1100f, 0f, 1000f, 50f, 10f))
    }

    @Test
    fun aGridTooShortForTwoBandsNeverScrolls() {
        assertEquals(0f, FolderReorderMath.edgeScrollStep(10f, 0f, 90f, 50f, 10f))
    }

    @Test
    fun edgeSideTellsWhichEnd() {
        assertEquals(-1, FolderReorderMath.edgeSide(10f, 0f, 1000f, 50f))
        assertEquals(1, FolderReorderMath.edgeSide(990f, 0f, 1000f, 50f))
        assertEquals(0, FolderReorderMath.edgeSide(500f, 0f, 1000f, 50f))
    }
}
