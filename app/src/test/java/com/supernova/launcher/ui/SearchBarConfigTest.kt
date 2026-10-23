package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

// The searchBarConfig pref must never take the launcher down: anything that
// isn't a valid document decodes to the defaults, which reproduce the
// pre-phase-4 behavior (no desktop bar, local drawer filter).
class SearchBarConfigTest {

    @Test
    fun drawerBarExtrasRoundTripAndDerive() {
        // "" placement derives from the legacy enabled flag.
        assertEquals(
            DrawerBarPlacement.TOP,
            DrawerBarPlacement.fromConfig(DrawerSearchBarConfig(enabled = true))
        )
        assertEquals(
            DrawerBarPlacement.HIDDEN,
            DrawerBarPlacement.fromConfig(DrawerSearchBarConfig(enabled = false))
        )
        val config = SearchBarConfig(
            drawer = DrawerSearchBarConfig(
                placement = "BOTTOM", radius = 14, filled = true,
                logo = "SUPERNOVA", logoMonochrome = false
            )
        )
        val decoded = SearchBarConfig.decode(config.encode())
        assertEquals(config, decoded)
        assertEquals(DrawerBarPlacement.BOTTOM, DrawerBarPlacement.fromConfig(decoded.drawer))
    }

    @Test
    fun blankDecodesToDefaults() {
        assertEquals(SearchBarConfig.DEFAULT, SearchBarConfig.decode(""))
        assertEquals(SearchBarConfig.DEFAULT, SearchBarConfig.decode("   "))
    }

    @Test
    fun garbageDecodesToDefaults() {
        assertEquals(SearchBarConfig.DEFAULT, SearchBarConfig.decode("not json"))
        assertEquals(SearchBarConfig.DEFAULT, SearchBarConfig.decode("""{"desktop":42}"""))
    }

    @Test
    fun unknownKeysAreIgnored() {
        val decoded = SearchBarConfig.decode(
            """{"desktop":{"placement":"DOCK_BELOW","future":true},"widget":{}}"""
        )
        assertEquals("DOCK_BELOW", decoded.desktop.placement)
        assertEquals(SearchBarConfig.DEFAULT.drawer, decoded.drawer)
    }

    @Test
    fun encodeRoundTrips() {
        val config = SearchBarConfig(
            desktop = DesktopSearchBarConfig(
                placement = "PERSISTENT",
                radius = 12,
                alpha = 0.5f,
                wireframe = true,
                shadow = true,
                chips = listOf("VOICE", "DATE")
            ),
            drawer = DrawerSearchBarConfig(enabled = false, globalOnTap = true)
        )
        assertEquals(config, SearchBarConfig.decode(config.encode()))
    }

    @Test
    fun defaultEncodesToEmpty() {
        assertEquals("", SearchBarConfig.DEFAULT.encode())
    }

    @Test
    fun unknownPlacementNameFallsBackToNone() {
        assertEquals(SearchBarPlacement.NONE, SearchBarPlacement.fromName("SIDEWAYS"))
    }

    // The starting layout writes one placement into an otherwise untouched
    // config, so the seeded bar has to survive a round trip without dragging
    // the rest of the document away from its defaults.
    @Test
    fun theStartingBarRoundTripsOnItsOwn() {
        val seeded = SearchBarConfig.DEFAULT.copy(
            desktop = SearchBarConfig.DEFAULT.desktop.copy(
                placement = SearchBarPlacement.DOCK_BELOW.name
            )
        )
        val decoded = SearchBarConfig.decode(seeded.encode())
        assertEquals(
            SearchBarPlacement.DOCK_BELOW,
            SearchBarPlacement.fromName(decoded.desktop.placement)
        )
        assertEquals(SearchBarConfig.DEFAULT.drawer, decoded.drawer)
        assertEquals(SearchBarConfig.DEFAULT.desktop.radius, decoded.desktop.radius)
    }

    @Test
    fun aBarSetUpBeforeTheCurrentStyleKeepsItsLook() {
        // What a phone set up by the old wizard stores: a placement and
        // nothing else. It keeps its text, its magnifier and no chips.
        val desktop = SearchBarConfig.decode("""{"desktop":{"placement":"DOCK_BELOW"}}""").desktop
        assertEquals(true, desktop.hint)
        assertEquals(false, desktop.shadow)
        assertEquals("SEARCH", desktop.logo)
        assertEquals(true, desktop.logoMonochrome)
        assertEquals(emptyList<String>(), desktop.chips)
    }

    @Test
    fun aFreshBarHasTheDefaultLookAndSurvivesTheStore() {
        val fresh = DesktopSearchBarConfig.FRESH
        assertEquals(SearchBarPlacement.DOCK_BELOW, SearchBarPlacement.fromName(fresh.placement))
        assertEquals(true, fresh.shadow)
        assertEquals(listOf(SearchBarChip.ASSISTANT), fresh.chips.mapNotNull(SearchBarChip::fromName))
        assertEquals(false, fresh.hint)
        val config = SearchBarConfig(desktop = fresh)
        assertEquals(config, SearchBarConfig.decode(config.encode()))
    }
}
