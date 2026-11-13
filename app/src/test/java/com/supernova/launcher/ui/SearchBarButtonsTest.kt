package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

// Both search bars offer every button, and the drawer's stored list keeps
// decoding what older builds wrote.
class SearchBarButtonsTest {

    @Test
    fun bothBarsListEveryButton() {
        assertEquals(SearchBarChip.entries.toSet(), SearchBarButtons.ROWS.toSet())
        assertEquals(SearchBarChip.entries.size, SearchBarButtons.ROWS.size)
    }

    @Test
    fun drawerListDecodesOldValuesAndDropsUnknownOnes() {
        assertEquals(
            listOf(SearchBarChip.PLAY_STORE, SearchBarChip.SETTINGS),
            SearchBarButtons.decode("PLAY_STORE,SETTINGS")
        )
        // SEARCH was a drawer button once; it is gone.
        assertEquals(listOf(SearchBarChip.SETTINGS), SearchBarButtons.decode("SEARCH, settings"))
        assertEquals(emptyList<SearchBarChip>(), SearchBarButtons.decode(""))
    }

    @Test
    fun turningOnAppendsAndTurningOffRemoves() {
        val start = listOf(SearchBarChip.SETTINGS)
        val on = SearchBarButtons.toggled(start, SearchBarChip.DATE, on = true)
        assertEquals(listOf(SearchBarChip.SETTINGS, SearchBarChip.DATE), on)
        assertEquals("SETTINGS,DATE", SearchBarButtons.encode(on))
        assertEquals(listOf(SearchBarChip.DATE), SearchBarButtons.toggled(on, SearchBarChip.SETTINGS, on = false))
    }
}
