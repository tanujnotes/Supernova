package com.supernova.launcher.search

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchOrchestratorTest {

    private class FakeProvider(
        override val id: String,
        private val results: (String) -> List<SearchResult>
    ) : SearchProvider {
        var searchCount = 0
            private set

        override fun search(query: String): List<SearchResult> {
            searchCount++
            return results(query)
        }
    }

    private fun webResult(query: String) = SearchResult.Web(query, WebProvider.GOOGLE)

    private fun calcResult(query: String) = SearchResult.Calculation(query, "42")

    private fun shortcutResult(label: String) =
        SearchResult.Shortcut("com.a", "id", label, 0L, icon = null)

    private fun orchestrator(
        scope: TestScope,
        immediate: List<SearchProvider>,
        async: List<SearchProvider>,
        isEnabled: (String) -> Boolean = { true }
    ): SearchOrchestrator {
        // The TestScope itself, not backgroundScope: advanceUntilIdle doesn't
        // process background-only work in coroutines-test 1.8, and every
        // orchestrator job here either completes or is cancelled, so runTest
        // never waits on a leak.
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        return SearchOrchestrator(
            immediateProviders = immediate,
            asyncProviders = async,
            scope = scope,
            backgroundDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            isEnabled = isEnabled
        )
    }

    @Test
    fun immediateTierDeliversSynchronously() = runTest {
        val immediate = FakeProvider("web") { listOf(webResult(it)) }
        val subject = orchestrator(this, listOf(immediate), emptyList())

        var delivered: List<SearchResult>? = null
        subject.search("cats") { delivered = it }

        // No scheduler advance: the immediate tier ran on the caller.
        assertEquals(listOf<SearchResult>(webResult("cats")), delivered)
    }

    @Test
    fun asyncTierMergesIntoASecondDelivery() = runTest {
        val immediate = FakeProvider("web") { listOf(webResult(it)) }
        val async = FakeProvider("shortcuts") { listOf(shortcutResult("Compose")) }
        val subject = orchestrator(this, listOf(immediate), listOf(async))

        val deliveries = mutableListOf<List<SearchResult>>()
        subject.search("co") { deliveries.add(it) }
        advanceUntilIdle()

        assertEquals(2, deliveries.size)
        // Merged list is sectioned: shortcut before web despite arriving later.
        assertEquals(
            listOf(shortcutResult("Compose"), webResult("co")),
            deliveries.last()
        )
    }

    @Test
    fun newQueryCancelsThePreviousAsyncPass() = runTest {
        val async = FakeProvider("shortcuts") { listOf(shortcutResult(it)) }
        val subject = orchestrator(this, emptyList(), listOf(async))

        val firstDeliveries = mutableListOf<List<SearchResult>>()
        subject.search("first") { firstDeliveries.add(it) }
        // Restart before the async pass gets to run.
        val secondDeliveries = mutableListOf<List<SearchResult>>()
        subject.search("second") { secondDeliveries.add(it) }
        advanceUntilIdle()

        // The first query's async merge never lands; the second's does.
        assertEquals(1, firstDeliveries.size)
        assertTrue(firstDeliveries.single().isEmpty())
        assertEquals(
            listOf<SearchResult>(shortcutResult("second")),
            secondDeliveries.last()
        )
    }

    @Test
    fun cancelDropsAnInFlightPass() = runTest {
        val async = FakeProvider("shortcuts") { listOf(shortcutResult(it)) }
        val subject = orchestrator(this, emptyList(), listOf(async))

        val deliveries = mutableListOf<List<SearchResult>>()
        subject.search("query") { deliveries.add(it) }
        subject.cancel()
        advanceUntilIdle()

        assertEquals(1, deliveries.size)
    }

    @Test
    fun disabledProvidersAreSkipped() = runTest {
        val calc = FakeProvider("math") { listOf(calcResult(it)) }
        val web = FakeProvider("web") { listOf(webResult(it)) }
        val contacts = FakeProvider("contacts") {
            listOf(SearchResult.Contact(1L, "key", "Ada", photo = null))
        }
        val subject = orchestrator(
            this, listOf(calc, web), listOf(contacts),
            isEnabled = { it != "math" && it != "contacts" }
        )

        val deliveries = mutableListOf<List<SearchResult>>()
        subject.search("1+1") { deliveries.add(it) }
        advanceUntilIdle()

        assertEquals(listOf(listOf<SearchResult>(webResult("1+1"))), deliveries)
        assertEquals(0, calc.searchCount)
        assertEquals(0, contacts.searchCount)
    }

    @Test
    fun blankQueryDeliversEmptyAndSkipsProviders() = runTest {
        val web = FakeProvider("web") { listOf(webResult(it)) }
        val subject = orchestrator(this, listOf(web), emptyList())

        var delivered: List<SearchResult>? = null
        subject.search("   ") { delivered = it }
        advanceUntilIdle()

        assertEquals(emptyList<SearchResult>(), delivered)
        assertEquals(0, web.searchCount)
    }

    @Test
    fun resultsAreSectionedInDisplayOrder() = runTest {
        val jumbled = FakeProvider("web") {
            listOf(
                SearchResult.ProviderChips(it),
                webResult(it),
                SearchResult.App(com.supernova.launcher.data.LaunchableApp("com.a", "com.a.M", "A")),
                calcResult(it)
            )
        }
        val subject = orchestrator(this, listOf(jumbled), emptyList())

        var delivered: List<SearchResult> = emptyList()
        subject.search("q") { delivered = it }

        assertTrue(delivered[0] is SearchResult.Calculation)
        assertTrue(delivered[1] is SearchResult.App)
        assertTrue(delivered[2] is SearchResult.Web)
        assertTrue(delivered[3] is SearchResult.ProviderChips)
    }

    @Test
    fun throwingProviderIsIsolated() = runTest {
        val broken = FakeProvider("broken") { throw IllegalStateException("boom") }
        val web = FakeProvider("web") { listOf(webResult(it)) }
        val subject = orchestrator(this, listOf(broken, web), emptyList())

        var delivered: List<SearchResult>? = null
        subject.search("q") { delivered = it }

        assertEquals(listOf<SearchResult>(webResult("q")), delivered)
    }
}
