package com.supernova.launcher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchProviderTest {

    @Test
    fun urlTemplatesEncodeTheQuery() {
        assertEquals(
            "https://www.google.com/search?q=caf%C3%A9+prices",
            WebProvider.GOOGLE.searchUrl("café prices")
        )
        assertEquals(
            "https://duckduckgo.com/?q=a%26b",
            WebProvider.DUCKDUCKGO.searchUrl("a&b")
        )
        assertEquals(
            "https://search.brave.com/search?q=hello",
            WebProvider.BRAVE.searchUrl("hello")
        )
        assertEquals(
            "https://www.bing.com/search?q=hello",
            WebProvider.BING.searchUrl("hello")
        )
    }

    @Test
    fun chipTemplatesEncodeTheQuery() {
        assertEquals("market://search?q=chess", SearchChip.PLAY_STORE.url("chess"))
        assertEquals("geo:0,0?q=pizza+near+me", SearchChip.MAPS.url("pizza near me"))
        assertEquals(
            "https://www.youtube.com/results?search_query=lo-fi",
            SearchChip.YOUTUBE.url("lo-fi")
        )
        assertEquals(
            "https://en.wikipedia.org/wiki/Special:Search?search=t%C3%BCbingen",
            SearchChip.WIKIPEDIA.url("tübingen")
        )
    }

    @Test
    fun unknownProviderNameFallsBackToGoogle() {
        assertEquals(WebProvider.GOOGLE, WebProvider.fromName("ALTAVISTA"))
        assertEquals(WebProvider.BING, WebProvider.fromName("bing"))
    }

    @Test
    fun providerEmitsOneWebRowAndOneChipRow() {
        val provider = WebSearchProvider(provider = { WebProvider.BRAVE })
        val results = provider.search("  query  ")
        assertEquals(
            listOf(
                SearchResult.Web("query", WebProvider.BRAVE),
                SearchResult.ProviderChips("query")
            ),
            results
        )
    }

    @Test
    fun noConfiguredChipsDropsTheChipRow() {
        val provider = WebSearchProvider(
            provider = { WebProvider.GOOGLE },
            chips = { emptyList() }
        )
        assertEquals(
            listOf(SearchResult.Web("query", WebProvider.GOOGLE)),
            provider.search("query")
        )
    }

    @Test
    fun chipCodecRoundTrips() {
        assertEquals(SearchChip.defaults, SearchChip.decode(""))
        assertEquals(emptyList<SearchChip>(), SearchChip.decode("NONE"))
        assertEquals("NONE", SearchChip.encode(emptyList()))
        val picked = listOf(SearchChip.SPOTIFY, SearchChip.GOOGLE)
        assertEquals(picked, SearchChip.decode(SearchChip.encode(picked)))
        // Unknown tokens are dropped rather than failing the whole pref.
        assertEquals(listOf(SearchChip.MAPS), SearchChip.decode("MAPS,ALTAVISTA"))
    }

    @Test
    fun blankQueryEmitsNothing() {
        val provider = WebSearchProvider(provider = { WebProvider.GOOGLE })
        assertTrue(provider.search("   ").isEmpty())
    }
}
