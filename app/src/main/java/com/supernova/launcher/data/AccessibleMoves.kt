package com.supernova.launcher.data

import kotlin.math.roundToInt

// Where a home screen icon goes when it is moved without a drag: a screen
// reader's or a switch's actions on the icon (ui/ItemMoveActions). Every
// move lands on a free spot or doesn't happen, so none of them can merge two
// icons into a folder or push another aside the way a drop can.
object AccessibleMoves {

    enum class Move { LEFT, RIGHT, UP, DOWN, PREVIOUS_PAGE, NEXT_PAGE, TO_DOCK, TO_HOME }

    sealed interface Target {
        data class Cell(val page: Int, val cellX: Int, val cellY: Int) : Target
        data class Dock(val rank: Int) : Target
    }

    // The grid in use and the dock's slots, all pages of it.
    data class Grid(val cols: Int, val rows: Int, val dockSlots: Int)

    // The moves worth offering for an item, by where it sits: the ones that
    // run off the grid's edge or past the last page are left out. Whether
    // the spot is free is only known when one is asked for (target).
    fun offered(model: LauncherModel, item: LauncherItem, grid: Grid): List<Move> {
        val cell = cellOf(model, item, grid)
        return when {
            item.itemType == ItemType.WIDGET -> emptyList()
            item.container == ContainerType.DOCK -> listOf(Move.TO_HOME)
            item.container == ContainerType.WORKSPACE && cell != null -> listOfNotNull(
                Move.LEFT.takeIf { cell.first > 0 },
                Move.RIGHT.takeIf { cell.first < grid.cols - spanOf(item).first },
                Move.UP.takeIf { cell.second > 0 },
                Move.DOWN.takeIf { cell.second < grid.rows - spanOf(item).second },
                Move.PREVIOUS_PAGE.takeIf { item.page > 0 },
                Move.NEXT_PAGE.takeIf { item.page < pages(model) - 1 },
                Move.TO_DOCK.takeIf { grid.dockSlots > 0 }
            )
            else -> emptyList()
        }
    }

    // Where the move puts the item, or null when there is no free spot for
    // it. `homePage` is the page a dock icon moves out to.
    fun target(model: LauncherModel, item: LauncherItem, move: Move, grid: Grid, homePage: Int): Target? =
        when (move) {
            Move.LEFT -> step(model, item, -1, 0, grid)
            Move.RIGHT -> step(model, item, 1, 0, grid)
            Move.UP -> step(model, item, 0, -1, grid)
            Move.DOWN -> step(model, item, 0, 1, grid)
            Move.PREVIOUS_PAGE -> firstFree(model, item.page - 1, grid, spanOf(item))
            Move.NEXT_PAGE -> firstFree(model, item.page + 1, grid, spanOf(item))
            Move.TO_HOME -> firstFree(model, homePage, grid, 1 to 1)
            Move.TO_DOCK -> {
                val taken = model.items.filter { it.container == ContainerType.DOCK }.map { it.rank }.toSet()
                (0 until grid.dockSlots).firstOrNull { it !in taken }?.let(Target::Dock)
            }
        }

    // A page exists once an item sits on it, whatever the stored count says.
    private fun pages(model: LauncherModel): Int = maxOf(
        model.pageCount,
        model.items.filter { it.container == ContainerType.WORKSPACE }.maxOfOrNull { it.page + 1 } ?: 1
    )

    // The cells a side an icon takes on the desktop: a large one keeps its
    // 2x2 when it moves there.
    private fun spanOf(item: LauncherItem): Pair<Int, Int> =
        if (item.isLargeIcon) item.spanX to item.spanY else 1 to 1

    // The cell a desktop icon is shown in: its own on the grid in use, or
    // the one its free (subgrid) position is nearest to.
    private fun cellOf(model: LauncherModel, item: LauncherItem, grid: Grid): Pair<Int, Int>? {
        if (item.container != ContainerType.WORKSPACE) return null
        val shown = WorkspaceGeometry.fitted(model, grid.cols, grid.rows).items.firstOrNull { it.id == item.id }
        return shown?.let { WorkspaceGeometry.itemRect(it, grid.cols, grid.rows) }?.let { rect ->
            rect.left.roundToInt().coerceIn(0, grid.cols - 1) to rect.top.roundToInt().coerceIn(0, grid.rows - 1)
        }
    }

    private fun step(model: LauncherModel, item: LauncherItem, dx: Int, dy: Int, grid: Grid): Target? {
        val (cellX, cellY) = cellOf(model, item, grid) ?: return null
        val (spanX, spanY) = spanOf(item)
        val area = WorkspaceGeometry.cellRect(cellX + dx, cellY + dy, spanX, spanY)
        val free = WorkspaceGeometry.isAreaFree(
            model, item.page, area, grid.cols, grid.rows, excludeIds = setOf(item.id)
        )
        return if (free) Target.Cell(item.page, cellX + dx, cellY + dy) else null
    }

    private fun firstFree(model: LauncherModel, page: Int, grid: Grid, span: Pair<Int, Int>): Target? {
        if (page !in 0 until pages(model)) return null
        return WorkspaceGeometry.findFreeCell(model, page, span.first, span.second, grid.cols, grid.rows)
            ?.let { (cellX, cellY) -> Target.Cell(page, cellX, cellY) }
    }
}
