package com.supernova.launcher.search

import com.supernova.launcher.search.ContactRanking.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

// The contacts provider returns filter matches in the order contacts were
// added; ranking has to happen before the cap or the name typed can vanish
// behind older, longer matches.
class ContactRankingTest {

    private var nextId = 0L

    private fun contact(name: String, starred: Boolean = false, id: Long = ++nextId) =
        Candidate(contactId = id, lookupKey = "key$id", name = name, starred = starred)

    private fun top(candidates: List<Candidate>, query: String, limit: Int = 8) =
        ContactRanking.top(candidates, query, limit, Locale.ENGLISH).map { it.name }

    // Older contacts first, the way the provider hands them back.
    private val providerOrder = listOf(
        "Alexander Brown", "Alexander Smith", "Alexandra", "Alessandro", "Alexis",
        "Alexander King", "Alejandro", "Alexei", "Alex", "Ale", "Ale2"
    ).map { contact(it) }

    @Test
    fun theNameTypedSurvivesTheCap() {
        val shown = top(providerOrder, "Ale")
        assertEquals(8, shown.size)
        assertEquals(listOf("Ale", "Ale2"), shown.take(2))
    }

    @Test
    fun shortNamesStayInReachOfAShorterQuery() {
        val shown = top(providerOrder, "Al")
        assertTrue(shown.containsAll(listOf("Ale", "Ale2")))
        assertEquals("Ale", shown.first())
    }

    @Test
    fun theWholeNameRanksAboveEveryPrefix() {
        assertEquals("Ale2", top(providerOrder, "ale2").first())
        assertEquals("Alex", top(providerOrder, "alex").first())
    }

    @Test
    fun prefixesBeforeLaterWordsBeforeNicknameMatches() {
        val candidates = listOf(
            contact("Robert Lee"), // the provider matched a nickname "Ale"
            contact("Mark Alexander"),
            contact("Alessia")
        )
        assertEquals(listOf("Alessia", "Mark Alexander", "Robert Lee"), top(candidates, "ale"))
    }

    @Test
    fun starredLeadsWithinARankButNotAcrossRanks() {
        val candidates = listOf(
            contact("Alessia"),
            contact("Alexandra", starred = true),
            contact("Ale"),
            contact("Mark Ale", starred = true)
        )
        assertEquals(listOf("Ale", "Alexandra", "Alessia", "Mark Ale"), top(candidates, "ale"))
    }

    @Test
    fun accentsRankLikeThePlainLetters() {
        val candidates = listOf(contact("Zoe Ale"), contact("Álex"), contact("Alé"))
        assertEquals(listOf("Alé", "Álex", "Zoe Ale"), top(candidates, "ale"))
        assertEquals("Álex", top(candidates, "álex").first())
    }

    @Test
    fun aContactRepeatedByTheProviderIsShownOnce() {
        val candidates = listOf(contact("Ale", id = 7), contact("Ale", id = 7), contact("Alex"))
        assertEquals(listOf("Ale", "Alex"), top(candidates, "al"))
    }

    @Test
    fun blankQueriesAndTheLimit() {
        assertTrue(top(providerOrder, "  ").isEmpty())
        assertEquals(3, top(providerOrder, "al", limit = 3).size)
    }
}
