package com.supernova.launcher.search

// Matches launcher folders (workspace folder items and drawer folder-groups)
// by name (D3). The host supplies cheap (label, id) hits and resolves icons
// only for the few results that actually match.
class FolderSearchProvider(
    private val hits: () -> List<Hit>,
    private val icon: (Hit) -> android.graphics.drawable.Drawable?
) : SearchProvider {

    data class Hit(val itemId: String?, val groupName: String?, val label: String)

    override val id: String = ID

    override fun search(query: String): List<SearchResult> {
        val all = hits().filter { it.label.isNotBlank() }
        val matched = all.filter { it.label.startsWith(query, ignoreCase = true) } +
            all.filter {
                !it.label.startsWith(query, ignoreCase = true) &&
                    it.label.contains(query, ignoreCase = true)
            }
        return matched.take(MAX_RESULTS).map { hit ->
            SearchResult.Folder(hit.itemId, hit.groupName, hit.label, icon(hit))
        }
    }

    companion object {
        const val ID = "folders"
        private const val MAX_RESULTS = 4
    }
}
