package com.supernova.launcher.data

import kotlin.math.ceil

// Collision model for the workspace, in cell units: widgets occupy their exact
// cell rectangle, free-positioned icons a one-cell square centred on their
// float position. Two items collide when their slightly shrunken rectangles
// intersect - the shrink lets snapped neighbours share an edge exactly.
object WorkspaceGeometry {

    private const val EPSILON = 0.08f

    // widgetOverlap pref, pushed from the ViewModel's prefs collector. When
    // set, space occupied by a widget counts as free, so items can be placed
    // on top of widgets; grid bounds still apply.
    @Volatile
    var allowWidgetOverlap: Boolean = false

    // overlapWhenPlacing pref, pushed the same way. When set, explicit drops
    // may land on occupied cells (resolvePlacement keeps the requested spot
    // and solveReorder stops pushing neighbours); the free-cell searches that
    // auto-place new apps and pins stay strict.
    @Volatile
    var allowPlacementOverlap: Boolean = false

    data class CellRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun cellRect(cellX: Int, cellY: Int, spanX: Int, spanY: Int) = CellRect(
        cellX.toFloat(),
        cellY.toFloat(),
        (cellX + spanX).toFloat(),
        (cellY + spanY).toFloat()
    )

    fun itemRect(item: LauncherItem, cols: Int, rows: Int): CellRect {
        return if (item.itemType == ItemType.WIDGET || item.workspaceX == null || item.workspaceY == null) {
            cellRect(item.cellX, item.cellY, item.spanX, item.spanY)
        } else {
            val centerX = item.workspaceX * cols
            val centerY = item.workspaceY * rows
            CellRect(centerX - 0.5f, centerY - 0.5f, centerX + 0.5f, centerY + 0.5f)
        }
    }

    private fun overlaps(a: CellRect, b: CellRect): Boolean =
        a.left + EPSILON < b.right - EPSILON &&
            b.left + EPSILON < a.right - EPSILON &&
            a.top + EPSILON < b.bottom - EPSILON &&
            b.top + EPSILON < a.bottom - EPSILON

    // The items on a page lying over an area.
    fun idsOverlapping(model: LauncherModel, page: Int, area: CellRect, cols: Int, rows: Int): Set<String> =
        model.items.filter { item ->
            item.container == ContainerType.WORKSPACE && item.page == page && overlaps(area, itemRect(item, cols, rows))
        }.mapTo(mutableSetOf()) { it.id }

    fun isAreaFree(
        model: LauncherModel,
        page: Int,
        area: CellRect,
        cols: Int,
        rows: Int,
        excludeIds: Set<String> = emptySet(),
        // Bounds-only check: occupied cells count as free. Placement paths
        // pass allowPlacementOverlap here so drops may land on other items.
        ignoreItems: Boolean = false
    ): Boolean {
        if (area.left < -EPSILON || area.top < -EPSILON ||
            area.right > cols + EPSILON || area.bottom > rows + EPSILON
        ) {
            return false
        }
        if (ignoreItems) return true
        return model.items.none { item ->
            item.container == ContainerType.WORKSPACE && item.page == page &&
                item.id !in excludeIds &&
                !(allowWidgetOverlap && item.itemType == ItemType.WIDGET) &&
                overlaps(area, itemRect(item, cols, rows))
        }
    }

    // First cell, row-major, where a spanX x spanY item fits.
    fun findFreeCell(
        model: LauncherModel,
        page: Int,
        spanX: Int,
        spanY: Int,
        cols: Int,
        rows: Int,
        excludeIds: Set<String> = emptySet()
    ): Pair<Int, Int>? {
        for (row in 0..(rows - spanY)) {
            for (col in 0..(cols - spanX)) {
                if (isAreaFree(model, page, cellRect(col, row, spanX, spanY), cols, rows, excludeIds)) {
                    return col to row
                }
            }
        }
        return null
    }

