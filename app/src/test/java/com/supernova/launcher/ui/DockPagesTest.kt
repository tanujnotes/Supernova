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
    fun morePagesKeepAlternatingRightThenLeft() {
        // Four pages: main second from the left, then right, left, right.
        assertEquals(listOf(1, 2, 0, 3), (0 until 4).map { DockPages.position(it, 4) })
        // Five pages: main in the middle, two each side.
        assertEquals(listOf(2, 3, 1, 4, 0), (0 until 5).map { DockPages.position(it, 5) })
        for (pages in 1..5) {
            assertEquals((0 until pages).toSet(), (0 until pages).map { DockPages.position(it, pages) }.toSet())
        }
    }

    @Test
    fun fivePagesFlickAcrossEveryPage() {
        // Leftmost to rightmost: 4, 2, 0, 1, 3.
        var page = 4
        val seen = mutableListOf(page)
        repeat(4) {
            page = DockPages.step(page, 5, toRight = true, wrap = false)
            seen += page
        }
        assertEquals(listOf(4, 2, 0, 1, 3), seen)
        assertEquals(3, DockPages.step(3, 5, toRight = true, wrap = false))
        assertEquals(4, DockPages.step(3, 5, toRight = true, wrap = true))
        assertEquals(3, DockPages.step(4, 5, toRight = false, wrap = true))
        // Home from the outer pages comes in from the side the main page is on.
        assertEquals(-1, DockPages.direction(3, 0, 5))
        assertEquals(1, DockPages.direction(4, 0, 5))
    }

    @Test
    fun homeComesInFromTheMiddle() {
        assertEquals(-1, DockPages.direction(1, 0, 3))
        assertEquals(1, DockPages.direction(2, 0, 3))
        assertEquals(0, DockPages.direction(0, 0, 3))
    }
}
