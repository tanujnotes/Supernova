package com.supernova.launcher.ui

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.Toast
import com.supernova.launcher.R
import com.supernova.launcher.data.ContainerType
import com.supernova.launcher.data.ItemType
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.data.LauncherItem
import com.supernova.launcher.data.LauncherModel
import com.supernova.launcher.data.WorkspaceGeometry
import kotlin.math.abs
import kotlin.math.roundToInt

// A drawer shortcut as the item it becomes on the home screen: the app it
// belongs to, and its own icon to keep with the item.
class ShortcutItem(val owner: LaunchableApp, val iconBase64: String?)

// What a drag does to a page of the home screen: the preview while it
// hovers (the icons in the way sliding aside, a folder target left still),
// and the drop itself - a move, a new icon or widget, or a merge into a
// folder. The drag engine (LiveDragController) finds the page and the cell
// and asks here what happens there.
class WorkspaceDrops(
    private val context: Context,
    private val viewModel: LauncherViewModel,
    private val host: Host
) : LiveDragController.DropTarget {

    interface Host {
        fun forEachWorkspaceIcon(action: (Int, View) -> Unit)

        // Pinned to this launcher on the way.
        fun shortcutItem(shortcut: DragPayload.DrawerShortcut): ShortcutItem

        fun bindWidget(info: AppWidgetProviderInfo, target: WidgetController.Target)

        // Runs a shortcut row's flow, its result bound for this cell.
        fun placeShortcut(target: ShortcutTarget, cell: ShortcutPlacer.DropCell)
    }

    // Reorder preview ("make space"): while a drag hovers over an
    // occupied spot, the blocking items animate aside after a short pause to
    // show the layout a drop would produce, and animate back if the drag
    // moves on. The solved arrangement itself is recomputed at drop time.
    private val reorderHandler = Handler(Looper.getMainLooper())
    private var pendingReorder: Runnable? = null
    private var reorderHoverKey: ReorderHoverKey? = null
    private var reorderCanvas: ViewGroup? = null
    private var reorderCellW = 1
    private var reorderCellH = 1

    // Material fast-out-slow-in: preview slides accelerate away and settle
    // gently instead of moving linearly.
    private val reorderInterpolator = PathInterpolator(EASE_OUT_X, 0f, EASE_IN_X, 1f)

    private data class ReorderHoverKey(
        val canvas: ViewGroup,
        val page: Int,
        val targetLeft: Float,
        val targetTop: Float,
        val folder: Boolean
    )

    // Commits a drop; false means nothing was written (the picture flies
    // back instead of settling).
    override fun onDropToWorkspace(
        payload: DragPayload,
        page: Int,
        cellX: Int,
        cellY: Int,
        workspaceX: Float,
        workspaceY: Float,
        rawCellX: Float,
        rawCellY: Float
    ): Boolean {
        cancelReorderPreview(animate = false)
        if (viewModel.state.value.prefs.desktopLocked) return false
        val model = viewModel.state.value.model
        val prefs = viewModel.state.value.prefs
        when (payload) {
            is DragPayload.ExistingItem -> {
                val movingItem = model.items.firstOrNull { it.id == payload.itemId } ?: return false
                val widget = movingItem.itemType == ItemType.WIDGET
                if (folderHover(payload, page, rawCellX, rawCellY)) {
                    // Centre-of-icon drop: the classic merge path, with the
                    // icon the hover showed, not whatever is stored at the
                    // drop cell.
                    val mergeTarget = folderTargetId(page, rawCellX, rawCellY, movingItem.id)
                    // A folder's app dropped back on its folder stays where
                    // it was, and flies back in.
                    if (mergeTarget != null && mergeTarget == movingItem.folderId) return false
                    viewModel.moveItemToWorkspace(
                        payload.itemId, page, cellX, cellY, workspaceX, workspaceY, mergeTargetId = mergeTarget
                    )
                    pulseFolderTarget(page, rawCellX, rawCellY, movingItem.id)
                    return true
                }
                val target = if (widget) {
                    widgetDropRect(movingItem, cellX, cellY, rawCellX, rawCellY)
                } else {
                    iconRectAt(workspaceX, workspaceY, prefs.gridCols, prefs.gridRows)
                }
                val (spanX, spanY) = if (widget) WorkspaceGeometry.wholeSpans(target) else 1 to 1
                val moves = WorkspaceGeometry.solveReorder(
                    model, page, target, prefs.gridCols, prefs.gridRows, setOf(movingItem.id)
                )
                if (moves == null && !pageHasRoom(model, page, target, spanX, spanY, setOf(movingItem.id))) {
                    Toast.makeText(context, R.string.no_room_on_page, Toast.LENGTH_SHORT).show()
                    return false
                }
                if (widget) {
                    viewModel.moveWidget(payload.itemId, page, target, moves.orEmpty())
                } else {
                    viewModel.moveItemToWorkspace(
                        payload.itemId, page, cellX, cellY, workspaceX, workspaceY,
                        moves.orEmpty(), mergeFolders = false
                    )
                }
                return true
            }
            is DragPayload.DrawerApp, is DragPayload.DrawerShortcut, is DragPayload.DrawerFolder -> {
                if (payload is DragPayload.DrawerFolder && payload.apps.isEmpty()) return false
                // Only an app or a shortcut merges; folderHover is false for
                // a folder.
                if (folderHover(payload, page, rawCellX, rawCellY)) {
                    val mergeTargetId = folderTargetId(page, rawCellX, rawCellY, excludeId = null)
                    when (payload) {
                        is DragPayload.DrawerApp -> viewModel.addAppToWorkspace(
                            payload.app, page, cellX, cellY, workspaceX, workspaceY, mergeTargetId = mergeTargetId
                        )
                        is DragPayload.DrawerShortcut -> {
                            val item = host.shortcutItem(payload)
                            viewModel.addShortcutToWorkspace(
                                item.owner, payload.shortcutId, payload.label, page, cellX, cellY,
                                workspaceX, workspaceY, item.iconBase64, mergeTargetId = mergeTargetId
                            )
                        }
                        else -> return false
                    }
                    pulseFolderTarget(page, rawCellX, rawCellY, excludeId = null)
                    return true
                }
                val target = iconRectAt(workspaceX, workspaceY, prefs.gridCols, prefs.gridRows)
                val moves = WorkspaceGeometry.solveReorder(
                    model, page, target, prefs.gridCols, prefs.gridRows, emptySet()
                )
                if (moves == null && !pageHasRoom(model, page, target, 1, 1, emptySet())) {
                    Toast.makeText(context, R.string.no_room_on_page, Toast.LENGTH_SHORT).show()
                    return false
                }
                val displace = moves.orEmpty()
                when (payload) {
                    is DragPayload.DrawerApp -> viewModel.addAppToWorkspace(
                        payload.app, page, cellX, cellY, workspaceX, workspaceY, displace, mergeFolders = false
                    )
                    is DragPayload.DrawerShortcut -> {
                        val item = host.shortcutItem(payload)
                        viewModel.addShortcutToWorkspace(
                            item.owner, payload.shortcutId, payload.label, page, cellX, cellY,
                            workspaceX, workspaceY, item.iconBase64, displace, mergeFolders = false
                        )
                    }
                    is DragPayload.DrawerFolder -> viewModel.addFolderToWorkspace(
                        payload.name, payload.apps, page, cellX, cellY, workspaceX, workspaceY, displace
                    )
                    else -> Unit
                }
                return true
            }
            is DragPayload.NewWidget -> {
                val anchorX = clampAnchor(cellX, payload.spanX, prefs.gridCols)
                val anchorY = clampAnchor(cellY, payload.spanY, prefs.gridRows)
                val target = WorkspaceGeometry.cellRect(anchorX, anchorY, payload.spanX, payload.spanY)
                // No arrangement makes room: the drop is refused before any
                // widget id is allocated, so cancelling costs nothing.
                val moves = WorkspaceGeometry.solveReorder(
                    model, page, target, prefs.gridCols, prefs.gridRows, emptySet()
                ) ?: run {
                    Toast.makeText(context, R.string.no_room_on_page, Toast.LENGTH_SHORT).show()
                    return false
                }
                host.bindWidget(payload.info, WidgetController.Target.Workspace(page, anchorX, anchorY, moves))
                return true
            }
            is DragPayload.NewShortcut -> {
                // As a widget from the sheet: the neighbours in the way move
                // aside once the row's result lands, nothing is added if its
                // flow is cancelled, and with no room the flow never starts.
                val target = iconRectAt(workspaceX, workspaceY, prefs.gridCols, prefs.gridRows)
                val moves = WorkspaceGeometry.solveReorder(
                    model, page, target, prefs.gridCols, prefs.gridRows, emptySet()
                ) ?: run {
                    Toast.makeText(context, R.string.no_room_on_page, Toast.LENGTH_SHORT).show()
                    return false
                }
                host.placeShortcut(
                    payload.target, ShortcutPlacer.DropCell(page, cellX, cellY, workspaceX, workspaceY, moves)
                )
                return true
            }
        }
    }

    // Where a placed widget dropped here lands, in cells, keeping its size:
    // its top-left on the hovered cell, or with subgrid positioning on the
    // nearest half cell. rawCellX is the drop point, half a cell in from the
    // picture's top-left.
    private fun widgetDropRect(
        item: LauncherItem,
        cellX: Int,
        cellY: Int,
        rawCellX: Float,
        rawCellY: Float
    ): WorkspaceGeometry.CellRect {
        val prefs = viewModel.state.value.prefs
        val rect = WorkspaceGeometry.itemRect(item, prefs.gridCols, prefs.gridRows)
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        fun anchor(cell: Int, raw: Float, size: Float, count: Int): Float {
            val most = (count - size).coerceAtLeast(0f)
            val start = if (prefs.subgrid) ((raw - HALF_CELL) * 2).roundToInt() / 2f else cell.toFloat()
            return start.coerceIn(0f, most)
        }
        val left = anchor(cellX, rawCellX, width, prefs.gridCols)
        val top = anchor(cellY, rawCellY, height, prefs.gridRows)
        return WorkspaceGeometry.CellRect(left, top, left + width, top + height)
    }

    // Whether a drop that can't push its neighbours aside still has a free
    // spot on the page: the repository then places it at the nearest one,
    // and refuses it only when there is none. Refused, the picture used to
    // settle into the occupied cell and vanish - a drawer app never added,
    // a moved icon popping back to where it was.
    private fun pageHasRoom(
        model: LauncherModel,
        page: Int,
        target: WorkspaceGeometry.CellRect,
        spanX: Int,
        spanY: Int,
        excludeIds: Set<String>
    ): Boolean {
        if (WorkspaceGeometry.allowPlacementOverlap) return true
        val prefs = viewModel.state.value.prefs
        return WorkspaceGeometry.nearestFreeCell(
            model, page, (target.left + target.right) / 2f, (target.top + target.bottom) / 2f,
            spanX, spanY, prefs.gridCols, prefs.gridRows, excludeIds
        ) != null
    }

    // Folder merge feedback: the icon the drop landed on swells for a beat.
    private fun pulseFolderTarget(page: Int, rawCellX: Float, rawCellY: Float, excludeId: String?) {
        val prefs = viewModel.state.value.prefs
        if (!Animations.enabled(prefs)) return
        val targetId = folderTargetId(page, rawCellX, rawCellY, excludeId) ?: return
        host.forEachWorkspaceIcon { _, view ->
            if (view.getTag(R.id.tag_item_id) != targetId) return@forEachWorkspaceIcon
            val duration = Animations.duration(prefs, FOLDER_PULSE_MS)
            view.animate().scaleX(FOLDER_PULSE_SCALE).scaleY(FOLDER_PULSE_SCALE).setDuration(duration)
                .withEndAction {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(duration).start()
                }
                .start()
        }
    }

    // The one-cell square an icon occupies when dropped at a normalized
    // position - the same rect WorkspaceGeometry.itemRect derives for it.
    private fun iconRectAt(workspaceX: Float, workspaceY: Float, cols: Int, rows: Int): WorkspaceGeometry.CellRect {
        val cx = workspaceX * cols
        val cy = workspaceY * rows
        return WorkspaceGeometry.CellRect(cx - HALF_CELL, cy - HALF_CELL, cx + HALF_CELL, cy + HALF_CELL)
    }

    // The centre-zone rule: hovering the middle of a folder-eligible item
    // still means "merge into a folder"; anywhere else means "make space".
    private fun folderHover(payload: DragPayload, page: Int, rawCellX: Float, rawCellY: Float): Boolean {
        val excludeId: String?
        when (payload) {
            is DragPayload.DrawerApp, is DragPayload.DrawerShortcut -> excludeId = null
            // A folder goes into no folder, and makes none with an icon.
            is DragPayload.DrawerFolder -> return false
            is DragPayload.ExistingItem -> {
                val item = viewModel.state.value.model.items.firstOrNull { it.id == payload.itemId } ?: return false
                if (item.itemType != ItemType.APP && item.itemType != ItemType.SHORTCUT) return false
                excludeId = item.id
            }
            // Nor does a sheet's widget or shortcut row, still to be made.
            is DragPayload.NewWidget, is DragPayload.NewShortcut -> return false
        }
        return folderTargetId(page, rawCellX, rawCellY, excludeId) != null
    }

    // The folder-eligible item whose centre zone the hover is in, if any.
    private fun folderTargetId(page: Int, rawCellX: Float, rawCellY: Float, excludeId: String?): String? {
        val prefs = viewModel.state.value.prefs
        return viewModel.state.value.model.items.firstOrNull { other ->
            other.container == ContainerType.WORKSPACE && other.page == page && other.id != excludeId &&
                other.itemType in FOLDER_TARGETS &&
                run {
                    val r = WorkspaceGeometry.itemRect(other, prefs.gridCols, prefs.gridRows)
                    abs((r.left + r.right) / 2f - rawCellX) <= FOLDER_CENTER_ZONE &&
                        abs((r.top + r.bottom) / 2f - rawCellY) <= FOLDER_CENTER_ZONE
                }
        }?.id
    }

    // The rect a drop at this hover position would occupy, plus the dragged
    // item to exclude from collision - null when the payload can't reorder.
    override fun hoverTargetRect(
        payload: DragPayload,
        cellX: Int,
        cellY: Int,
        rawCellX: Float,
        rawCellY: Float
    ): Pair<WorkspaceGeometry.CellRect, Set<String>>? {
        val prefs = viewModel.state.value.prefs
        val cols = prefs.gridCols
        val rows = prefs.gridRows
        fun snappedIconRect(): WorkspaceGeometry.CellRect {
            // Mirrors the adapter's placement(): subgrid snaps to half-cell
            // centres, otherwise the drop centres on the hovered cell.
            fun snapped(raw: Float, cell: Int, count: Int): Float =
                if (prefs.subgrid) snapToHalfCell((raw / count).coerceIn(0f, 1f), count) else (cell + HALF_CELL) / count
            val wx = snapped(rawCellX, cellX, cols)
            val wy = snapped(rawCellY, cellY, rows)
            return iconRectAt(wx, wy, cols, rows)
        }
        fun widgetRect(spanX: Int, spanY: Int) = WorkspaceGeometry.cellRect(
            clampAnchor(cellX, spanX, cols), clampAnchor(cellY, spanY, rows), spanX, spanY
        )
        return when (payload) {
            is DragPayload.DrawerApp, is DragPayload.DrawerShortcut, is DragPayload.DrawerFolder,
            is DragPayload.NewShortcut -> snappedIconRect() to emptySet()
            is DragPayload.ExistingItem -> {
                val item = viewModel.state.value.model.items.firstOrNull { it.id == payload.itemId } ?: return null
                if (item.itemType == ItemType.WIDGET) {
                    widgetDropRect(item, cellX, cellY, rawCellX, rawCellY) to setOf(item.id)
                } else {
                    snappedIconRect() to setOf(item.id)
                }
            }
            is DragPayload.NewWidget -> widgetRect(payload.spanX, payload.spanY) to emptySet()
        }
    }

    override fun onDragOverWorkspace(
        payload: DragPayload,
        page: Int,
        canvas: ViewGroup,
        cellWidth: Int,
        cellHeight: Int,
        cellX: Int,
        cellY: Int,
        rawCellX: Float,
        rawCellY: Float
    ) {
        if (viewModel.state.value.prefs.desktopLocked) return
        val (target, exclude) = hoverTargetRect(payload, cellX, cellY, rawCellX, rawCellY) ?: return
        val folder = folderHover(payload, page, rawCellX, rawCellY)
        val key = ReorderHoverKey(canvas, page, target.left, target.top, folder)
        if (key == reorderHoverKey) return
        pendingReorder?.let(reorderHandler::removeCallbacks)
        pendingReorder = null
        if (reorderCanvas !== canvas) previewReorder(emptyMap())
        reorderHoverKey = key
        reorderCanvas = canvas
        reorderCellW = cellWidth
        reorderCellH = cellHeight
        if (folder) {
            previewReorder(emptyMap())
            return
        }
        val run = Runnable {
            val prefs = viewModel.state.value.prefs
            val moves = WorkspaceGeometry.solveReorder(
                viewModel.state.value.model, page, target, prefs.gridCols, prefs.gridRows, exclude
            )
            previewReorder(moves.orEmpty().associate { it.itemId to (it.dCellX to it.dCellY) })
        }
        pendingReorder = run
        reorderHandler.postDelayed(run, REORDER_HOVER_DELAY_MS)
    }

    override fun cancelReorderPreview(animate: Boolean) {
        pendingReorder?.let(reorderHandler::removeCallbacks)
        pendingReorder = null
        reorderHoverKey = null
        previewReorder(emptyMap(), animate)
        reorderCanvas = null
    }

    // Shifts the hovered page's children by cell deltas (empty map reverts).
    // Only the translation moves; the model stays untouched until the drop.
    // Deltas are applied RELATIVE to each child's resting translation, the
    // base captured the first time the preview moves it. The canvas lays
    // icons out on their point and widgets on their cells, so that rest is
    // zero after a bind - but the base, not zero, is what a revert returns to.
    private fun previewReorder(offsets: Map<String, Pair<Int, Int>>, animate: Boolean = true) {
        val canvas = reorderCanvas ?: return
        val useAnim = animate && Animations.enabled(viewModel.state.value.prefs)
        for (i in 0 until canvas.childCount) {
            val child = canvas.getChildAt(i)
            val id = child.getTag(R.id.tag_item_id) as? String ?: continue
            val delta = offsets[id]
            val storedBase = child.getTag(R.id.tag_preview_base) as? PointF
            // Never touched by the preview and not shifting now: leave it
            // alone rather than trusting translation zero to be its home.
            if (delta == null && storedBase == null) continue
            val base = storedBase
                ?: PointF(child.translationX, child.translationY).also { child.setTag(R.id.tag_preview_base, it) }
            val tx = base.x + (delta?.first ?: 0) * reorderCellW.toFloat()
            val ty = base.y + (delta?.second ?: 0) * reorderCellH.toFloat()
            if (child.translationX == tx && child.translationY == ty) continue
            if (useAnim) {
                child.animate().translationX(tx).translationY(ty)
                    .setDuration(Animations.duration(viewModel.state.value.prefs, REORDER_ANIM_MS))
                    .setInterpolator(reorderInterpolator)
                    .start()
            } else {
                child.animate().cancel()
                child.translationX = tx
                child.translationY = ty
            }
        }
    }

    private companion object {
        // Reorder: how long a drag must hover one spot before items move
        // aside, how fast they slide, and how large the folder-merge centre
        // zone of an icon is (in cells, each side of the centre).
        const val REORDER_HOVER_DELAY_MS = 250L
        const val REORDER_ANIM_MS = 180L
        const val FOLDER_CENTER_ZONE = 0.35f
        const val FOLDER_PULSE_SCALE = 1.18f
        const val FOLDER_PULSE_MS = 90L

        // An icon's point is its centre, half a cell in from its cell's edge.
        const val HALF_CELL = 0.5f

        // Material fast-out-slow-in, as control points.
        const val EASE_OUT_X = 0.4f
        const val EASE_IN_X = 0.2f

        // What an icon dropped on its middle joins, or makes a folder with.
        val FOLDER_TARGETS = setOf(ItemType.APP, ItemType.SHORTCUT, ItemType.FOLDER)
    }
}
