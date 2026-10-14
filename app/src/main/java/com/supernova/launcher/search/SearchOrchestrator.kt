package com.supernova.launcher.search

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Two-tier fan-out for the global search overlay. The immediate tier (apps,
// calculator, web fallback) runs synchronously on the caller so
// every keystroke paints without a frame of latency; the async tier
// (shortcuts, contacts - binder and content-provider backed) runs in a
// cancel-and-restart coroutine and re-delivers the merged list. A new query
// cancels the previous async pass; a stale pass that already finished is
// dropped by the generation check on the main dispatcher.
class SearchOrchestrator(
    private val immediateProviders: List<SearchProvider>,
    private val asyncProviders: List<SearchProvider>,
    private val scope: CoroutineScope,
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    // Per-provider enable flags, keyed by SearchProvider.id; the controller
    // backs this with the search prefs.
    private val isEnabled: (String) -> Boolean = { true }
) {

    private var asyncJob: Job? = null
    private var generation = 0

    // Must be called from the main thread (the generation guard relies on
    // callbacks and searches sharing the main dispatcher).
    fun search(query: String, onResults: (List<SearchResult>) -> Unit) {
        asyncJob?.cancel()
        generation++
        val startedGeneration = generation

        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            onResults(emptyList())
            return
        }

        val immediate = collect(immediateProviders, trimmed)
        onResults(sectioned(immediate))

        val active = asyncProviders.filter { isEnabled(it.id) }
        if (active.isEmpty()) return
        asyncJob = scope.launch(backgroundDispatcher) {
            val async = active.flatMap { provider ->
                runCatching { provider.search(trimmed) }.getOrDefault(emptyList())
            }
            ensureActive()
            if (async.isEmpty()) return@launch
            withContext(mainDispatcher) {
                if (generation == startedGeneration) {
                    onResults(sectioned(immediate + async))
                }
            }
        }
    }

    fun cancel() {
        asyncJob?.cancel()
        asyncJob = null
        // Orphans any async pass already past its ensureActive check.
        generation++
    }

    private fun collect(providers: List<SearchProvider>, query: String): List<SearchResult> =
        providers.filter { isEnabled(it.id) }.flatMap { provider ->
            runCatching { provider.search(query) }.getOrDefault(emptyList())
        }

    // Stable sort groups results into display sections regardless of which
    // tier produced them, keeping each provider's own ordering within a group.
    private fun sectioned(results: List<SearchResult>): List<SearchResult> =
        results.sortedBy(::sectionRank)

    private fun sectionRank(result: SearchResult): Int = when (result) {
        is SearchResult.Calculation -> 0
        is SearchResult.App -> 1
        is SearchResult.Card -> 2
        is SearchResult.Folder -> 3
        is SearchResult.Shortcut -> 4
        is SearchResult.Contact -> 5
        is SearchResult.Web -> 6
        is SearchResult.ProviderChips -> 7
    }
}
