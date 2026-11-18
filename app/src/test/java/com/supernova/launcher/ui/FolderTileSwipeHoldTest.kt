package com.supernova.launcher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderTileSwipeHoldTest {
    @Test
    fun boundUpFlickStaysWithTheTile() {
        assertTrue(FolderTileSwipeHold.keeps(dx = 5f, dy = -30f, claimsUp = true, claimsDown = false))
    }

    @Test
    fun unboundDirectionGoesBackToTheGrid() {
        assertFalse(FolderTileSwipeHold.keeps(dx = 5f, dy = 30f, claimsUp = true, claimsDown = false))
        assertTrue(FolderTileSwipeHold.keeps(dx = 5f, dy = 30f, claimsUp = false, claimsDown = true))
    }

    @Test
    fun sidewaysMoveGoesBackToTheGridForPaging() {
        assertFalse(FolderTileSwipeHold.keeps(dx = -30f, dy = -10f, claimsUp = true, claimsDown = true))
    }
}
