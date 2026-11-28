package com.supernova.launcher.data

// A home page removed with what was on it (#215): its items, folder members
// included, kept as they were for Undo.
class RemovedPage(val page: Int, val items: List<LauncherItem>, val wasDefault: Boolean) {
    val widgets: List<LauncherItem> get() = items.filter { it.itemType == ItemType.WIDGET }
}

// Removing a page with its content, and putting it back. Pure, for tests.
object PageRemoval {

    // The pager shows a page for every item, whatever pageCount says.
    private fun shownPages(model: LauncherModel): Int = maxOf(
        model.pageCount,
        (model.items.filter { it.container == ContainerType.WORKSPACE }.maxOfOrNull { it.page } ?: -1) + 1
    )

    // The page goes, and everything on it: the folders' apps with their
    // folders. The pages after it move down. Null when it is the only page
    // or not a page at all.
    fun removed(model: LauncherModel, page: Int): Pair<LauncherModel, RemovedPage>? {
        val shown = shownPages(model)
        if (shown <= 1 || page !in 0 until shown) return null
        val onPage = model.items.filter { it.container == ContainerType.WORKSPACE && it.page == page }
        // Everything in its folders, the folders inside them included.
        val members = onPage.filter { it.itemType == ItemType.FOLDER }
            .flatMap { NestedFolders.contents(model.items, it.id) }
        val gone = (onPage + members).mapTo(mutableSetOf()) { it.id }
        val left = model.copy(items = model.items.filterNot { it.id in gone })
        val after = LauncherRepository.withoutPages(left.copy(pageCount = shown), listOf(page))
        return after to RemovedPage(page, onPage + members, wasDefault = model.defaultPage == page)
    }

    // Undo: an empty page goes back in where it was, the pages from there on
    // move up, and the items return to it as they were. Items back already
    // (by some other route) are left alone.
    fun restored(model: LauncherModel, removed: RemovedPage): LauncherModel {
        val shown = shownPages(model)
        val page = removed.page.coerceIn(0, shown)
        val existing = model.items.mapTo(mutableSetOf()) { it.id }
        val returning = removed.items.filter { it.id !in existing }.map {
            if (it.container == ContainerType.WORKSPACE) it.copy(page = page) else it
        }
        val default = when {
            removed.wasDefault -> page
            model.defaultPage >= page -> model.defaultPage + 1
            else -> model.defaultPage
        }
        return model.copy(
            pageCount = shown + 1,
            defaultPage = default,
            items = model.items.map {
                if (it.container == ContainerType.WORKSPACE && it.page >= page) it.copy(page = it.page + 1) else it
            } + returning
        )
    }
}
