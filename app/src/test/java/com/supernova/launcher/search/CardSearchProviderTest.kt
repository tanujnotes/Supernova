package com.supernova.launcher.search

import com.supernova.launcher.feed.FeedCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Cards match by name with the app matcher's rules; disabled and unnamed
// cards never surface; the cap keeps a wall of cards out of the results.
class CardSearchProviderTest {

    private val cards = listOf(
        FeedCard(id = "1", name = "Morning Commute"),
        FeedCard(id = "2", name = "YouTube Music"),
        FeedCard(id = "3", name = "Hidden", enabled = false),
        FeedCard(id = "4", name = ""),
        FeedCard(id = "5", name = "Music Mix"),
        FeedCard(id = "6", name = "Music Two"),
        FeedCard(id = "7", name = "Music Three"),
        FeedCard(id = "8", name = "Music Four")
    )
    private val provider = CardSearchProvider { cards }

    @Test
    fun matchesSubstringsAndInitials() {
        assertEquals(listOf("1"), ids(provider.search("commute")))
        assertEquals(listOf("2"), ids(provider.search("ym")))
    }

    @Test
    fun skipsDisabledUnnamedAndBlankQueries() {
        assertTrue(provider.search("hidden").isEmpty())
        assertTrue(provider.search("   ").isEmpty())
        assertTrue(provider.search("zzz").isEmpty())
    }

    @Test
    fun capsResults() {
        assertEquals(CardSearchProvider.MAX_RESULTS, provider.search("music").size)
    }

    private fun ids(results: List<SearchResult>) =
        results.map { (it as SearchResult.Card).card.id }
}
