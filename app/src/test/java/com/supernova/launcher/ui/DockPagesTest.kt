package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DockPagesTest {
    @Test
    fun mainPageSitsInTheMiddle() {
        assertEquals(0, DockPages.position(0, 1))
        assertEquals(listOf(0, 1), (0 until 2).map { DockPages.position(it, 2) })
        // Three pages: main in the middle, page 1 right of it, page 2 left.
        assertEquals(listOf(1, 2, 0), (0 until 3).map { DockPages.position(it, 3) })
    }

    @Test
    fun pageAtUndoesPosition() {
        for (pages in 1..5) {
            for (page in 0 until pages) {
                assertEquals(page, DockPages.pageAt(DockPages.position(page, pages), pages))
            }
        }
    }

    @Test
    fun flicksMoveOnePlaceAndStopAtTheEdges() {
        assertEquals(1, DockPages.step(0, 3, toRight = true, wrap = false))
        assertEquals(2, DockPages.step(0, 3, toRight = false, wrap = false))
        assertEquals(1, DockPages.step(1, 3, toRight = true, wrap = false))
        assertEquals(2, DockPages.step(2, 3, toRight = false, wrap = false))
        assertEquals(0, DockPages.step(0, 1, toRight = true, wrap = true))
    }

    @Test
    fun infiniteScrollWrapsAround() {
        assertEquals(2, DockPages.step(1, 3, toRight = true, wrap = true))
        assertEquals(1, DockPages.step(2, 3, toRight = false, wrap = true))
        assertEquals(0, DockPages.step(1, 2, toRight = true, wrap = true))
    }

    @Test
    fun homeComesInFromTheMiddle() {
        assertEquals(-1, DockPages.direction(1, 0, 3))
        assertEquals(1, DockPages.direction(2, 0, 3))
        assertEquals(0, DockPages.direction(0, 0, 3))
    }
}
