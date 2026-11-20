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
    fun customUrlTakesTheQueryAtItsMarkOrItsEnd() {
        val marked = WebEngine(WebProvider.CUSTOM, "https://searx.example.org/search?q=%s&lang=en")
        assertEquals("https://searx.example.org/search?q=a%26b&lang=en", marked.searchUrl("a&b"))
        assertEquals("searx.example.org", marked.name)
        val plain = WebEngine(WebProvider.CUSTOM, " https://www.startpage.com/do/search?q= ")
        assertEquals("https://www.startpage.com/do/search?q=hello+world", plain.searchUrl("hello world"))
        assertEquals("startpage.com", plain.name)
    }

    @Test
    fun customWithoutAUsableUrlSearchesGoogle() {
        for (url in listOf("", "searx.example.org/?q=%s", "ftp://example.org/%s")) {
            val engine = WebEngine(WebProvider.CUSTOM, url)
            assertEquals("https://www.google.com/search?q=hi", engine.searchUrl("hi"))
            assertEquals("Google", engine.name)
        }
        // A fixed engine ignores a URL left over from Custom.
        assertEquals("https://kagi.com/search?q=hi", WebEngine(WebProvider.KAGI, "https://x.org/%s").searchUrl("hi"))
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
        val provider = WebSearchProvider(provider = { WebEngine(WebProvider.BRAVE) })
        val results = provider.search("  query  ")
        assertEquals(
            listOf(
                SearchResult.Web("query", WebEngine(WebProvider.BRAVE)),
                SearchResult.ProviderChips("query")
            ),
            results
        )
    }

    @Test
    fun noConfiguredChipsDropsTheChipRow() {
        val provider = WebSearchProvider(
            provider = { WebEngine(WebProvider.GOOGLE) },
            chips = { emptyList() }
        )
        assertEquals(
            listOf(SearchResult.Web("query", WebEngine(WebProvider.GOOGLE))),
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
        val provider = WebSearchProvider(provider = { WebEngine(WebProvider.GOOGLE) })
        assertTrue(provider.search("   ").isEmpty())
    }
}
