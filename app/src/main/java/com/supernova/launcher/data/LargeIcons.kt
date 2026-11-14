package com.supernova.launcher.data

// A home screen icon made large (its long-press menu's Large icon): it takes
// LARGE_ICON_SPAN x LARGE_ICON_SPAN cells, its spanX/spanY, and its icon is
// drawn that many times the desktop size. Only on the desktop: the dock and
// folders have one size, and an icon moved there is made small again
// (LargeIcons.withSmallIconsOffDesktop).
const val LARGE_ICON_SPAN = 2

val LauncherItem.isLargeIcon: Boolean
    get() = itemType != ItemType.WIDGET && container == ContainerType.WORKSPACE && spanX > 1

// The layout changes that make an icon large or small: pure, for tests, and
// run by LauncherRepository.
object LargeIcons {

    // Every write ends here: an icon in the dock or a folder is one
    // size, so a large one moved there (by any path) is made small, and
    // comes back small if it goes back to the desktop.
    internal fun withSmallIconsOffDesktop(model: LauncherModel): LauncherModel {
        fun shrinks(item: LauncherItem) = item.itemType != ItemType.WIDGET &&
            item.container != ContainerType.WORKSPACE && (item.spanX != 1 || item.spanY != 1)
        if (model.items.none(::shrinks)) return model
        return model.copy(items = model.items.map { if (shrinks(it)) it.copy(spanX = 1, spanY = 1) else it })
    }

    // setIconSize's model change, pure for tests, on the layout as
    // shown: null when it can't be made. Large takes the 2x2 from the
    // icon's cell (or the nearest 2x2 holding that cell) when it is free,
    // else pushes the neighbours aside as a drop there would, else moves
    // to the nearest free 2x2 on the page. Normal keeps the top-left
    // cell, which a large icon always holds.
    internal fun withIconSize(
        current: LauncherModel,
        itemId: String,
        large: Boolean,
        cols: Int,
        rows: Int
    ): LauncherModel? {
        val item = current.items.firstOrNull {
            it.id == itemId && it.container == ContainerType.WORKSPACE && it.itemType != ItemType.WIDGET
        }
        val placement = when {
            item == null -> null
            item.isLargeIcon == large -> Triple(current, item.cellX, item.cellY)
            large -> largePlacement(current, item, cols, rows)
            else -> Triple(current, item.cellX, item.cellY)
        } ?: return null
        val (model, cellX, cellY) = placement
        val span = if (large) LARGE_ICON_SPAN else 1
        val (wx, wy) = WorkspaceGeometry.cellCenteredPosition(cellX, cellY, cols, rows, span to span)
        return model.copy(
            items = model.items.map {
                if (it.id != itemId) {
                    it
                } else {
                    it.copy(
                        cellX = cellX, cellY = cellY, spanX = span, spanY = span, workspaceX = wx, workspaceY = wy
                    )
                }
            }
        )
    }

    // Where a large `item` goes, and the layout with its neighbours moved
    // aside for it if they had to be.
    private fun largePlacement(
        current: LauncherModel,
        item: LauncherItem,
        cols: Int,
        rows: Int
    ): Triple<LauncherModel, Int, Int>? {
        val span = LARGE_ICON_SPAN
        if (cols < span || rows < span) return null
        val exclude = setOf(item.id)
        val cellX = item.cellX.coerceIn(0, cols - 1)
        val cellY = item.cellY.coerceIn(0, rows - 1)
        // The 2x2 squares holding the icon's cell, the one it heads first.
        val origins = listOf(0 to 0, -1 to 0, 0 to -1, -1 to -1)
            .map { (dx, dy) -> (cellX + dx).coerceIn(0, cols - span) to (cellY + dy).coerceIn(0, rows - span) }
            .distinct()
        fun square(origin: Pair<Int, Int>) = WorkspaceGeometry.cellRect(origin.first, origin.second, span, span)
        val free = origins.firstOrNull {
            WorkspaceGeometry.isAreaFree(current, item.page, square(it), cols, rows, exclude)
        }
        val pushed = if (free != null) {
            null
        } else {
            WorkspaceGeometry.solveReorder(current, item.page, square(origins.first()), cols, rows, exclude)
                ?.let { moves -> LauncherRepository.applyDisplacement(current, moves, item.id, cols, rows) }
        }
        val nearest = if (free != null || pushed != null) {
            null
        } else {
            val center = WorkspaceGeometry.itemRect(item, cols, rows)
            WorkspaceGeometry.nearestFreeCell(
                current, item.page, (center.left + center.right) / 2f, (center.top + center.bottom) / 2f,
                span, span, cols, rows, exclude
            )
        }
        return when {
            free != null -> Triple(current, free.first, free.second)
            pushed != null -> Triple(pushed, origins.first().first, origins.first().second)
            nearest != null -> Triple(current, nearest.first, nearest.second)
            else -> null
        }
    }
}
