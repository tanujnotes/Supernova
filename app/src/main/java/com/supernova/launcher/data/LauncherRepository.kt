package com.supernova.launcher.data

import android.content.Context
import android.content.res.Resources
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.supernova.launcher.R
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class LauncherRepository(private val context: Context) {

    // encodeDefaults keeps schema fields (version, userSerial, ...) present in
    // persisted JSON even when they equal their defaults, so migrations can
    // trust what they read back.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // flowOn keeps the whole-document JSON decode off whichever thread
    // collects; the state combine reads through this. A stored layout this
    // build can't read shows as an empty one, but stays as it is (decode).
    val modelFlow: Flow<LauncherModel> = context.supernovaStore.data.map { prefs ->
        decode(prefs[StoreKeys.model]) ?: LauncherModel(version = CURRENT_MODEL_VERSION)
    }.distinctUntilChanged().flowOn(kotlinx.coroutines.Dispatchers.Default)

    // Null for a stored layout that doesn't decode, one written by a newer
    // build say (an older one installed over it can't read its new types).
    // Nothing is ever written over it (mutate), so installing a build that
    // can read it brings it back; a copy is kept in files/ as well.
    private fun decode(raw: String?): LauncherModel? {
        if (raw == null) return LauncherModel(version = CURRENT_MODEL_VERSION)
        return runCatching { json.decodeFromString<LauncherModel>(raw) }
            .onFailure { failure ->
                Log.w("Supernova", "Launcher model doesn't decode; leaving it untouched", failure)
                runCatching {
                    val copy = File(context.filesDir, "unreadable_model.json")
                    if (!copy.exists() || copy.readText() != raw) copy.writeText(raw)
                }
            }
            .getOrNull()
            ?.let(ModelMigrations::migrate)
    }

    // False while the stored layout can't be decoded: nothing may act on the
    // empty stand-in, like the widget sweep freeing every id it doesn't see.
    suspend fun modelReadable(): Boolean = decode(context.supernovaStore.data.first()[StoreKeys.model]) != null

    suspend fun addAppToWorkspace(
        app: LaunchableApp,
        page: Int,
        cellX: Int,
        cellY: Int,
        cols: Int,
        rows: Int,
        workspaceX: Float? = null,
        workspaceY: Float? = null,
        displace: List<WorkspaceGeometry.ReorderMove> = emptyList(),
        mergeFolders: Boolean = true,
        mergeTargetId: String? = null
    ) {
        val item = LauncherItem(
            id = id(),
            itemType = ItemType.APP,
            container = ContainerType.WORKSPACE,
            page = page,
            cellX = cellX,
            cellY = cellY,
            workspaceX = workspaceX,
            workspaceY = workspaceY,
            label = app.label,
            packageName = app.packageName,
            className = app.className,
            userSerial = app.userSerial
        )
        addNewWorkspaceItem(item, cols, rows, displace, mergeFolders, mergeTargetId)
    }

    // A new install on the first free cell of the first page with one,
    // checked and placed under the lock: every open screen's view model
    // hears the same install, and only the first to get here adds it.
    suspend fun autoAddApp(app: LaunchableApp, cols: Int, rows: Int) {
        mutate { current ->
            val placed = current.items.any {
                it.itemType == ItemType.APP && it.packageName == app.packageName && it.userSerial == app.userSerial
            }
            if (placed) return@mutate current
            for (page in 0 until current.pageCount) {
                val cell = WorkspaceGeometry.findFreeCell(current, page, 1, 1, cols, rows) ?: continue
                val item = LauncherItem(
                    id = id(),
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
                return@mutate current.copy(items = current.items + item)
            }
            current
        }
    }

    suspend fun addShortcutToWorkspace(
        app: LaunchableApp,
        shortcutId: String,
        label: String,
        page: Int,
        cellX: Int,
        cellY: Int,
        cols: Int,
        rows: Int,
        workspaceX: Float? = null,
        workspaceY: Float? = null,
        iconBase64: String? = null,
        displace: List<WorkspaceGeometry.ReorderMove> = emptyList(),
        mergeFolders: Boolean = true,
        mergeTargetId: String? = null
    ) {
        val item = LauncherItem(
            id = id(),
            itemType = ItemType.SHORTCUT,
            container = ContainerType.WORKSPACE,
            page = page,
            cellX = cellX,
            cellY = cellY,
            workspaceX = workspaceX,
            workspaceY = workspaceY,
            label = label,
            packageName = app.packageName,
            className = app.className,
            shortcutId = shortcutId,
            userSerial = app.userSerial,
            iconBase64 = iconBase64
        )
        addNewWorkspaceItem(item, cols, rows, displace, mergeFolders, mergeTargetId)
    }

    // A drawer folder placed on the home screen: a new folder with the
    // group's name, holding its apps in the order given. The copy is the
    // home screen's own; the drawer group stays as it is.
    suspend fun addFolderToWorkspace(
        label: String,
        apps: List<LaunchableApp>,
        page: Int,
        cellX: Int,
        cellY: Int,
        cols: Int,
        rows: Int,
        workspaceX: Float? = null,
        workspaceY: Float? = null,
        displace: List<WorkspaceGeometry.ReorderMove> = emptyList()
    ) {
        if (apps.isEmpty()) return
        val folder = LauncherItem(
            id = id(),
            itemType = ItemType.FOLDER,
            container = ContainerType.WORKSPACE,
            page = page,
            cellX = cellX,
            cellY = cellY,
            workspaceX = workspaceX,
            workspaceY = workspaceY,
            label = label
        )
        val members = folderMembers(folder.id, apps)
        mutate { withNewFolder(it, folder, members, displace, cols, rows) }
    }

    private fun folderMembers(folderId: String, apps: List<LaunchableApp>): List<LauncherItem> =
        apps.mapIndexed { rank, app ->
            LauncherItem(
                id = id(),
                itemType = ItemType.APP,
                container = ContainerType.FOLDER,
                folderId = folderId,
                rank = rank,
                label = app.label,
                packageName = app.packageName,
                className = app.className,
                userSerial = app.userSerial
            )
        }

    // A launcher action, an activity, or a legacy created shortcut: a SHORTCUT
    // item carrying an intentUri (and an optional snapshot icon) rather than an
    // app-shortcut id. launchItem routes on the uri.
    suspend fun addIntentItemToWorkspace(
        intentUri: String,
        label: String,
        iconBase64: String?,
        page: Int,
        cellX: Int,
        cellY: Int,
        cols: Int,
        rows: Int,
        workspaceX: Float? = null,
        workspaceY: Float? = null,
        displace: List<WorkspaceGeometry.ReorderMove> = emptyList()
    ) {
        val item = LauncherItem(
            id = id(),
            itemType = ItemType.SHORTCUT,
            container = ContainerType.WORKSPACE,
            page = page,
            cellX = cellX,
            cellY = cellY,
            workspaceX = workspaceX,
            workspaceY = workspaceY,
            label = label,
            intentUri = intentUri,
            iconBase64 = iconBase64
        )
        addNewWorkspaceItem(item, cols, rows, displace, mergeFolders = false)
    }

    suspend fun addAppToDock(app: LaunchableApp, rank: Int, cols: Int, rows: Int, preferredPage: Int = 0) {
        addNewItemToDock(
            LauncherItem(
                id = id(),
                itemType = ItemType.APP,
                container = ContainerType.DOCK,
                rank = rank,
                label = app.label,
                packageName = app.packageName,
                className = app.className,
                userSerial = app.userSerial
            ),
            emptyList(), rank, cols, rows, preferredPage
        )
    }

    suspend fun addShortcutToDock(
        app: LaunchableApp,
        shortcutId: String,
        label: String,
        iconBase64: String?,
        rank: Int,
        cols: Int,
        rows: Int,
        preferredPage: Int = 0
    ) {
        addNewItemToDock(
            LauncherItem(
                id = id(),
                itemType = ItemType.SHORTCUT,
                container = ContainerType.DOCK,
                rank = rank,
                label = label,
                packageName = app.packageName,
                className = app.className,
                shortcutId = shortcutId,
                userSerial = app.userSerial,
                iconBase64 = iconBase64
            ),
            emptyList(), rank, cols, rows, preferredPage
        )
    }

    suspend fun addFolderToDock(
        label: String,
        apps: List<LaunchableApp>,
        rank: Int,
        cols: Int,
        rows: Int,
        preferredPage: Int = 0
    ) {
        if (apps.isEmpty()) return
        val folder = LauncherItem(id = id(), itemType = ItemType.FOLDER, container = ContainerType.DOCK, rank = rank, label = label)
        addNewItemToDock(folder, folderMembers(folder.id, apps), rank, cols, rows, preferredPage)
    }

    // A new item into a dock slot, with its folder members if it is a folder.
    private suspend fun addNewItemToDock(
        item: LauncherItem,
        members: List<LauncherItem>,
        rank: Int,
        cols: Int,
        rows: Int,
        preferredPage: Int
    ) {
        mutate { withNewDockItem(it, item, members, rank, cols, rows, preferredPage) }
    }

    suspend fun moveItemToWorkspace(
        itemId: String,
        page: Int,
        cellX: Int,
        cellY: Int,
        cols: Int,
        rows: Int,
        workspaceX: Float? = null,
        workspaceY: Float? = null,
        displace: List<WorkspaceGeometry.ReorderMove> = emptyList(),
        mergeFolders: Boolean = true,
        mergeTargetId: String? = null
    ) {
        mutate { original ->
            val current = applyDisplacement(original, displace, itemId, cols, rows) ?: original
            val moving = current.items.firstOrNull { it.id == itemId } ?: return@mutate original
            val target = mergeTarget(current, page, cellX, cellY, itemId, mergeTargetId)

            // Folder semantics win over collision: dropping onto an icon or
            // folder merges as before. Reorder drops (mergeFolders = false)
            // land beside the target instead - the caller already ruled the
            // hover outside the folder centre zone.
            val next = when {
                mergeFolders && target != null && target.itemType == ItemType.FOLDER && isFolderEligible(moving) ->
                    moveIntoFolderModel(current, moving, target.id)
                mergeFolders && target != null && isFolderEligible(moving) && isFolderEligible(target) ->
                    createNewFolderModel(current, moving, target)
                else -> {
                    val moved = moving.copy(
                        container = ContainerType.WORKSPACE,
                        page = page,
                        cellX = cellX,
                        cellY = cellY,
                        workspaceX = workspaceX?.coerceIn(0f, 1f),
                        workspaceY = workspaceY?.coerceIn(0f, 1f),
                        folderId = null,
                        rank = 0
                    )
                    // Occupied destination: relocate to the nearest free
                    // cell, or bounce the drop entirely (with any
                    // displacement undone) when the page is full.
                    val placed = resolvePlacement(current, moved, cols, rows) ?: return@mutate original
                    current.copy(items = current.items.map { if (it.id == placed.id) placed else it })
                }
            }
            // An app taken out of a folder may leave too little of it to
            // stay a folder.
            if (moving.container == ContainerType.FOLDER) withSparseFolderDissolved(next, moving.folderId) else next
        }
    }

    suspend fun moveItemToDock(itemId: String, rank: Int) {
        mutate { current -> movedToDock(current, itemId, rank) }
    }

    // A drop on the middle of a dock icon: into the folder there, or a new
    // folder of the two. An item from the drawer comes in new.
    suspend fun mergeIntoDock(item: LauncherItem, rank: Int) {
        mutate { current ->
            val moving = current.items.firstOrNull { it.id == item.id } ?: item
            DockFolders.merged(current, moving, rank, id())
        }
    }

    suspend fun moveItemIntoFolder(itemId: String, folderId: String) {
        mutate { current ->
            val moving = current.items.firstOrNull { it.id == itemId } ?: return@mutate current
            if (!isFolderEligible(moving)) return@mutate current
            moveIntoFolderModel(current, moving, folderId)
        }
    }

    // Folder checklist additions: a brand-new item created directly inside the
    // folder (a drawer copy), not moved from anywhere.
    suspend fun addAppToFolder(app: LaunchableApp, folderId: String) {
        mutate { current ->
            if (current.items.none { it.id == folderId && it.itemType == ItemType.FOLDER }) {
                return@mutate current
            }
            val nextRank = (current.items.filter { it.folderId == folderId }
                .maxOfOrNull { it.rank } ?: -1) + 1
            current.copy(
                items = current.items + LauncherItem(
                    id = id(),
                    itemType = ItemType.APP,
                    container = ContainerType.FOLDER,
                    folderId = folderId,
                    rank = nextRank,
                    label = app.label,
                    packageName = app.packageName,
                    className = app.className,
                    userSerial = app.userSerial
                )
            )
        }
    }

    suspend fun renameFolder(folderId: String, name: String) {
        mutate { current ->
            current.copy(
                items = current.items.map {
                    if (it.id == folderId && it.itemType == ItemType.FOLDER) it.copy(label = name.trim()) else it
                }
            )
        }
    }

    // A placed shortcut's own label (a pinned shortcut, a launcher action,
    // a picked activity). An app icon's rename is an app override instead.
    suspend fun renameShortcut(itemId: String, label: String) {
        val name = label.trim()
        if (name.isEmpty()) return
        mutate { current ->
            current.copy(
                items = current.items.map {
                    if (it.id == itemId && it.itemType == ItemType.SHORTCUT) it.copy(label = name) else it
                }
            )
        }
    }

    suspend fun removeItem(itemId: String) {
        mutate { current ->
            val removedItem = current.items.firstOrNull { it.id == itemId }
            val removeIds = mutableSetOf(itemId)
            if (removedItem?.itemType == ItemType.FOLDER) {
                current.items.filter { it.folderId == itemId }.forEach { removeIds.add(it.id) }
            }
            current.copy(
                items = current.items.filterNot { it.id in removeIds }
            )
        }
    }

    // Undo for a removal: re-adds the captured items verbatim. The first
    // workspace item goes back to its original cell when free, else the
    // nearest free cell; folder members re-attach by their kept folderId.
    // Items whose ids still exist (double-undo, races) are skipped.
    // dockSlots: the dock's slots over all its pages (count x pages).
    suspend fun restoreItems(items: List<LauncherItem>, cols: Int, rows: Int, dockSlots: Int) {
        mutate { current -> restored(current, items, cols, rows, dockSlots) }
    }

    suspend fun removeFolderMember(memberId: String, page: Int, cellX: Int, cellY: Int) {
        mutate { current ->
            current.copy(
                items = current.items.map {
                    if (it.id == memberId) {
                        it.copy(
                            container = ContainerType.WORKSPACE,
                            page = page,
                            cellX = cellX,
                            cellY = cellY,
                            workspaceX = null,
                            workspaceY = null,
                            folderId = null,
                            rank = 0
                        )
                    } else {
                        it
                    }
                }
            )
        }
    }

    suspend fun reorderFolderMember(folderId: String, memberId: String, direction: Int) {
        mutate { current ->
            val members = current.items.filter { it.folderId == folderId }.sortedBy { it.rank }
            val index = members.indexOfFirst { it.id == memberId }
            if (index == -1) return@mutate current
            val targetIndex = (index + direction).coerceIn(0, members.lastIndex)
            if (targetIndex == index) return@mutate current

            val reordered = members.toMutableList().apply {
                val item = removeAt(index)
                add(targetIndex, item)
            }
            val rankMap = reordered.mapIndexed { idx, item -> item.id to idx }.toMap()
            current.copy(
                items = current.items.map { item ->
                    rankMap[item.id]?.let { rank -> item.copy(rank = rank) } ?: item
                }
            )
        }
    }

    suspend fun addWidget(
        page: Int,
        cellX: Int,
        cellY: Int,
        cols: Int,
        rows: Int,
        appWidgetId: Int,
        provider: String,
        label: String,
        spanX: Int,
        spanY: Int,
        // Neighbours a drag previewed aside; re-validated like every other
        // displacement, and dropped wholesale if the layout moved on.
        displace: List<WorkspaceGeometry.ReorderMove> = emptyList()
    ) {
        val widget = LauncherItem(
            id = id(),
            itemType = ItemType.WIDGET,
            container = ContainerType.WORKSPACE,
            page = page,
            cellX = cellX,
            cellY = cellY,
            spanX = spanX.coerceAtLeast(1),
            spanY = spanY.coerceAtLeast(1),
            appWidgetId = appWidgetId,
            widgetProvider = provider,
            label = label
        )
        mutate { original ->
            val current = applyDisplacement(original, displace, null, cols, rows) ?: original
            // The widget is already bound by now, so if the layout changed
            // while it was being configured, keep it at the requested spot as
            // a last resort rather than dropping it.
            val placed = resolvePlacement(current, widget, cols, rows) ?: widget
            current.copy(items = current.items + placed)
        }
    }

    // Provider/grid constraint validation happens in the UI layer (WidgetSizing);
    // the repository enforces only occupancy and the >=1 floor. The anchor cell
    // moves along with the span when resizing from the top or left edge.
    // False when the resize was refused (nothing saved), so the caller can
    // put the widget and its frame back.
    // rect is in cells, on half cells with the subgrid on.
    suspend fun resizeWidget(itemId: String, rect: WorkspaceGeometry.CellRect, cols: Int, rows: Int): Boolean {
        var saved = false
        mutate { current ->
            val widget = current.items.firstOrNull { it.id == itemId && it.itemType == ItemType.WIDGET }
                ?: return@mutate current
            val width = (rect.right - rect.left).coerceIn(MIN_WIDGET_SPAN, cols.toFloat())
            val height = (rect.bottom - rect.top).coerceIn(MIN_WIDGET_SPAN, rows.toFloat())
            val left = rect.left.coerceIn(0f, cols - width)
            val top = rect.top.coerceIn(0f, rows - height)
            val area = WorkspaceGeometry.CellRect(left, top, left + width, top + height)
            // Items already under the widget were let in there (widget
            // overlap, overlap when placing); like the frame's room check,
            // the resize only has to keep clear of the rest. Counted, they
            // refused every resize, shrinking included.
            val underneath = WorkspaceGeometry.idsOverlapping(
                current, widget.page, WorkspaceGeometry.itemRect(widget, cols, rows), cols, rows
            )
            if (!WorkspaceGeometry.isAreaFree(
                    current, widget.page, area, cols, rows, underneath + widget.id,
                    ignoreItems = WorkspaceGeometry.allowPlacementOverlap
                )
            ) {
                return@mutate current
            }
            saved = true
            current.copy(
                items = current.items.map {
                    if (it.id == itemId) WorkspaceGeometry.widgetOn(it, area) else it
                }
            )
        }
        return saved
    }

    suspend fun setWidgetPadding(itemId: String, paddingDp: Int?) {
        mutate { current ->
            current.copy(
                items = current.items.map {
                    if (it.id == itemId && it.itemType == ItemType.WIDGET) {
                        it.copy(widgetPadding = paddingDp?.coerceIn(0, MAX_WIDGET_PADDING_DP))
                    } else {
                        it
                    }
                }
            )
        }
    }

    suspend fun restackItem(itemId: String, toFront: Boolean) {
        mutate { current ->
            current.copy(items = WorkspaceGeometry.restacked(current.items, itemId, toFront))
        }
    }

    suspend fun setFolderSwipeToOpen(folderId: String, on: Boolean) {
        mutate { current ->
            current.copy(
                items = current.items.map {
                    if (it.id == folderId && it.itemType == ItemType.FOLDER) it.copy(swipeToOpen = on) else it
                }
            )
        }
    }

    suspend fun setSwipeAction(itemId: String, component: String?, label: String?, down: Boolean = false) {
        mutate { current ->
            current.copy(
                items = current.items.map {
                    when {
                        it.id != itemId -> it
                        down -> it.copy(swipeActionDown = component, swipeLabelDown = label)
                        else -> it.copy(swipeAction = component, swipeLabel = label)
                    }
                }
            )
        }
    }

    // One past every page shown, items on pages past pageCount included:
    // counted from pageCount alone, such a layout added a page nobody saw,
    // and edit mode's "+" then waited for it for the rest of the session.
    suspend fun addPage() {
        mutate { current ->
            val shown = maxOf(
                current.pageCount,
                (current.items.filter { it.container == ContainerType.WORKSPACE }.maxOfOrNull { it.page } ?: -1) + 1
            )
            current.copy(pageCount = (shown + 1).coerceAtMost(MAX_PAGES))
        }
    }

    // False when the page wasn't removed: it holds something, or is the last.
    suspend fun removePage(page: Int): Boolean {
        var removed = false
        mutate { current ->
            val occupied = current.items.any { it.container == ContainerType.WORKSPACE && it.page == page }
            if (current.pageCount <= 1 || occupied || page !in 0 until current.pageCount) return@mutate current
            removed = true
            current.copy(
                pageCount = current.pageCount - 1,
                defaultPage = (if (current.defaultPage > page) current.defaultPage - 1 else current.defaultPage)
                    .coerceIn(0, current.pageCount - 2),
                items = current.items.map {
                    if (it.container == ContainerType.WORKSPACE && it.page > page) it.copy(page = it.page - 1) else it
                }
            )
        }
        return removed
    }

    // Every page with nothing on it goes, but the home page, the pages in
    // keep and always one page. The pages after each one move down. Returns
    // the pages removed, by their old numbers, in order.
    suspend fun removeEmptyPages(keep: Set<Int>): List<Int> {
        var removed = emptyList<Int>()
        mutate { current ->
            removed = emptyPagesToRemove(current, keep)
            withoutPages(current, removed)
        }
        return removed
    }

    suspend fun movePage(from: Int, to: Int) {
        mutate { current ->
            if (from == to || from !in 0 until current.pageCount || to !in 0 until current.pageCount) {
                return@mutate current
            }
            fun remap(page: Int): Int = when {
                page == from -> to
                from < to && page in (from + 1)..to -> page - 1
                to < from && page in to until from -> page + 1
                else -> page
            }
            current.copy(
                defaultPage = remap(current.defaultPage),
                items = current.items.map {
                    if (it.container == ContainerType.WORKSPACE) it.copy(page = remap(it.page)) else it
                }
            )
        }
    }

    suspend fun setDefaultPage(page: Int) {
        mutate { current -> current.copy(defaultPage = page.coerceIn(0, current.pageCount - 1)) }
    }

    // Widgets are bound for the launcher's own user: the picker lists only
    // that profile's providers, and widget items don't record a user.
    private val ownUserSerial: Long by lazy {
        context.getSystemService(android.os.UserManager::class.java)
            ?.getSerialNumberForUser(android.os.Process.myUserHandle()) ?: 0L
    }

    suspend fun removeItemsForPackage(packageName: String, userSerial: Long) {
        mutate { current ->
            val removedIds = current.items.filter { item ->
                when (item.itemType) {
                    ItemType.APP,
                    ItemType.SHORTCUT -> item.packageName == packageName && item.userSerial == userSerial
                    // So only the launcher's own user losing the app takes
                    // its widgets: the work copy of an app going leaves the
                    // personal widget alone.
                    ItemType.WIDGET -> userSerial == ownUserSerial &&
                        item.widgetProvider?.substringBefore('/') == packageName
                    ItemType.FOLDER -> false
                }
            }.map { it.id }.toSet()
            if (removedIds.isEmpty()) return@mutate current

            val remaining = current.items.filterNot { it.id in removedIds }
            val emptyFolderIds = remaining.filter { folder ->
                folder.itemType == ItemType.FOLDER && remaining.none { it.folderId == folder.id }
            }.map { it.id }.toSet()

            current.copy(
                items = remaining.filterNot { it.id in emptyFolderIds },
                // Overrides for the removed app would otherwise accrete forever.
                appOverrides = current.appOverrides.filterKeys {
                    !it.startsWith("$userSerial/$packageName/")
                }
            )
        }
    }

    suspend fun setAppOverride(key: String, transform: (AppOverride) -> AppOverride) {
        mutate { current ->
            val updated = transform(current.appOverrides[key] ?: AppOverride())
            current.copy(
                appOverrides = if (updated.isEmpty()) {
                    current.appOverrides - key
                } else {
                    current.appOverrides + (key to updated)
                }
            )
        }
    }

    suspend fun clearAppOverride(key: String) {
        mutate { current -> current.copy(appOverrides = current.appOverrides - key) }
    }

    // Items snapshot their label at add time; a cleared rename restores the
    // catalog's label on every pinned copy.
    suspend fun relabelItems(packageName: String, className: String, userSerial: Long, label: String) {
        mutate { current ->
            current.copy(items = current.items.map { item ->
                if (item.packageName == packageName && item.className == className &&
                    item.userSerial == userSerial && item.itemType == ItemType.APP
                ) {
                    item.copy(label = label)
                } else {
                    item
                }
            })
        }
    }

    // A folder's Sort: its apps ranked A-Z, an order it then keeps.
    suspend fun sortFolder(folderId: String) {
        mutate { FolderSort.sorted(it, folderId) }
    }

    suspend fun setFolderIconRaw(folderId: String, raw: Boolean) {
        mutate { current ->
            current.copy(items = current.items.map { item ->
                if (item.id == folderId) item.copy(iconRaw = raw) else item
            })
        }
    }

    suspend fun setFolderIcon(folderId: String, pack: String?, drawableName: String?, base64: String? = null) {
        mutate { current ->
            current.copy(items = current.items.map { item ->
                if (item.id == folderId) {
                    item.copy(customIconPack = pack, customIconName = drawableName, iconBase64 = base64)
                } else {
                    item
                }
            })
        }
    }

    suspend fun resetCustomIcons() {
        mutate { CustomIcons.reset(it) }
    }

    // Feed cards (model v5). Upsert-by-id so the editor's save path is one
    // call for both create and edit. An existing card keeps its slot: the
    // stored order is the feed's order within each group, and a pin/edit
    // must not shuffle it.
    suspend fun upsertFeedCard(card: com.supernova.launcher.feed.FeedCard) {
        mutate { current ->
            val cards = current.feedCards
            current.copy(
                feedCards = if (cards.any { it.id == card.id }) {
                    cards.map { if (it.id == card.id) card else it }
                } else {
                    cards + card
                }
            )
        }
    }

    suspend fun removeFeedCard(cardId: String) {
        mutate { current ->
            current.copy(feedCards = current.feedCards.filterNot { it.id == cardId })
        }
    }

    // Sets a bound widget as a card's featured content. The old host id (if
    // any) is freed by the caller before this runs.
    suspend fun setCardWidget(cardId: String, appWidgetId: Int, provider: String) {
        mutate { current ->
            current.copy(feedCards = current.feedCards.map { card ->
                if (card.id == cardId) {
                    card.copy(
                        featuredType = com.supernova.launcher.feed.FeaturedType.WIDGET.name,
                        featuredTarget = appWidgetId.toString(),
                        appWidgetId = appWidgetId,
                        widgetProvider = provider
                    )
                } else {
                    card
                }
            })
        }
    }

    // Rolling local snapshots so a bad layout edit is recoverable without the
    // user having exported anything. Kept out of the export path's way.
    private val autoBackupDir: File get() = File(context.filesDir, "auto_backups")

    // Whether the snapshot was written.
    suspend fun writeAutoBackup(prefs: LauncherPrefs): Boolean = runCatching {
        val payload = exportBackup(prefs)
        autoBackupDir.mkdirs()
        val written = File(autoBackupDir, "layout-${System.currentTimeMillis()}.json")
        written.writeText(payload)
        // The newest by name are kept, but never at the cost of the one
        // just written: a clock set back names it older than the rest, and
        // the snapshot taken before a reset or an import must survive.
        autoBackupDir.listFiles()
            ?.filter { it != written }
            ?.sortedByDescending { it.name }
            ?.drop(AUTO_BACKUP_KEEP - 1)
            ?.forEach { it.delete() }
    }.isSuccess

    fun autoBackups(): List<File> =
        autoBackupDir.listFiles()?.sortedByDescending { it.name }.orEmpty()

    suspend fun exportBackup(prefs: LauncherPrefs): String {
        val model = modelFlow.first()
        val payload = BackupPayload(
            exportedAtEpochMs = System.currentTimeMillis(),
            model = model,
            prefs = prefs
        )
        return json.encodeToString(payload)
    }

    data class ImportResult(
        val restored: Int,
        val droppedMissingApps: Int,
        val widgetsNeedingRebind: Int
    ) {
        // What an import tells the user: "Restored 12 items, skipped 1
        // missing app".
        fun summary(res: Resources): String = buildString {
            append(res.getQuantityString(R.plurals.backup_imported_count, restored, restored))
            if (droppedMissingApps > 0) {
                append(res.getQuantityString(R.plurals.backup_dropped_apps, droppedMissingApps, droppedMissingApps))
            }
            if (widgetsNeedingRebind > 0) {
                append(res.getQuantityString(R.plurals.backup_widgets_rebind, widgetsNeedingRebind, widgetsNeedingRebind))
            }
        }
    }

    // currentPrefs are the prefs being replaced, for the snapshot taken first.
    suspend fun importBackup(
        raw: String,
        currentPrefs: LauncherPrefs,
        isInstalled: (packageName: String, userSerial: Long) -> Boolean
    ): ImportResult {
        val payload = json.decodeFromString<BackupPayload>(raw)
        val migrated = ModelMigrations.migrate(payload.model)

        // Apps that vanished since the backup would render as dead icons, and
        // widget ids never survive a restore - the host must re-bind them.
        val missing = migrated.items.filter { item ->
            (item.itemType == ItemType.APP || item.itemType == ItemType.SHORTCUT) &&
                item.packageName?.let { !isInstalled(it, item.userSerial) } ?: false
        }.map { it.id }.toSet()

        val kept = migrated.items.filterNot { it.id in missing }
        val emptyFolders = kept.filter { folder ->
            folder.itemType == ItemType.FOLDER && kept.none { it.folderId == folder.id }
        }.map { it.id }.toSet()

        val validated = migrated.copy(
            items = kept
                .filterNot { it.id in emptyFolders }
                // Stale ids belong to the old install's host; clear them so the
                // workspace shows a placeholder instead of someone else's widget.
                .map { if (it.itemType == ItemType.WIDGET) it.copy(appWidgetId = null) else it },
            // Feed card widget ids die with the old host exactly like item
            // widget ids; the provider component survives for a future rebind.
            feedCards = migrated.feedCards.map {
                if (it.appWidgetId != null) it.copy(appWidgetId = null) else it
            }
        )

        // What is being replaced stays recoverable from Automatic backups, as
        // Reset's does: without that snapshot, no import. Written under the
        // lock, so no layout edit lands in between and is lost.
        check(writeAutoBackup(currentPrefs)) { "couldn't save a backup of the current layout first" }
        lock.withLock {
            context.supernovaStore.edit { prefs ->
                prefs[StoreKeys.model] = json.encodeToString(validated)
                LauncherPreferences.writeAll(prefs, payload.prefs)
            }
        }

        return ImportResult(
            restored = validated.items.size,
            droppedMissingApps = missing.size,
            widgetsNeedingRebind = validated.items.count { it.itemType == ItemType.WIDGET } +
                migrated.feedCards.count { it.appWidgetId != null }
        )
    }

    // The fresh-install layout (see DefaultLayoutBuilder), written only into a
    // model that holds nothing at all: an upgrade from an older version, a
    // restored backup, or anything the user has already placed keeps its own
    // layout untouched. Being once-per-install is the caller's business -
    // LauncherPreferences.claimDefaultLayoutSeed.
    suspend fun seedDefaultLayout(items: List<LauncherItem>) {
        if (items.isEmpty()) return
        mutate { current -> if (current.items.isEmpty()) current.copy(items = items) else current }
    }

    // Settings > Reset to default. The auto-backup snapshot goes first so the
    // reset itself is recoverable from Automatic backups; then one atomic edit
    // replaces the model document and every LauncherPrefs key. Device-local
    // keys (launch counts, onboarding/home-prompt flags, dock page state,
    // search recents) are deliberately untouched. Host widget ids of the
    // removed widgets are deleted by the caller, which can reach the host by
    // id; the next launcher create's sweepOrphanWidgetIds would reclaim them
    // regardless.
    suspend fun resetToDefaults(prefs: LauncherPrefs, fresh: LauncherPrefs) {
        // No snapshot, no reset: the reset has to be undoable.
        check(writeAutoBackup(prefs)) { "couldn't save a backup first" }
        lock.withLock {
            context.supernovaStore.edit { target ->
                target[StoreKeys.model] = json.encodeToString(LauncherModel(version = CURRENT_MODEL_VERSION))
                LauncherPreferences.writeAll(target, fresh)
            }
        }
    }

    // A placed widget onto `rect` (in cells, half ones included), clear of
    // what a drag previewed aside; an occupied spot sends it to the nearest
    // free whole cells.
    suspend fun moveWidget(
        itemId: String,
        page: Int,
        rect: WorkspaceGeometry.CellRect,
        cols: Int,
        rows: Int,
        displace: List<WorkspaceGeometry.ReorderMove> = emptyList()
    ) {
        mutate { original ->
            val current = applyDisplacement(original, displace, itemId, cols, rows) ?: original
            val moving = current.items.firstOrNull { it.id == itemId && it.itemType == ItemType.WIDGET }
                ?: return@mutate original
            val moved = WorkspaceGeometry.widgetOn(moving.copy(page = page), rect)
            val placed = resolvePlacement(current, moved, cols, rows) ?: return@mutate original
            current.copy(items = current.items.map { if (it.id == placed.id) placed else it })
        }
    }

    private suspend fun addNewWorkspaceItem(
        item: LauncherItem,
        cols: Int,
        rows: Int,
        displace: List<WorkspaceGeometry.ReorderMove> = emptyList(),
        mergeFolders: Boolean = true,
        mergeTargetId: String? = null
    ) {
        mutate { original ->
            val current = applyDisplacement(original, displace, null, cols, rows) ?: original
            val target = mergeTarget(current, item.page, item.cellX, item.cellY, null, mergeTargetId)

            when {
                mergeFolders && target != null && target.itemType == ItemType.FOLDER && isFolderEligible(item) ->
                    moveIntoFolderModel(
                        current.copy(items = current.items + item),
                        item,
                        target.id
                    )
                mergeFolders && target != null && isFolderEligible(item) && isFolderEligible(target) ->
                    createNewFolderModel(current, item, target)
                else -> {
                    val placed = resolvePlacement(current, item, cols, rows) ?: return@mutate original
                    current.copy(items = current.items + placed)
                }
            }
        }
    }

    // Keeps the item where it asked to be if that area is free; otherwise
    // relocates it to the nearest free cell (cell-centred), or returns null
    // when the page has no room at all.
    private fun moveIntoFolderModel(current: LauncherModel, moving: LauncherItem, folderId: String): LauncherModel {
        val nextRank = (current.items.filter { it.folderId == folderId }.maxOfOrNull { it.rank } ?: -1) + 1
        return current.copy(
            items = current.items.map {
                if (it.id == moving.id) {
                    it.copy(
                        container = ContainerType.FOLDER,
                        folderId = folderId,
                        workspaceX = null,
                        workspaceY = null,
                        rank = nextRank
                    )
                } else {
                    it
                }
            }
        )
    }

    // The item a folder drop merges with. The hover picks it by where icons
    // are drawn - their float centres - which after a grid change, or with
    // the subgrid, is not their stored cell; matching the drop cell merged
    // with another icon, or with none while the hovered one pulsed. So the
    // caller names it when it knows it, and the cell is only the fallback.
    private fun mergeTarget(
        current: LauncherModel,
        page: Int,
        cellX: Int,
        cellY: Int,
        excludeId: String?,
        targetId: String?
    ): LauncherItem? = if (targetId != null) {
        current.items.firstOrNull {
            it.id == targetId && it.id != excludeId && it.container == ContainerType.WORKSPACE && it.page == page
        }
    } else {
        current.items.firstOrNull {
            it.container == ContainerType.WORKSPACE && it.page == page && it.cellX == cellX && it.cellY == cellY &&
                it.id != excludeId
        }
    }

    // The new folder takes the target icon's place exactly, float position
    // included, rather than the drop's cell.
    private fun createNewFolderModel(
        current: LauncherModel,
        moving: LauncherItem,
        target: LauncherItem
    ): LauncherModel {
        val folderId = id()
        val folder = LauncherItem(
            id = folderId,
            itemType = ItemType.FOLDER,
            container = ContainerType.WORKSPACE,
            page = target.page,
            cellX = target.cellX,
            cellY = target.cellY,
            workspaceX = target.workspaceX,
            workspaceY = target.workspaceY,
            label = "Folder"
        )

        val itemsWithoutTarget = current.items.filterNot { it.id == target.id }
        val itemsWithMoving = if (itemsWithoutTarget.any { it.id == moving.id }) {
            itemsWithoutTarget
        } else {
            itemsWithoutTarget + moving
        }

        val result = itemsWithMoving.map {
            when (it.id) {
                target.id -> it
                moving.id -> it.copy(
                    container = ContainerType.FOLDER,
                    folderId = folderId,
                    workspaceX = null,
                    workspaceY = null,
                    rank = 1
                )
                else -> it
            }
        }.filterNot { it.id == target.id } + target.copy(
            container = ContainerType.FOLDER,
            folderId = folderId,
            workspaceX = null,
            workspaceY = null,
            rank = 0
        ) + folder

        return current.copy(items = result)
    }

    private suspend fun mutate(block: (LauncherModel) -> LauncherModel) {
        lock.withLock {
            // Never over a layout this build couldn't read: an edit to the
            // empty stand-in would replace it for good.
            val current = decode(context.supernovaStore.data.first()[StoreKeys.model]) ?: return
            val next = block(current)
            if (next == current) return
            context.supernovaStore.edit { prefs ->
                prefs[StoreKeys.model] = json.encodeToString(next)
            }
        }
    }

    private fun id(): String = UUID.randomUUID().toString()

    companion object {
        // One lock for the process, not one per repository: every open
        // screen's view model has its own repository, and each read-modify-
        // write has to see the others' writes or one of them is lost.
        private val lock = Mutex()

        internal fun emptyPagesToRemove(model: LauncherModel, keep: Set<Int>): List<Int> {
            val occupied = model.items.filter { it.container == ContainerType.WORKSPACE }.map { it.page }.toSet()
            // The pager shows a page for every item, whatever pageCount says.
            val count = maxOf(model.pageCount, (occupied.maxOrNull() ?: -1) + 1)
            val empty = (0 until count).filter { it !in occupied && it !in keep && it != model.defaultPage }
            return if (empty.size >= count) empty.drop(1) else empty
        }

        internal fun withoutPages(model: LauncherModel, pages: List<Int>): LauncherModel {
            if (pages.isEmpty()) return model
            val count = maxOf(
                model.pageCount,
                (model.items.filter { it.container == ContainerType.WORKSPACE }.maxOfOrNull { it.page } ?: -1) + 1
            )
            fun shifted(page: Int) = page - pages.count { it < page }
            return model.copy(
                pageCount = (count - pages.size).coerceAtLeast(1),
                defaultPage = shifted(model.defaultPage).coerceIn(0, (count - pages.size - 1).coerceAtLeast(0)),
                items = model.items.map {
                    if (it.container == ContainerType.WORKSPACE) it.copy(page = shifted(it.page)) else it
                }
            )
        }

        // Applies reorder displacements before the dragged item is placed. Every
        // displaced item's new spot is re-validated against the (possibly stale)
        // model this write actually sees; any mismatch abandons the whole
        // displacement rather than corrupting the layout.
        private fun applyDisplacement(
            current: LauncherModel,
            displace: List<WorkspaceGeometry.ReorderMove>,
            movingId: String?,
            cols: Int,
            rows: Int
        ): LauncherModel? {
            if (displace.isEmpty()) return current
            val byId = displace.associateBy { it.itemId }
            if (!displace.all { m -> current.items.any { it.id == m.itemId } }) return null

            val updated = current.copy(
                items = current.items.map { item ->
                    val m = byId[item.id] ?: return@map item
                    if (item.itemType == ItemType.WIDGET && item.halfCells) {
                        return@map WorkspaceGeometry.widgetOn(
                            item, WorkspaceGeometry.displaced(WorkspaceGeometry.itemRect(item, cols, rows), m)
                        )
                    }
                    val newCellX = (item.cellX + m.dCellX).coerceIn(0, cols - 1)
                    val newCellY = (item.cellY + m.dCellY).coerceIn(0, rows - 1)
                    if (item.workspaceX == null || item.workspaceY == null || item.itemType == ItemType.WIDGET) {
                        item.copy(cellX = newCellX, cellY = newCellY)
                    } else if (m.snapToCell) {
                        val (wx, wy) = WorkspaceGeometry.cellCenteredPosition(newCellX, newCellY, cols, rows)
                        item.copy(cellX = newCellX, cellY = newCellY, workspaceX = wx, workspaceY = wy)
                    } else {
                        // A pushed subgrid icon keeps its fractional offset.
                        item.copy(
                            cellX = newCellX,
                            cellY = newCellY,
                            workspaceX = (item.workspaceX + m.dCellX / cols.toFloat()).coerceIn(0f, 1f),
                            workspaceY = (item.workspaceY + m.dCellY / rows.toFloat()).coerceIn(0f, 1f)
                        )
                    }
                }
            )
            val valid = displace.all { m ->
                val item = updated.items.first { it.id == m.itemId }
                WorkspaceGeometry.isAreaFree(
                    updated, item.page, WorkspaceGeometry.itemRect(item, cols, rows),
                    cols, rows, setOfNotNull(item.id, movingId)
                )
            }
            return if (valid) updated else null
        }


        // addFolderToWorkspace's model change, pure for tests: the neighbours
        // make room as the drop's preview showed, then the folder takes its
        // spot or the nearest free one - never merged into what it lands
        // on, as a folder can go into no folder, nor make one with an icon.
        // A page with no room leaves the model as it was.
        internal fun withNewFolder(
            original: LauncherModel,
            folder: LauncherItem,
            members: List<LauncherItem>,
            displace: List<WorkspaceGeometry.ReorderMove>,
            cols: Int,
            rows: Int
        ): LauncherModel {
            val current = applyDisplacement(original, displace, null, cols, rows) ?: original
            val placed = resolvePlacement(current, folder, cols, rows) ?: return original
            return current.copy(items = current.items + placed + members)
        }

        // addNewItemToDock's model change, pure for tests. An icon already
        // in the slot moves out to a real free cell: on the page in view,
        // else any page. With no room anywhere the drop is refused.
        internal fun withNewDockItem(
            current: LauncherModel,
            item: LauncherItem,
            members: List<LauncherItem>,
            rank: Int,
            cols: Int,
            rows: Int,
            preferredPage: Int
        ): LauncherModel {
            val displaced = current.items.firstOrNull {
                it.container == ContainerType.DOCK && it.rank == rank
            }
            val movedOut = if (displaced == null) {
                current.items
            } else {
                // It used to try page 0 alone and, that full, sat at cell
                // (0,0) on top of another icon.
                val pages = listOf(preferredPage) + (0 until current.pageCount).filter { it != preferredPage }
                val (page, cell) = pages.firstNotNullOfOrNull { page ->
                    WorkspaceGeometry.findFreeCell(current, page, 1, 1, cols, rows)?.let { page to it }
                } ?: return current
                current.items.map {
                    if (it.id == displaced.id) {
                        it.copy(
                            container = ContainerType.WORKSPACE,
                            page = page,
                            cellX = cell.first,
                            cellY = cell.second,
                            workspaceX = null,
                            workspaceY = null,
                            rank = 0
                        )
                    } else {
                        it
                    }
                }
            }
            return current.copy(items = movedOut + item + members)
        }

        private fun resolvePlacement(
            current: LauncherModel,
            item: LauncherItem,
            cols: Int,
            rows: Int
        ): LauncherItem? {
            val rect = WorkspaceGeometry.itemRect(item, cols, rows)
            val exclude = setOf(item.id)
            // Overlap-when-placing keeps the requested spot whenever it is inside
            // the grid, occupied or not.
            if (WorkspaceGeometry.isAreaFree(
                    current, item.page, rect, cols, rows, exclude,
                    ignoreItems = WorkspaceGeometry.allowPlacementOverlap
                )
            ) {
                return item
            }

            // A widget on half cells settles on whole ones when it has to
            // move somewhere else.
            val (spanX, spanY) = WorkspaceGeometry.wholeSpans(rect)
            val cell = WorkspaceGeometry.nearestFreeCell(
                model = current,
                page = item.page,
                nearCellX = (rect.left + rect.right) / 2f,
                nearCellY = (rect.top + rect.bottom) / 2f,
                spanX = if (item.itemType == ItemType.WIDGET) spanX else item.spanX,
                spanY = if (item.itemType == ItemType.WIDGET) spanY else item.spanY,
                cols = cols,
                rows = rows,
                excludeIds = exclude
            ) ?: return null

            return if (item.itemType == ItemType.WIDGET) {
                WorkspaceGeometry.widgetOn(item, WorkspaceGeometry.cellRect(cell.first, cell.second, spanX, spanY))
            } else if (item.workspaceX == null || item.workspaceY == null) {
                item.copy(cellX = cell.first, cellY = cell.second)
            } else {
                val (centerX, centerY) = WorkspaceGeometry.cellCenteredPosition(cell.first, cell.second, cols, rows)
                item.copy(cellX = cell.first, cellY = cell.second, workspaceX = centerX, workspaceY = centerY)
            }
        }

        // restoreItems' model change (undo of a removal), pure for tests. A
        // page item goes back to its spot or the nearest free one. A dock
        // item goes back to its slot if that is still free, else to the
        // first free slot, else to the nearest free cell of the home page.
        // Keeping the original spot is the last resort, rather than
        // dropping the undo.
        internal fun restored(
            current: LauncherModel,
            items: List<LauncherItem>,
            cols: Int,
            rows: Int,
            dockSlots: Int
        ): LauncherModel {
            val existing = current.items.map { it.id }.toSet()
            val toRestore = items.filter { it.id !in existing }
            if (toRestore.isEmpty()) return current
            val takenRanks = current.items.filter { it.container == ContainerType.DOCK }.map { it.rank }.toMutableSet()
            val placed = toRestore.map { item ->
                when (item.container) {
                    ContainerType.WORKSPACE -> resolvePlacement(current, item, cols, rows) ?: item
                    ContainerType.DOCK -> when {
                        takenRanks.add(item.rank) -> item
                        else -> (0 until dockSlots).firstOrNull { it !in takenRanks }
                            ?.let { rank -> takenRanks.add(rank); item.copy(rank = rank) }
                            ?: resolvePlacement(
                                current,
                                item.copy(
                                    container = ContainerType.WORKSPACE, page = current.defaultPage,
                                    cellX = 0, cellY = 0, workspaceX = null, workspaceY = null, rank = 0
                                ),
                                cols, rows
                            )
                            ?: item
                    }
                    ContainerType.FOLDER -> item
                }
            }
            return current.copy(items = current.items + placed)
        }

        private fun isFolderEligible(item: LauncherItem): Boolean {
            return item.itemType == ItemType.APP || item.itemType == ItemType.SHORTCUT
        }

        // moveItemToDock's model change, pure so the swaps can be tested.
        internal fun movedToDock(current: LauncherModel, itemId: String, rank: Int): LauncherModel {
            val moving = current.items.firstOrNull { it.id == itemId } ?: return current
            // Widgets live on pages only: the dock would host one squeezed
            // into a single slot, stretching the whole row's height.
            if (moving.itemType == ItemType.WIDGET) return current
            val swapTarget = current.items.firstOrNull {
                it.container == ContainerType.DOCK && it.rank == rank && it.id != moving.id
            }
            // The icon in the slot trades places with the one dropped on it,
            // wherever that came from: another dock slot, a page cell (free
            // now), or a folder, whose place it takes. Only the page cell is
            // the dragged item's page and cell; a dock or folder item's are
            // left over from long ago and may overlap or no longer exist.
            if (swapTarget != null && moving.container == ContainerType.FOLDER && !isFolderEligible(swapTarget)) {
                // A folder can't go into a folder: leave both where they are.
                return current
            }
            val moved = current.copy(
                items = current.items.map {
                    when {
                        it.id == moving.id -> it.copy(
                            container = ContainerType.DOCK,
                            workspaceX = null,
                            workspaceY = null,
                            rank = rank,
                            folderId = null
                        )
                        swapTarget != null && it.id == swapTarget.id -> when (moving.container) {
                            ContainerType.DOCK -> it.copy(rank = moving.rank)
                            ContainerType.FOLDER -> it.copy(
                                container = ContainerType.FOLDER,
                                folderId = moving.folderId,
                                workspaceX = null,
                                workspaceY = null,
                                rank = moving.rank
                            )
                            else -> it.copy(
                                container = ContainerType.WORKSPACE,
                                page = moving.page,
                                cellX = moving.cellX,
                                cellY = moving.cellY,
                                workspaceX = moving.workspaceX,
                                workspaceY = moving.workspaceY,
                                rank = 0,
                                folderId = null
                            )
                        }
                        else -> it
                    }
                }
            )
            // Out of a folder into an empty slot leaves the folder an app
            // short. A trade puts the slot's icon in the app's place, and
            // the folder stays as full as it was.
            return if (moving.container == ContainerType.FOLDER && swapTarget == null) {
                withSparseFolderDissolved(moved, moving.folderId)
            } else {
                moved
            }
        }

        // A folder an app has just been taken out of, with one app or none
        // left in it, gives way: the last app takes the
        // folder's place - its cell, or its dock slot - and an empty folder
        // simply goes. The folder's own name and icon go with it.
        internal fun withSparseFolderDissolved(current: LauncherModel, folderId: String?): LauncherModel {
            val folder = current.items.firstOrNull { it.id == folderId && it.itemType == ItemType.FOLDER }
                ?: return current
            val members = current.items.filter { it.container == ContainerType.FOLDER && it.folderId == folder.id }
            if (members.size > 1) return current
            val last = members.singleOrNull()?.copy(
                container = folder.container,
                page = folder.page,
                cellX = folder.cellX,
                cellY = folder.cellY,
                workspaceX = folder.workspaceX,
                workspaceY = folder.workspaceY,
                rank = folder.rank,
                folderId = null
            )
            // In the folder's place in the list too, which is its place in
            // the page's stacking.
            return current.copy(
                items = current.items.mapNotNull {
                    when (it.id) {
                        folder.id -> last
                        last?.id -> null
                        else -> it
                    }
                }
            )
        }

        private const val AUTO_BACKUP_KEEP = 5
        // The narrowest a widget resizes to: half a cell, on the subgrid.
        private const val MIN_WIDGET_SPAN = 0.5f
        // Shared with the surfaces that offer an add-page control.
        const val MAX_PAGES = 9
        // The top of the Widget padding bar, which a widget's own padding
        // shares.
        const val MAX_WIDGET_PADDING_DP = 24
    }
}
