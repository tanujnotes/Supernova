package com.supernova.launcher.search

import com.supernova.launcher.data.LaunchableApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchFirstOpenableTest {

    private val app = SearchResult.App(LaunchableApp("p", "c", "App"))
    private val folder = SearchResult.Folder("id", null, "Folder", null)
    private val shortcut = SearchResult.Shortcut("p", "s", "Shortcut", 0L, null)
    private val contact = SearchResult.Contact(1L, "key", "Name", null)
    private val web = SearchResult.Web("q", WebProvider.GOOGLE)

    @Test
    fun followsTheListsOrderNotTheProviders() {
        assertEquals(app, SearchResult.firstOpenable(listOf(contact, shortcut, folder, app, web)))
        assertEquals(folder, SearchResult.firstOpenable(listOf(contact, shortcut, folder, web)))
        assertEquals(shortcut, SearchResult.firstOpenable(listOf(contact, shortcut, web)))
        assertEquals(contact, SearchResult.firstOpenable(listOf(contact, web)))
    }

    @Test
    fun onlyWebLeavesNothingToOpen() {
        assertNull(SearchResult.firstOpenable(listOf(web, SearchResult.Calculation("1+1", "2"))))
    }
}