    // Free cell whose centre lies closest to a point (in cell units).
    fun nearestFreeCell(
        model: LauncherModel,
        page: Int,
        nearCellX: Float,
        nearCellY: Float,
        spanX: Int,
        spanY: Int,
        cols: Int,
        rows: Int,
        excludeIds: Set<String> = emptySet()
    ): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestDistance = Float.MAX_VALUE
        for (row in 0..(rows - spanY)) {
            for (col in 0..(cols - spanX)) {
                if (!isAreaFree(model, page, cellRect(col, row, spanX, spanY), cols, rows, excludeIds)) continue
                val dx = (col + spanX / 2f) - nearCellX
                val dy = (row + spanY / 2f) - nearCellY
                val distance = dx * dx + dy * dy
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = col to row
                }
            }
        }
        return best
    }

    // Stacking on a page is list order: a later item draws over an earlier
    // one and takes the touch where they overlap. Whether anything overlaps
    // `item` from below (earlier) and from above (later), which is what a
    // widget's Bring to front and Send to back would change.
    data class Stacking(val below: Boolean, val above: Boolean)

    fun stacking(model: LauncherModel, item: LauncherItem, cols: Int, rows: Int): Stacking {
        val rect = itemRect(item, cols, rows)
        var below = false
        var above = false
        var passed = false
        for (other in model.items) {
            if (other.id == item.id) {
                passed = true
                continue
            }
            if (other.container != ContainerType.WORKSPACE || other.page != item.page) continue
            if (!overlaps(rect, itemRect(other, cols, rows))) continue
            if (passed) above = true else below = true
        }
        return Stacking(below, above)
    }

    // The items with `itemId` moved to the top of the stack (the end of the
    // list) or the bottom (the start). The rest keep their order, and other
    // pages are untouched: each page reads only its own items.
    fun restacked(items: List<LauncherItem>, itemId: String, toFront: Boolean): List<LauncherItem> {
        val item = items.firstOrNull { it.id == itemId } ?: return items
        val rest = items.filterNot { it.id == itemId }
        return if (toFront) rest + item else listOf(item) + rest
    }

    data class ResizeRoom(val left: Int, val top: Int, val right: Int, val bottom: Int)

    // How many free whole cells lie beyond each edge of a widget before
    // another item or the grid edge. Scans one strip at a time per direction.
    fun resizeRoom(model: LauncherModel, item: LauncherItem, cols: Int, rows: Int): ResizeRoom {
        val exclude = setOf(item.id)
        var left = 0
        while (item.cellX - left > 0 &&
            isAreaFree(model, item.page, cellRect(item.cellX - left - 1, item.cellY, 1, item.spanY), cols, rows, exclude)
        ) {
            left++
        }
        var right = 0
        while (item.cellX + item.spanX + right < cols &&
            isAreaFree(model, item.page, cellRect(item.cellX + item.spanX + right, item.cellY, 1, item.spanY), cols, rows, exclude)
        ) {
            right++
        }
        var top = 0
        while (item.cellY - top > 0 &&
            isAreaFree(model, item.page, cellRect(item.cellX, item.cellY - top - 1, item.spanX, 1), cols, rows, exclude)
        ) {
            top++
        }
        var bottom = 0
        while (item.cellY + item.spanY + bottom < rows &&
            isAreaFree(model, item.page, cellRect(item.cellX, item.cellY + item.spanY + bottom, item.spanX, 1), cols, rows, exclude)
        ) {
            bottom++
        }
        return ResizeRoom(left, top, right, bottom)
    }

    fun cellCenteredPosition(cellX: Int, cellY: Int, cols: Int, rows: Int): Pair<Float, Float> =
        ((cellX + 0.5f) / cols) to ((cellY + 0.5f) / rows)

    // --- Reorder: making room under a dragged item (Launcher3-style push).

    // snapToCell marks a relocation rather than a push: the item lands
    // cell-centred instead of keeping its fractional subgrid offset.
    data class ReorderMove(
        val itemId: String,
        val dCellX: Int,
        val dCellY: Int,
        val snapToCell: Boolean = false
    )

    // Makes room for a drop occupying `target` (widgets: their cell rect;
    // icons: a one-cell square centred on the drop position): returns the
    // moves that clear the area (empty when it is already free), or null when
    // no arrangement exists. Overlapped items are pushed away from the target in
    // cascading whole-cell steps - the push direction that most agrees with
    // where the blockers already sit is tried first - and blockers that cannot
    // be pushed are relocated to their nearest free cells instead.
    fun solveReorder(
        model: LauncherModel,
        page: Int,
        target: CellRect,
        cols: Int,
        rows: Int,
        excludeIds: Set<String>
    ): List<ReorderMove>? {
        if (target.left < -EPSILON || target.top < -EPSILON ||
            target.right > cols + EPSILON || target.bottom > rows + EPSILON
        ) {
            return null
        }
        // Overlap-when-placing: the drop lands over whatever is there, so
        // nothing needs to move.
        if (allowPlacementOverlap) return emptyList()
        val statics = model.items.filter {
            it.container == ContainerType.WORKSPACE && it.page == page &&
                it.id !in excludeIds &&
                !(allowWidgetOverlap && it.itemType == ItemType.WIDGET)
        }
        val rects = statics.associate { it.id to itemRect(it, cols, rows) }
        val blockers = statics.filter { overlaps(rects.getValue(it.id), target) }
        if (blockers.isEmpty()) return emptyList()

        for (dir in pushDirections(target, blockers, rects)) {
            attemptPush(statics, blockers, rects, target, dir, cols, rows)?.let { return it }
        }
        return relocateBlockers(statics, blockers, rects, target, cols, rows)
    }

    // The four cardinal directions, best-aligned with the blockers first: a
    // blocker sitting right of the target centre votes for pushing right.
    private fun pushDirections(
        target: CellRect,
        blockers: List<LauncherItem>,
        rects: Map<String, CellRect>
    ): List<Pair<Int, Int>> {
        val tx = (target.left + target.right) / 2f
        val ty = (target.top + target.bottom) / 2f
        return listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).sortedByDescending { (dx, dy) ->
            blockers.sumOf { b ->
                val r = rects.getValue(b.id)
                (dx * ((r.left + r.right) / 2f - tx) + dy * ((r.top + r.bottom) / 2f - ty)).toDouble()
            }
        }
    }

    private fun offsetRect(r: CellRect, dir: Pair<Int, Int>, cells: Int) = CellRect(
        r.left + dir.first * cells,
        r.top + dir.second * cells,
        r.right + dir.first * cells,
        r.bottom + dir.second * cells
    )

    // Whole cells `r` must travel along `dir` until it clears `obstacle`.
    private fun requiredShift(r: CellRect, obstacle: CellRect, dir: Pair<Int, Int>): Int {
        val distance = when {
            dir.first > 0 -> obstacle.right - r.left
            dir.first < 0 -> r.right - obstacle.left
            dir.second > 0 -> obstacle.bottom - r.top
            else -> r.bottom - obstacle.top
        }
        return ceil(distance - EPSILON * 2).toInt().coerceAtLeast(1)
    }

    private fun attemptPush(
        statics: List<LauncherItem>,
        blockers: List<LauncherItem>,
        rects: Map<String, CellRect>,
        target: CellRect,
        dir: Pair<Int, Int>,
        cols: Int,
        rows: Int
    ): List<ReorderMove>? {
        // Cells each item travels along dir; only ever grows, so the loop
        // terminates: an ever-growing shift walks off the grid and fails.
        val shifts = mutableMapOf<String, Int>()
        val queue = ArrayDeque<Pair<String, Int>>()
        blockers.forEach { queue += it.id to requiredShift(rects.getValue(it.id), target, dir) }

        while (queue.isNotEmpty()) {
            val (id, needed) = queue.removeFirst()
            if ((shifts[id] ?: 0) >= needed) continue
            shifts[id] = needed
            val moved = offsetRect(rects.getValue(id), dir, needed)
            if (moved.left < -EPSILON || moved.top < -EPSILON ||
                moved.right > cols + EPSILON || moved.bottom > rows + EPSILON
            ) {
                return null
            }
            for (other in statics) {
                if (other.id == id) continue
                val otherRect = offsetRect(rects.getValue(other.id), dir, shifts[other.id] ?: 0)
                if (overlaps(moved, otherRect)) {
                    queue += other.id to (shifts[other.id] ?: 0) + requiredShift(otherRect, moved, dir)
                }
            }
        }

        // The queue rechecks pairs as shifts grow, but verify the final
        // layout wholesale - cheap, and it keeps the solver trustworthy.
        val finalRects = statics.map { it.id to offsetRect(rects.getValue(it.id), dir, shifts[it.id] ?: 0) }
        for (i in finalRects.indices) {
            if (overlaps(finalRects[i].second, target)) return null
            for (j in i + 1 until finalRects.size) {
                if (overlaps(finalRects[i].second, finalRects[j].second)) return null
            }
        }
        return shifts.filterValues { it > 0 }
            .map { (id, s) -> ReorderMove(id, dir.first * s, dir.second * s) }
    }

    // Push failed in every direction: park each blocker on its nearest free
    // cell instead (the layout equivalent of Launcher3's reorder fallback).
    private fun relocateBlockers(
        statics: List<LauncherItem>,
        blockers: List<LauncherItem>,
        rects: Map<String, CellRect>,
        target: CellRect,
        cols: Int,
        rows: Int
    ): List<ReorderMove>? {
        val occupied = mutableListOf(target)
        statics.filter { s -> blockers.none { it.id == s.id } }.forEach { occupied += rects.getValue(it.id) }
        val moves = mutableListOf<ReorderMove>()
        for (b in blockers) {
            val from = rects.getValue(b.id)
            val cx = (from.left + from.right) / 2f
            val cy = (from.top + from.bottom) / 2f
            var best: Pair<Int, Int>? = null
            var bestDistance = Float.MAX_VALUE
            for (row in 0..(rows - b.spanY)) {
                for (col in 0..(cols - b.spanX)) {
                    val r = cellRect(col, row, b.spanX, b.spanY)
                    if (occupied.any { overlaps(it, r) }) continue
                    val dx = (col + b.spanX / 2f) - cx
                    val dy = (row + b.spanY / 2f) - cy
                    val distance = dx * dx + dy * dy
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = col to row
                    }
                }
            }
            val cell = best ?: return null
            occupied += cellRect(cell.first, cell.second, b.spanX, b.spanY)
            moves += ReorderMove(b.id, cell.first - b.cellX, cell.second - b.cellY, snapToCell = true)
        }
        return moves
    }
}
