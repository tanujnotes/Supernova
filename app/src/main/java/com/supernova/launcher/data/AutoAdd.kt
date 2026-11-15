package com.supernova.launcher.data

// Where "Add new apps to Home screen" puts a new install: pages are searched
// from the first, top row first; with every page full the app goes on a new
// page after the last. Only when no page can be added is it left out.
object AutoAdd {

    fun place(current: LauncherModel, app: LaunchableApp, itemId: String, cols: Int, rows: Int): LauncherModel {
        val placed = current.items.any {
            it.itemType == ItemType.APP && it.packageName == app.packageName && it.userSerial == app.userSerial
        }
        val spot = if (placed) null else spotFor(current, cols, rows)
        return if (spot == null) {
            current
        } else {
            val (page, cell) = spot
            val item = LauncherItem(
                id = itemId,
                itemType = ItemType.APP,
                container = ContainerType.WORKSPACE,
                page = page,
                cellX = cell.first,
                cellY = cell.second,
                label = app.label,
                packageName = app.packageName,
                className = app.className,
                userSerial = app.userSerial
            )
            current.copy(items = current.items + item, pageCount = maxOf(current.pageCount, page + 1))
        }
    }

    private fun spotFor(current: LauncherModel, cols: Int, rows: Int): Pair<Int, Pair<Int, Int>>? {
        // The pager shows a page for every item, whatever pageCount says.
        val shown = maxOf(
            1,
            current.pageCount,
            (current.items.filter { it.container == ContainerType.WORKSPACE }.maxOfOrNull { it.page } ?: -1) + 1
        )
        return (0 until shown).firstNotNullOfOrNull { page ->
            WorkspaceGeometry.findFreeCell(current, page, 1, 1, cols, rows)?.let { page to it }
        } ?: (shown to (0 to 0)).takeIf { shown < LauncherRepository.MAX_PAGES }
    }
}
