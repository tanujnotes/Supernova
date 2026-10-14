package com.supernova.launcher.search

import com.supernova.launcher.feed.FeedCard

// Custom cards matched by name with the same rules the app filter uses, so
// "ym" finds a card called "YouTube Music" the way it finds the app. Only
// enabled, named cards take part; the immediate tier runs it synchronously
// off a cheap snapshot.
class CardSearchProvider(private val cards: () -> List<FeedCard>) : SearchProvider {

    override val id: String = ID

    override fun search(query: String): List<SearchResult> {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return emptyList()
        return cards()
            .filter { card ->
                card.enabled && card.name.isNotBlank() &&
                    AppMatcher.matches(card.name, trimmed)
            }
            .take(MAX_RESULTS)
            .map { SearchResult.Card(it) }
    }

    companion object {
        const val ID = "cards"
        const val MAX_RESULTS = 3
    }
}
