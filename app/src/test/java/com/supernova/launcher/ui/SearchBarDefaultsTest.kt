package com.supernova.launcher.ui

import com.supernova.launcher.data.LauncherPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

// Each bar's buttons until the user changes them: Voice search on the home
// bar, Play Store in the drawer field, the date and Settings on the feed bar.
class SearchBarDefaultsTest {

    @Test
    fun theHomeBarStartsWithVoiceSearch() {
        assertEquals(listOf(SearchBarChip.VOICE.name), DesktopSearchBarConfig.FRESH.chips)
    }

    @Test
    fun theDrawerFieldStartsWithPlayStore() {
        assertEquals(listOf(SearchBarChip.PLAY_STORE), SearchBarButtons.decode(LauncherPrefs().drawerActionButtons))
    }

    @Test
    fun theFeedBarStartsWithTheDateAndSettings() {
        assertEquals(
            listOf(SearchBarChip.DATE, SearchBarChip.SETTINGS),
            SearchBarButtons.decode(LauncherPrefs().feedBarButtons)
        )
    }

    // Turning every button off is kept as none, not the default again.
    @Test
    fun noButtonsStaysNone() {
        assertEquals(emptyList<SearchBarChip>(), SearchBarButtons.decode(""))
    }
}
