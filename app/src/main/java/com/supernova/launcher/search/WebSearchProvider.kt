package com.supernova.launcher.search

import com.supernova.launcher.data.SettingEnumSerializer
import java.net.URLEncoder

// The user's configured web search engine. Display names are brand names and
// deliberately not translated.
@kotlinx.serialization.Serializable(with = WebProvider.Names::class)
enum class WebProvider(val displayName: String) {
    GOOGLE("Google"),
    DUCKDUCKGO("DuckDuckGo"),
    BRAVE("Brave"),
    BING("Bing"),
    ECOSIA("Ecosia"),
    STARTPAGE("Startpage"),
    QWANT("Qwant"),
    YAHOO("Yahoo"),
    YANDEX("Yandex"),
    KAGI("Kagi"),

    // A search URL the user typed (searchWebCustomUrl): see WebEngine.
    CUSTOM("Custom");

    fun searchUrl(query: String): String = when (this) {
        GOOGLE -> "https://www.google.com/search?q=${encodeQuery(query)}"
        DUCKDUCKGO -> "https://duckduckgo.com/?q=${encodeQuery(query)}"
        BRAVE -> "https://search.brave.com/search?q=${encodeQuery(query)}"
        BING -> "https://www.bing.com/search?q=${encodeQuery(query)}"
        ECOSIA -> "https://www.ecosia.org/search?q=${encodeQuery(query)}"
        STARTPAGE -> "https://www.startpage.com/do/search?q=${encodeQuery(query)}"
        QWANT -> "https://www.qwant.com/?q=${encodeQuery(query)}"
        YAHOO -> "https://search.yahoo.com/search?p=${encodeQuery(query)}"
        YANDEX -> "https://yandex.com/search/?text=${encodeQuery(query)}"
        KAGI -> "https://kagi.com/search?q=${encodeQuery(query)}"
        // Only reached without a usable URL: WebEngine reads the typed one.
        CUSTOM -> GOOGLE.searchUrl(query)
    }

    // Its name in the store and in a backup, read back leniently.
    object Names : SettingEnumSerializer<WebProvider>("WebProvider", entries, GOOGLE)

    companion object {
        fun fromName(name: String): WebProvider = Names.parse(name)
    }
}

// The web search as set: the engine, or for Custom the URL the user typed,
// with %s where the query goes (or the query added at the end). A Custom
// without a usable URL searches Google.
data class WebEngine(val provider: WebProvider, val customUrl: String = "") {

    private val custom: String? =
        customUrl.trim().takeIf { provider == WebProvider.CUSTOM && isUsableUrl(it) }

    // The engine's name, for "Search <name> for ...": a Custom one goes by
    // its site.
    val name: String
        get() {
            val site = custom?.let { runCatching { java.net.URI(it.replace(QUERY_MARK, "q")).host }.getOrNull() }
            return site?.removePrefix("www.") ?: when (provider) {
                WebProvider.CUSTOM -> WebProvider.GOOGLE.displayName
                else -> provider.displayName
            }
        }

    fun searchUrl(query: String): String {
        val url = custom ?: return provider.searchUrl(query)
        val encoded = encodeQuery(query)
        return if (QUERY_MARK in url) url.replace(QUERY_MARK, encoded) else url + encoded
    }

    companion object {
        const val QUERY_MARK = "%s"

        fun of(prefs: com.supernova.launcher.data.LauncherPrefs?): WebEngine =
            WebEngine(prefs?.searchWebProvider ?: WebProvider.GOOGLE, prefs?.searchWebCustomUrl.orEmpty())

        // An http or https address with a host.
        fun isUsableUrl(url: String): Boolean {
            // %s is no valid URI escape: stand a letter in for it first.
            val uri = runCatching { java.net.URI(url.trim().replace(QUERY_MARK, "q")) }.getOrNull() ?: return false
            return (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrEmpty()
        }
    }
}

// One-tap alternate search targets rendered as a pill row under the web row.
// Every URL is launched with ACTION_VIEW; a missing handler (no Play Store, no
// maps app) falls back to the chip's web page, and with nothing to open it
// the user is told and the search stays.
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

    // The web page for a chip whose own link needs an app (market:, geo:),
    // opened when no app takes that link; null where url is a web page already.
    fun fallbackUrl(query: String): String? = when (this) {
        PLAY_STORE -> "https://play.google.com/store/search?q=${encodeQuery(query)}&c=apps"
        MAPS -> "https://www.google.com/maps/search/?api=1&query=${encodeQuery(query)}"
        else -> null
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
    private val provider: () -> WebEngine,
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
