package com.supernova.launcher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The forgiving matcher behind the search window, checked against "pla":
// Play Store, Play Console, Olauncher and Pro Launcher all match, each for
// a different reason.
class AppMatcherTest {

    private fun ranges(label: String, query: String): List<IntRange>? =
        AppMatcher.match(label, query)?.ranges

    private fun tier(label: String, query: String): Int? = AppMatcher.match(label, query)?.tier

    @Test
    fun prefixOfTheLabelIsTheBestTier() {
        assertEquals(AppMatcher.TIER_PREFIX, tier("Play Store", "pla"))
        assertEquals(listOf(0 until 3), ranges("Play Store", "pla"))
    }

    @Test
    fun prefixOfALaterWord() {
        assertEquals(AppMatcher.TIER_WORD_PREFIX, tier("Google Calendar", "cal"))
        assertEquals(listOf(7 until 10), ranges("Google Calendar", "cal"))
    }

    @Test
    fun substringInsideAWord() {
        assertEquals(AppMatcher.TIER_SUBSTRING, tier("LocalSend", "cal"))
        assertEquals(listOf(2 until 5), ranges("LocalSend", "cal"))
    }

    @Test
    fun runsAcrossWordStarts() {
        assertEquals(AppMatcher.TIER_LOOSE, tier("Pro Launcher", "pla"))
        assertEquals(listOf(0 until 1, 4 until 6), ranges("Pro Launcher", "pla"))
        // Initials, the old rule, are the one-letter case of this.
        assertEquals(listOf(0 until 1, 8 until 9), ranges("YouTube Music", "ym"))
        // A greedy first run would eat "pl" and strand the "a"; it backtracks.
        assertEquals(listOf(0 until 1, 4 until 6), ranges("Plx Launcher", "pla"))
    }

    @Test
    fun oneTypoIsForgiven() {
        assertEquals(AppMatcher.TIER_LOOSE, tier("Olauncher", "pla"))
        assertEquals(listOf(0 until 3), ranges("Olauncher", "pla"))
        assertEquals(listOf(0 until 3), ranges("Calendar", "cla"))
        // One letter too many: with nothing to substitute against, the
        // extra letter is dropped.
        assertEquals(listOf(0 until 4), ranges("Maps", "mapsx"))
        // A skipped letter: the whole word the query spelled around it.
        assertEquals(listOf(0 until 6), ranges("Chrome", "chome"))
    }

    @Test
    fun typosNeedThreeLettersAndTwoAreTooMany() {
        assertNull(AppMatcher.match("Olauncher", "pl"))
        // Dropping a letter from a three-letter query would leave two.
        assertNull(AppMatcher.match("Zeta Launcher", "pla"))
        assertNull(AppMatcher.match("Olauncher", "xya"))
        assertNull(AppMatcher.match("Chrome", "xyz"))
    }

    @Test
    fun caseAndWhitespaceDoNotMatter() {
        assertTrue(AppMatcher.matches("Play Store", "  PLA "))
        assertNull(AppMatcher.match("Play Store", "   "))
    }

    @Test
    fun providerRanksByTierThenLaunchesThenName() {
        val apps = listOf("Olauncher", "Play Console", "Play Store", "Pro Launcher").map {
            com.supernova.launcher.data.LaunchableApp("com.$it", "com.$it.Main", it)
        }
        val store = apps[2]
        val provider = AppSearchProvider(
            apps = { apps },
            launchCounts = { mapOf(AppSearchProvider.countKey(store) to 5) }
        )
        val results = provider.search("pla").map { it as SearchResult.App }
        assertEquals(
            listOf("Play Store", "Play Console", "Olauncher", "Pro Launcher"),
            results.map { it.app.label }
        )
        assertEquals(listOf(0 until 1, 4 until 6), results.last().highlight)
    }
}
