package com.supernova.launcher.search

// The private space as one search row, the way back to a space the system
// hides while it is locked: Android tells users to search for it in the
// launcher. label is the row's name, null while the phone has no private
// space. A query matches when the name, or one of its words, starts with it,
// from MIN_QUERY letters on, so a single letter doesn't bring it up.
class PrivateSpaceSearchProvider(
    private val label: () -> String?,
    private val icon: () -> android.graphics.drawable.Drawable?
) : SearchProvider {

    override val id: String = ID

    override fun search(query: String): List<SearchResult> {
        val name = label()?.takeIf { matches(it, query) } ?: return emptyList()
        return listOf(
            SearchResult.Folder(itemId = null, groupName = null, label = name, icon = icon(), privateSpace = true)
        )
    }

    companion object {
        const val ID = "private_space"
        private const val MIN_QUERY = 3

        fun matches(name: String, query: String): Boolean {
            val q = query.trim()
            return q.length >= MIN_QUERY &&
                (name.startsWith(q, ignoreCase = true) || name.split(' ').any { it.startsWith(q, ignoreCase = true) })
        }
    }
}
