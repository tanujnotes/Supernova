package com.supernova.launcher.search

// One search source feeding the global search surface. Implementations must
// be callable off the main thread; in-memory providers (apps, calculator)
// should return synchronously and fast, binder/content-provider sources are
// invoked from a cancellable coroutine by the orchestrator.
interface SearchProvider {
    val id: String
    fun search(query: String): List<SearchResult>
}

// Result variants for the global search surface. Drawables ride along where
// the producing provider already resolved them off the main thread (shortcut
// and contact icons), so binds stay allocation-free.
sealed class SearchResult {
    data class Calculation(val expression: String, val display: String) : SearchResult()
    // highlight: the label ranges the query matched (AppMatcher), which
    // the search window emboldens.
    data class App(
        val app: com.supernova.launcher.data.LaunchableApp,
        val highlight: List<IntRange> = emptyList()
    ) : SearchResult()
    data class Shortcut(
        val packageName: String,
        val shortcutId: String,
        val label: String,
        val userSerial: Long,
        val icon: android.graphics.drawable.Drawable?
    ) : SearchResult()
    data class Contact(
        val contactId: Long,
        val lookupKey: String,
        val name: String,
        val photo: android.graphics.drawable.Drawable?,
        // First phone number, resolved only when contact cards are on (D4).
        val phoneNumber: String? = null
    ) : SearchResult()
    // A launcher folder (workspace item or drawer folder-group); exactly one
    // of itemId / groupName is set. The icon is resolved at match time.
    data class Folder(
        val itemId: String?,
        val groupName: String?,
        val label: String,
        val icon: android.graphics.drawable.Drawable?
    ) : SearchResult()

    // A custom card matched by name; rendered as the full card.
    data class Card(val card: com.supernova.launcher.feed.FeedCard) : SearchResult()

    data class Web(val query: String, val provider: WebProvider) : SearchResult()
    data class ProviderChips(val query: String) : SearchResult()
}

class MathSearchProvider : SearchProvider {
    override val id: String = ID

    override fun search(query: String): List<SearchResult> {
        val result = Calculator.evaluate(query) ?: return emptyList()
        return listOf(SearchResult.Calculation(query, result.display))
    }

    companion object {
        const val ID = "math"
    }
}
