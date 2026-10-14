package com.supernova.launcher.search

import java.net.URLEncoder

// The user's configured web search engine. Display names are brand names and
// deliberately not translated.
enum class WebProvider(val displayName: String) {
    GOOGLE("Google"),
    DUCKDUCKGO("DuckDuckGo"),
    BRAVE("Brave"),
    BING("Bing");

    fun searchUrl(query: String): String = when (this) {
        GOOGLE -> "https://www.google.com/search?q=${encodeQuery(query)}"
        DUCKDUCKGO -> "https://duckduckgo.com/?q=${encodeQuery(query)}"
        BRAVE -> "https://search.brave.com/search?q=${encodeQuery(query)}"
        BING -> "https://www.bing.com/search?q=${encodeQuery(query)}"
    }

    companion object {
        fun fromName(name: String): WebProvider =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: GOOGLE
    }
}

// One-tap alternate search targets rendered as a pill row under the web row.
// Every URL is launched blind with ACTION_VIEW inside runCatching, so a
// missing handler (no Play Store, no maps app) degrades to a no-op.
enum class SearchChip {
    PLAY_STORE,
    MAPS,
    YOUTUBE,
    WIKIPEDIA,
    SPOTIFY,
    GOOGLE;

    // The app whose launcher icon stands for this provider in the no-results
    // row. Null or not installed falls back to a lettered circle.
    val iconPackage: String?
        get() = when (this) {
            PLAY_STORE -> "com.android.vending"
            MAPS -> "com.google.android.apps.maps"
            YOUTUBE -> "com.google.android.youtube"
            WIKIPEDIA -> "org.wikipedia"
            SPOTIFY -> "com.spotify.music"
            GOOGLE -> "com.google.android.googlequicksearchbox"
        }

    fun url(query: String): String = when (this) {
        PLAY_STORE -> "market://search?q=${encodeQuery(query)}"
        MAPS -> "geo:0,0?q=${encodeQuery(query)}"
        YOUTUBE -> "https://www.youtube.com/results?search_query=${encodeQuery(query)}"
        WIKIPEDIA -> "https://en.wikipedia.org/wiki/Special:Search?search=${encodeQuery(query)}"
        SPOTIFY -> "https://open.spotify.com/search/${encodeQuery(query)}"
        GOOGLE -> "https://www.google.com/search?q=${encodeQuery(query)}"
    }

    // The chip row is user-ordered, so the pref keeps a list rather than a set.
    // "" means the defaults below; "NONE" means the row is off entirely.
    companion object {
        const val NONE_TOKEN = "NONE"

        // What the row drew before it was configurable.
        val defaults = listOf(PLAY_STORE, MAPS, YOUTUBE, WIKIPEDIA)

        fun decode(raw: String): List<SearchChip> = when {
            raw.isEmpty() -> defaults
            raw.equals(NONE_TOKEN, ignoreCase = true) -> emptyList()
            else -> raw.split(',').mapNotNull { token ->
                entries.firstOrNull { it.name.equals(token.trim(), ignoreCase = true) }
            }
        }

        fun encode(chips: List<SearchChip>): String =
            if (chips.isEmpty()) NONE_TOKEN else chips.joinToString(",") { it.name }
    }
}

// URLEncoder is form-encoding (space -> '+'), which every target above
// accepts in query parameters.
private fun encodeQuery(query: String): String = URLEncoder.encode(query, "UTF-8")

// Always offers the fallback: one "search the web" row plus the chip row.
// The provider is read per query so a settings change applies immediately.
class WebSearchProvider(
    private val provider: () -> WebProvider,
    // Empty (the user unticked every chip) drops the row rather than drawing
    // an empty scroller.
    private val chips: () -> List<SearchChip> = { SearchChip.defaults }
) : SearchProvider {

    override val id: String = ID

    override fun search(query: String): List<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val web = SearchResult.Web(trimmed, provider())
        return if (chips().isEmpty()) listOf(web) else listOf(web, SearchResult.ProviderChips(trimmed))
    }

    companion object {
        const val ID = "web"
    }
}
