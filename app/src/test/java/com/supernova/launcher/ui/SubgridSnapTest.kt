package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SubgridSnapTest {

    private fun snapCells(cell: Float, cols: Int) = snapToHalfCell(cell / cols, cols) * cols

    @Test
    fun cellCentreStaysPut() {
        assertEquals(0.5f, snapCells(0.5f, 4), 1e-4f)
        assertEquals(2.5f, snapCells(2.5f, 4), 1e-4f)
    }

    @Test
    fun snapsToHalfCellSteps() {
        assertEquals(1.0f, snapCells(1.1f, 4), 1e-4f)
        assertEquals(1.5f, snapCells(1.4f, 4), 1e-4f)
        assertEquals(2.0f, snapCells(1.8f, 5), 1e-4f)
    }

    // The footprint is one cell wide, so its centre never gets closer than
    // half a cell to the grid's edge.
    @Test
    fun keepsTheFootprintOnTheGrid() {
        assertEquals(0.5f, snapCells(0f, 4), 1e-4f)
        assertEquals(0.5f, snapCells(0.2f, 4), 1e-4f)
        assertEquals(3.5f, snapCells(4f, 4), 1e-4f)
        assertEquals(3.5f, snapCells(3.9f, 4), 1e-4f)
    }
}
