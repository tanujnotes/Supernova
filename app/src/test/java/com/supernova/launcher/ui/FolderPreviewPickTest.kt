package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderPreviewPickTest {

    private val apps = ('a'..'l').map(Char::toString)

    @Test
    fun aGridPreviewIsTheWindowsTopLeftCorner() {
        // A three-column window: a b c / d e f / ...
        assertEquals(
            listOf("a", "b", "d", "e"),
            FolderPreviewPick.shown(apps.take(9), 3, FolderPreviewStyle.GRID, prioritizeFirst = false, limit = 9)
        )
        assertEquals(
            listOf("a", "b", "c", "e", "f", "g", "i", "j", "k"),
            FolderPreviewPick.shown(apps, 4, FolderPreviewStyle.GRID_3X3, prioritizeFirst = false, limit = 9)
        )
    }

    @Test
    fun aWindowNoWiderThanTheTileReadsStraightIn() {
        assertEquals(
            listOf("a", "b", "c", "d"),
            FolderPreviewPick.shown(apps.take(4), 2, FolderPreviewStyle.GRID, prioritizeFirst = false, limit = 9)
        )
        // Three apps in a three-column window: the tile holds them all.
        assertEquals(
            listOf("a", "b", "c"),
            FolderPreviewPick.shown(apps.take(3), 3, FolderPreviewStyle.GRID, prioritizeFirst = false, limit = 9)
        )
    }

    @Test
    fun otherStylesTakeTheFirstApps() {
        assertEquals(
            apps.take(9),
            FolderPreviewPick.shown(apps, 4, FolderPreviewStyle.LINE, prioritizeFirst = false, limit = 9)
        )
        assertEquals(
            apps.take(9),
            FolderPreviewPick.shown(apps, 4, FolderPreviewStyle.GRID, prioritizeFirst = true, limit = 9)
        )
    }
}
