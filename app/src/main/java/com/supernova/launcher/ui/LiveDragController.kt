package com.supernova.launcher.ui

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.PopupWindow
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.children
import androidx.core.view.drawToBitmap
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import com.supernova.launcher.R
import com.supernova.launcher.data.ContainerType
import com.supernova.launcher.data.ItemType
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.data.LauncherItem
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.LauncherRepository
import com.supernova.launcher.data.WorkspaceGeometry
import com.supernova.launcher.databinding.ActivityLauncherBinding
import com.supernova.launcher.widget.WidgetResizeFrame
import com.supernova.launcher.widget.WidgetSizing
import kotlin.math.hypot
import kotlin.math.max

// The launcher's own drag engine, menu first. An item long-pressed
// shows its menu at once, non-touch-modal, and arms the gesture (arm); the
// first real movement lifts the item into DragLayer as a picture
// (routeGesture, then beginLiveDrag, inside the same touch stream), the
// menu goes, and every drop target is found by hit-testing the views under
// the finger: the drop-zone bar, the dock slots, the workspace page. The
// drop itself (the model write, the reorder preview, the folder merge) is
// the host's; this class moves the picture, finds the target, previews the
// grid, flips pages from an edge hover, and settles or flies the picture
// back.
//
// Touches reach it from the activity's window and from any window an item
// can be long-pressed in besides it (a drawer folder's card), through
// routeGesture; see its comment.
class LiveDragController(
    private val binding: ActivityLauncherBinding,
    private val viewModel: LauncherViewModel,
    private val host: Host
) {

    interface Host {
        val workspaceAdapter: WorkspacePagerAdapter
        val activeResizeFrame: WidgetResizeFrame?
        val drawerExpanded: Boolean

        // The press that may become a menu or a drag has landed; the item
        // menus open at that finger.
        fun onGestureDown(ev: MotionEvent)

        // Sends the cancel to everything under a drag that has just begun.
        fun cancelTouchesBelow(cancel: MotionEvent)
        fun animateWallpaperZoom(target: Float)

        // A new item leaves what it was picked from (drawer, search, a card).
        fun closeItemSources()
        fun exitEditMode()

        // Geometry the settle needs: cell size, icon boxes, the dock icon's
        // top inset, the desktop icon base and the pixel padding of an item.
        fun currentCellSizePx(): Pair<Int, Int>
        fun desktopIconBoxPx(prefs: LauncherPrefs): Int
        fun dockIconBoxPx(prefs: LauncherPrefs): Int
        fun dockIconTopInsetPx(): Int
        fun desktopIconBasePx(): Int

        fun installedApp(packageName: String, userSerial: Long): LaunchableApp?
        fun removeItemWithUndo(item: LauncherItem)
        // A drag is over, its picture landed or gone.
        fun onDragFinished()

        // The hover's reorder preview and the drop itself.
        fun hoverTargetRect(
            payload: DragPayload,
            cellX: Int,
            cellY: Int,
            rawCellX: Float,
            rawCellY: Float
        ): Pair<WorkspaceGeometry.CellRect, Set<String>>?

        fun onDragOverWorkspace(
            payload: DragPayload,
            page: Int,
            canvas: ViewGroup,
            cellWidth: Int,
            cellHeight: Int,
            cellX: Int,
            cellY: Int,
            rawCellX: Float,
            rawCellY: Float
        )

        fun cancelReorderPreview(animate: Boolean)

        // False when the drop can't be taken: the picture then flies back.
        fun handleDropToDock(payload: DragPayload, rank: Int): Boolean

        fun onDropToWorkspace(
            payload: DragPayload,
            page: Int,
            cellX: Int,
            cellY: Int,
            workspaceX: Float,
            workspaceY: Float,
            rawCellX: Float,
            rawCellY: Float
        ): Boolean
    }

    private val context: Context get() = binding.root.context

    private val desktopLocked: Boolean
        get() = viewModel.state.value.prefs.desktopLocked

    // A press is armed (menu up, finger still down); a drag is in flight.
    val isArmed: Boolean get() = pendingDrag != null
    val isActive: Boolean get() = activeDragPayload != null
    val isDragging: Boolean get() = dragLayer.isDragging

    // The window an armed press or its drag was taken from has closed under
    // it (a home press, Back, a rotation): the rest of that gesture would
    // only ever have come through that window, so it ends here.
    fun onWindowDismissed(window: PopupWindow?) {
        if (pendingDragWindow === window) {
            pendingDrag = null
            pendingDragWindow = null
        }
        if (dragWindow === window) {
            dragWindow = null
            cancel()
        }
    }

    // A drag hovering past the last page asked for a new one; once the model
    // carries it (the host's state observer), the drag is carried over to it.
    fun carryOverToNewPage() {
        if (pendingDragPageFlip in 0 until host.workspaceAdapter.realPageCount) {
            val page = pendingDragPageFlip
            pendingDragPageFlip = -1
            if (dragLayer.isDragging) {
                binding.workspacePager.setCurrentItem(host.workspaceAdapter.centerPosition(page), true)
            }
        }
    }

    // Where that press landed on screen: a drag's picture is held at the
    // point the finger pressed, not at wherever it had moved to by the time
    // the drag threshold was crossed.
    private var pressRawX = 0f
    private var pressRawY = 0f

    // Long-press drag state, menu first. CRITICAL (do not reorder): the
    // item menu appears AT long-press but non-touch-modal, so the still-down
    // gesture keeps streaming to this window; the system drag is NOT started
    // yet - dispatchTouchEvent watches the armed gesture (pendingDrag) and
    // starts the drag lazily on the first real movement (beginLiveDrag, which
    // must still run inside the touch stream), dismissing the menu. A finger
    // lift instead upgrades the menu to a normal modal popup (makeModal).
    var activeItemPopup: SupernovaPopupMenu? = null
    private var pendingDrag: Pair<View, DragPayload>? = null
    private var pendingDragStartX = Float.NaN
    private var pendingDragStartY = Float.NaN
    private var dragSourceView: View? = null
    private var dragFromDrawer = false
    // The window an armed press, or the live drag it became, takes its
    // touches from when that isn't this activity's: a drawer folder's card
    // is a window of its own, and the activity never sees its touches (see
    // routeItemGesture). Null for the activity's own window.
    private var pendingDragWindow: android.widget.PopupWindow? = null
    private var dragWindow: android.widget.PopupWindow? = null
    // Payload of the in-flight drag session, for the drop-target bar.
    private var activeDragPayload: DragPayload? = null

    // Own drag layer state (see beginLiveDrag). The pointer that picked the
    // item up, what the picture is over right now, the edge-hover page flip,
    // and the dropped item whose settle animation is still flying - rebinds
    // add that item hidden so it isn't seen twice (WorkspacePagerAdapter
    // asks through isSettling), and finishDrag reveals it.
    private var dragPointerId = -1
    // The finger holding an armed long-press, found on its first move (-1
    // until then), and the first finger of the gesture.
    private var pendingDragPointerId = -1
    private var downPointerId = -1
    private var dragTarget: DragTarget = DragTarget.None
    var settlingItemId: String? = null
        private set
    // The page showing its drag grid (cell-corner sparkles and target
    // outline), if any; the grid's two halves are views in that page.
    private var dragGridPage: ViewGroup? = null
    private val dragGridLayers by lazy { intArrayOf(R.id.dragSparkles, R.id.dragOutline) }
    private val dragGridRect = RectF()
    // The finger's last position, and where the hovered page sat on screen
    // when it was last hovered (see dragPageWatch).
    private var lastDragRawX = 0f
    private var lastDragRawY = 0f
    private val hoveredPageLocation = IntArray(2)
    private val pageLocationNow = IntArray(2)
    private val edgeFlipHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var edgeFlipDirection = 0
    private var edgeFlipPending: Runnable? = null
    // A page addPage just opened for a drag past the last page; the pager
    // moves there once the adapter has it.
    private var pendingDragPageFlip = -1
    private val dropZoneActions = linkedMapOf<View, (DragPayload) -> Unit>()
    private val hitLocation = IntArray(2)

    private sealed interface DragTarget {
        data object None : DragTarget
        data class Zone(val view: View) : DragTarget
        data class Dock(val slot: View, val rank: Int) : DragTarget
        data class Workspace(val canvas: ViewGroup, val page: Int) : DragTarget
    }


    // The item part of touch handling: the press that opens an item menu,
    // the long-press armed by it, the live drag that becomes, and the press
    // that closes a modal menu. Fed by this activity's window and by any
    // window an item can be long-pressed in besides it - a drawer folder's
    // card, whose touches never reach dispatchTouchEvent and arrive through
    // its touch interceptor instead. window: where the event came from, null
    // for the activity's own. An armed press and a live drag each belong to
    // the window their finger went down in, and only its events move them
    // on. True when the event was taken.
    fun routeGesture(ev: MotionEvent, window: android.widget.PopupWindow?): Boolean {
        // A second finger in another window (split touch) while one window's
        // item is held or dragged: that window's gesture keeps its press point.
        val otherWindowsGesture = (pendingDrag != null && pendingDragWindow !== window) ||
            (dragLayer.isDragging && dragWindow !== window)
        // Where the press that is about to open a menu landed. Item menus are
        // opened from a long-press callback that is handed the view but not
        // the touch, and a drawer row in list style is as wide as the screen,
        // so the menu needs this to open at the finger rather than at the far
        // edge of whatever was pressed.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !otherWindowsGesture) {
            host.onGestureDown(ev)
            pressRawX = ev.rawX
            pressRawY = ev.rawY
            downPointerId = ev.getPointerId(0)
            // A press armed with no finger on the screen (TalkBack's or a
            // keyboard's long-press) never sees its lift. A new press is past
            // it: left armed, the first move of this one would pick up that
            // item, and until then the drawer and feed pulls stayed refused.
            if (pendingDrag != null) {
                pendingDrag = null
                pendingDragWindow = null
                activeItemPopup?.makeModal()
            }
        }
        // A live drag owns the whole stream: the picture follows the finger
        // and nothing underneath sees the gesture (the hierarchy was sent a
        // cancel when the drag began).
        if (dragLayer.isDragging) {
            // Another window's drag: this one stays out of it.
            if (dragWindow !== window) return true
            val index = ev.findPointerIndex(dragPointerId).takeIf { it >= 0 } ?: 0
            // Per-pointer raw coordinates are API 29; the window offset is
            // the same for every pointer.
            val rawX = ev.getX(index) + (ev.rawX - ev.x)
            val rawY = ev.getY(index) + (ev.rawY - ev.y)
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> onLiveDragMove(rawX, rawY)
                MotionEvent.ACTION_UP -> onLiveDragRelease(rawX, rawY)
                MotionEvent.ACTION_POINTER_UP ->
                    if (ev.getPointerId(ev.actionIndex) == dragPointerId) onLiveDragRelease(rawX, rawY)
                MotionEvent.ACTION_CANCEL -> cancel()
                else -> Unit
            }
            // The drag has let go of the window it came from; that window's
            // card has been hidden since the pickup, and goes now.
            if (!dragLayer.isDragging) releaseDragWindow()
            return true
        }
        // A press reaching the activity while an item menu is open and modal
        // landed outside it - presses inside go to the menu's own window.
        // Close the menu and stop there: the window cannot be made touch-modal
        // once shown (see makeModal), so without this the same tap also
        // presses whatever is underneath, which in the drawer means an app
        // launches as the menu closes.
        //
        // Only the press is swallowed, not the rest of the stream. No view
        // ever starts a press, so the later move and lift reach a hierarchy
        // with no touch target and do nothing - while a locked desktop, which
        // makes its menu modal with the finger still down, keeps the lift that
        // ends that long-press.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            activeItemPopup?.takeIf { it.isModal }?.let { menu ->
                menu.dismiss()
                activeItemPopup = null
                return true
            }
        }
        // An armed long-press gesture (menu already showing): movement past
        // the threshold turns it into the live drag; a lift keeps the menu
        // and makes it modal. Runs before every early return - drawer items
        // arm while the drawer is expanded.
        pendingDrag?.takeIf { pendingDragWindow === window }?.let { (anchor, payload) ->
            // Measured by the finger that long-pressed the item. With a palm
            // or another finger already on the screen that isn't pointer 0,
            // and reading pointer 0 the drag never started, or jumped over
            // to the other finger and followed it.
            if (pendingDragPointerId < 0) pendingDragPointerId = pointerOn(anchor, ev)
            val index = ev.findPointerIndex(pendingDragPointerId).takeIf { it >= 0 } ?: 0
            val rawX = ev.getX(index) + (ev.rawX - ev.x)
            val rawY = ev.getY(index) + (ev.rawY - ev.y)
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (pendingDragStartX.isNaN()) {
                        pendingDragStartX = rawX
                        pendingDragStartY = rawY
                    } else if (kotlin.math.hypot(
                            (rawX - pendingDragStartX).toDouble(),
                            (rawY - pendingDragStartY).toDouble()
                        ) > context.dp(12)
                    ) {
                        pendingDrag = null
                        pendingDragWindow = null
                        // No picture to lift (an icon not found in the
                        // view): the menu stays, as after a lift.
                        if (beginLiveDrag(anchor, payload, ev, window)) {
                            activeItemPopup?.dismiss()
                            activeItemPopup = null
                        } else {
                            activeItemPopup?.makeModal()
                        }
                        return true
                    }
                    Unit
                }
                // That finger lifting is the lift, whatever else is down.
                MotionEvent.ACTION_POINTER_UP -> {
                    if (ev.getPointerId(ev.actionIndex) == pendingDragPointerId) {
                        pendingDrag = null
                        pendingDragWindow = null
                        activeItemPopup?.makeModal()
                    }
                    Unit
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pendingDrag = null
                    pendingDragWindow = null
                    activeItemPopup?.makeModal()
                }
                else -> Unit
            }
        }
        return false
    }

    // A drag taken from another window is over (or cancelled): that window,
    // hidden since the pickup, closes.
    private fun releaseDragWindow() {
        val window = dragWindow ?: return
        dragWindow = null
        window.dismiss()
    }

    // Menu first: show the menu now (non-touch-modal - see the popup), arm
    // the gesture, and let dispatchTouchEvent start the real drag only when
    // the finger actually moves. Picking up here would float a copy under a
    // resting finger. window: the window the item was pressed in, when it
    // isn't this activity's (a drawer folder's card); that window hands its
    // touches to routeItemGesture.
    fun arm(
        anchor: View,
        payload: DragPayload,
        window: android.widget.PopupWindow? = null,
        // False: the menu alone, as under a locked desktop (a drawer folder
        // with none of its apps showing has nothing to put down).
        draggable: Boolean = true,
        menu: (() -> SupernovaPopupMenu)?
    ) {
        activeItemPopup = menu?.invoke()
        // Ancestors (workspace pager, drawer list, the drawer sheet's own
        // drag-to-close) must not steal the held gesture for a scroll: before
        // the drag threshold is crossed, or at all under a locked desktop's
        // menu, which the finger would otherwise drag the drawer shut under.
        anchor.parent?.requestDisallowInterceptTouchEvent(true)
        // A locked desktop long-press is menu-only, so nothing can be moved
        // by accident.
        if (desktopLocked || !draggable) {
            activeItemPopup?.makeModal()
            return
        }
        pendingDrag = anchor to payload
        pendingDragWindow = window
        pendingDragStartX = Float.NaN
        pendingDragStartY = Float.NaN
        pendingDragPointerId = -1
    }

    // The pointer on the view, or the one nearest it: the finger that
    // pressed it, whatever order the fingers came down in.
    private fun pointerOn(view: View, ev: MotionEvent): Int {
        val rect = view.screenRect()
        val offsetX = ev.rawX - ev.x
        val offsetY = ev.rawY - ev.y
        var best = ev.getPointerId(0)
        var bestDistance = Float.MAX_VALUE
        for (i in 0 until ev.pointerCount) {
            val x = ev.getX(i) + offsetX
            val y = ev.getY(i) + offsetY
            if (rect.contains(x.toInt(), y.toInt())) return ev.getPointerId(i)
            val distance = kotlin.math.hypot(x - rect.exactCenterX(), y - rect.exactCenterY())
            if (distance < bestDistance) {
                bestDistance = distance
                best = ev.getPointerId(i)
            }
        }
        return best
    }

    // A widget long-pressed in the add sheet: arm it like an item, with the
    // preview as the picture. Its spans are the provider's defaults, the same
    // ones a tap-to-add would use.
    fun armWidget(info: AppWidgetProviderInfo, preview: View): Boolean {
        if (desktopLocked) return false
        val prefs = viewModel.state.value.prefs
        val (cellWidth, cellHeight) = host.currentCellSizePx()
        val spans = WidgetSizing.defaultSpans(context, info, cellWidth, cellHeight, prefs.gridCols, prefs.gridRows)
        arm(preview, DragPayload.NewWidget(info, spans.first, spans.second), menu = null)
        return true
    }

    // ---- Own drag layer. The picked-up item is a picture in DragLayer,
    // moved by the touch stream dispatchTouchEvent hands over, and every drop
    // target is found by hit-testing the views under the finger: the
    // drop-zone bar, the dock slots, the workspace page. Compared with the
    // system drag this gives a pickup and a settle that animate, page flips
    // from an edge hover, and drags out of the in-activity add sheet.

    private val dragLayer: DragLayer get() = binding.dragLayer

    private class DragPicture(val bitmap: Bitmap, val source: Rect?)

    // Runs inside the still-active long-press touch stream, of this window or
    // of the window the item was pressed in (routeItemGesture). False when
    // there is no picture to lift, and nothing started.
    private fun beginLiveDrag(
        anchor: View,
        payload: DragPayload,
        ev: MotionEvent,
        window: android.widget.PopupWindow? = null
    ): Boolean {
        val picture = dragPicture(anchor, payload) ?: return false
        // The last drop may still be settling: it finishes first, on its own
        // state, before this drag's takes over (its end, run later, ended
        // this drag instead, which then had no payload to drop).
        dragLayer.cancel()
        val prefs = viewModel.state.value.prefs
        dragSourceView = anchor
        dragFromDrawer = payload.isNew
        dragWindow = window
        activeDragPayload = payload
        dragPointerId = pendingDragPointerId.takeIf { ev.findPointerIndex(it) >= 0 } ?: ev.getPointerId(0)
        dragTarget = DragTarget.None
        // The picture is held where that finger pressed: the gesture's own
        // press point when it is the gesture's first finger, else where it
        // was as it started to move.
        val (grabX, grabY) = if (dragPointerId == downPointerId || pendingDragStartX.isNaN()) {
            pressRawX to pressRawY
        } else {
            pendingDragStartX to pendingDragStartY
        }
        // Release the hierarchy: the anchor drops its press, and the pager or
        // list under it never reads the movement as a scroll. The gesture
        // detector saw the same down, so it lets go too.
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        if (window != null) {
            // The gesture is that window's; this one never saw it. Its card
            // goes invisible at once but stays open, as the rest of the
            // stream only ever arrives there: the drag picture, drawn in this
            // window, shows through it.
            window.contentView?.let { content ->
                content.dispatchTouchEvent(cancel)
                content.animate().cancel()
                content.alpha = 0f
            }
            // A home folder's card is as good as closed, and its wallpaper
            // zoom goes with it. Under a drawer folder's the drawer holds
            // the zoom, and lets it go as it closes below.
            if (!host.drawerExpanded) host.animateWallpaperZoom(0f)
        } else {
            host.cancelTouchesBelow(cancel)
        }
        cancel.recycle()

        dragLayer.begin(
            picture.bitmap, grabX, grabY, picture.source,
            if (isWidgetPayload(payload)) 1f else DRAG_PICKUP_SCALE,
            Animations.duration(prefs, DRAG_PICKUP_MS)
        )
        // The picture is what is seen now; the source can vanish.
        anchor.alpha = 0f
        // A resize frame around it goes too, rather than stand empty on the
        // old cells until the drop. It is back once the drag is over
        // (onDragEnded), around the widget's new cells if the drop's rebind
        // has landed by then (onPageBound re-anchors it).
        if (payload is DragPayload.ExistingItem && host.activeResizeFrame?.itemId == payload.itemId) {
            host.activeResizeFrame?.visibility = View.INVISIBLE
        }
        showDropTargetBar()
        startIconWiggle()
        watchDragPage(true)
        // A new item leaves what it was picked from for the home screen.
        if (dragFromDrawer) host.closeItemSources()
        // Edit mode ends with any drag, as it did for a widget from its add
        // sheet: the sheet slides away and the page zooms back to full size
        // under the drag. Kept open, the zoomed page took drops a couple of
        // cells from where they were aimed, and the dock, faded out, still
        // took them too.
        host.exitEditMode()
        val index = ev.findPointerIndex(dragPointerId).takeIf { it >= 0 } ?: 0
        onLiveDragMove(ev.getX(index) + (ev.rawX - ev.x), ev.getY(index) + (ev.rawY - ev.y))
        return true
    }

    private fun itemById(id: String): LauncherItem? =
        viewModel.state.value.model.items.firstOrNull { it.id == id }

    // A widget from the add sheet or one already placed: carried at full
    // size, aimed by its top-left cell, and never given a dock slot.
    private fun isWidgetPayload(payload: DragPayload): Boolean =
        payload is DragPayload.NewWidget ||
            (payload is DragPayload.ExistingItem && itemById(payload.itemId)?.itemType == ItemType.WIDGET)

    // What the drag layer carries: an icon drawn at the size it is seen (the
    // picture starts exactly over it), a widget's own frame, or a picker
    // preview scaled to the cells it will take.
    private fun dragPicture(anchor: View, payload: DragPayload): DragPicture? = when (payload) {
        // Drawn at least at the desktop's icon size, where it is going: a
        // pill's 22dp icon, lifted at its own size, was blown up about twice
        // over, blurred, as it settled into its cell.
        is DragPayload.DrawerApp, is DragPayload.DrawerShortcut, is DragPayload.DrawerFolder -> {
            val minSize = host.desktopIconBoxPx(viewModel.state.value.prefs)
            iconPicture(dragIconView(anchor), minSize)
                // A shortcut published without an icon shows none; it is
                // lifted as its app's icon, centred on the finger.
                ?: (payload as? DragPayload.DrawerShortcut)
                    ?.let { host.installedApp(it.packageName, it.userSerial) }
                    ?.let { viewModel.appCatalog().iconFor(it) }
                    ?.let { DragPicture(it.toBitmap(minSize, minSize), null) }
        }
        is DragPayload.ExistingItem -> {
            val item = itemById(payload.itemId)
            if (item?.itemType == ItemType.WIDGET) {
                if (anchor.width > 0 && anchor.height > 0) {
                    // A snapshot ignores the host view's outline clip, so the
                    // corner radius is cut into the picture by hand.
                    val radius = context.dp(viewModel.state.value.prefs.widgetCornerRadius).toFloat()
                    runCatching { DragPicture(roundedCorners(anchor.drawToBitmap(), radius), anchor.screenRect()) }
                        .getOrNull()
                } else {
                    null
                }
            } else {
                // A folder's app is on its way out to the desktop, so it is
                // drawn at least at the desktop's icon size, as a drawer
                // icon is: a smaller folder icon blurred as it settled.
                val minSize = if (item?.container == ContainerType.FOLDER) {
                    host.desktopIconBoxPx(viewModel.state.value.prefs)
                } else {
                    0
                }
                iconPicture(anchor.findViewById(R.id.icon), minSize)
            }
        }
        is DragPayload.NewWidget -> {
            val prefs = viewModel.state.value.prefs
            val (cellWidth, cellHeight) = host.currentCellSizePx()
            val inset = context.dp(prefs.widgetPadding)
            val boxW = (cellWidth * payload.spanX - inset * 2).coerceAtLeast(context.dp(48))
            val boxH = (cellHeight * payload.spanY - inset * 2).coerceAtLeast(context.dp(48))
            val drawable = (anchor as? android.widget.ImageView)?.drawable
                ?: runCatching { context.packageManager.getApplicationIcon(payload.info.provider.packageName) }.getOrNull()
                ?: AppCompatResources.getDrawable(context, R.drawable.ic_widget)
            drawable?.let { d ->
                // Fit the preview inside the widget's cells, keeping its shape.
                val iw = d.intrinsicWidth.takeIf { it > 0 } ?: boxW
                val ih = d.intrinsicHeight.takeIf { it > 0 } ?: boxH
                val fit = minOf(boxW.toFloat() / iw, boxH.toFloat() / ih)
                val w = (iw * fit).toInt().coerceAtLeast(1)
                val h = (ih * fit).toInt().coerceAtLeast(1)
                DragPicture(d.toBitmap(w, h), null)
            }
        }
    }

    private fun roundedCorners(bitmap: Bitmap, radiusPx: Float): Bitmap {
        if (radiusPx <= 0f) return bitmap
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return out
    }

    // The icon inside a new item's view, wherever it came from: a drawer
    // tile or row, a recent-row cell (appIcon); a folder member (icon); a
    // search row (resultIcon); the top hit's icon, armed itself; a shortcut
    // pill, whose icon has no id.
    private fun dragIconView(anchor: View): android.widget.ImageView? =
        anchor.findViewById(R.id.appIcon)
            ?: anchor.findViewById(R.id.icon)
            ?: anchor.findViewById(R.id.resultIcon)
            ?: anchor as? android.widget.ImageView
            ?: (anchor as? ViewGroup)?.children?.firstNotNullOfOrNull { it as? android.widget.ImageView }

    private fun iconPicture(image: android.widget.ImageView?, minSize: Int = 0): DragPicture? {
        val drawable = image?.drawable ?: return null
        val rect = image.screenRect()
        val w = rect.width().coerceAtLeast(1)
        val h = rect.height().coerceAtLeast(1)
        val grow = (minSize.toFloat() / max(w, h)).coerceAtLeast(1f)
        return DragPicture(drawable.toBitmap((w * grow).toInt(), (h * grow).toInt()), rect)
    }

    // The page can move under a finger that holds still: the desktop drifts
    // back into place as the drawer closes behind a drag out of it, and edit
    // mode zooms out behind a widget from the add sheet. The hover reads the
    // page where it is at that moment, so without this the target (and the
    // drag grid's outline) stayed wherever the page had been mid-drift until
    // the finger moved again. Each frame, a page that moved is re-hovered.
    private val dragPageWatch = ViewTreeObserver.OnPreDrawListener {
        val target = dragTarget as? DragTarget.Workspace
        if (target != null && dragLayer.isDragging) {
            target.canvas.getLocationOnScreen(pageLocationNow)
            if (!pageLocationNow.contentEquals(hoveredPageLocation)) onLiveDragMove(lastDragRawX, lastDragRawY)
        }
        true
    }

    private fun watchDragPage(watch: Boolean) {
        val observer = binding.root.viewTreeObserver
        if (!observer.isAlive) return
        observer.removeOnPreDrawListener(dragPageWatch)
        if (watch) observer.addOnPreDrawListener(dragPageWatch)
    }

    private fun onLiveDragMove(rawX: Float, rawY: Float) {
        lastDragRawX = rawX
        lastDragRawY = rawY
        dragLayer.moveTo(rawX, rawY)
        val target = findDragTarget(rawX, rawY)
        if (target != dragTarget) {
            leaveDragTarget(dragTarget)
            dragTarget = target
            enterDragTarget(target)
        }
        if (target is DragTarget.Workspace) hoverWorkspace(target, rawX, rawY)
        trackEdgeFlip(rawX, rawY)
    }

    private fun findDragTarget(rawX: Float, rawY: Float): DragTarget {
        val x = rawX.toInt()
        val y = rawY.toInt()
        if (binding.dropTargetBar.alpha > 0f) {
            for (zone in dropZoneActions.keys) {
                if (zone.isVisible && zone.screenRect().contains(x, y)) return DragTarget.Zone(zone)
            }
        }
        // Widgets never dock, so for one the dock is no target: a release over
        // it counts as a release over nothing, and a placed widget flies back.
        val carryingWidget = activeDragPayload?.let(::isWidgetPayload) == true
        if (binding.dockBar.isVisible && !carryingWidget) {
            for (i in 0 until binding.dockBar.childCount) {
                val slot = binding.dockBar.getChildAt(i)
                val rank = slot.getTag(R.id.tag_dock_rank) as? Int ?: continue
                if (slot.screenRect().contains(x, y)) return DragTarget.Dock(slot, rank)
            }
        }
        binding.workspacePager.currentWorkspaceCanvas()?.let { canvas ->
            val page = canvas.getTag(R.id.tag_page) as? Int ?: return@let
            val rect = canvas.screenRect().apply { offset(-pageSlideX(canvas), 0) }
            if (rect.contains(x, y)) return DragTarget.Workspace(canvas, page)
        }
        return DragTarget.None
    }

    // How far the page holding this canvas is from where it will come to
    // rest: zero once the pager is still, and mid-flip the distance it has
    // yet to slide. A drop let go while an edge flip is still sliding the
    // next page in is aimed at where that page is about to be, not at
    // wherever it happens to be that frame.
    private fun pageSlideX(canvas: View): Int {
        val pager = binding.workspacePager
        if (pager.scrollState == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE) return 0
        val recycler = pager.getChildAt(0) as? ViewGroup ?: return 0
        var pageView: View = canvas
        while (pageView.parent !== recycler) pageView = pageView.parent as? View ?: return 0
        val pagerLocation = IntArray(2)
        pager.getLocationOnScreen(pagerLocation)
        pageView.getLocationOnScreen(hitLocation)
        return hitLocation[0] - pagerLocation[0]
    }

    private fun enterDragTarget(target: DragTarget) {
        if (target is DragTarget.Zone) target.view.setBackgroundColor(DROP_ZONE_HIGHLIGHT)
    }

    private fun leaveDragTarget(target: DragTarget) {
        when (target) {
            is DragTarget.Zone -> target.view.setBackgroundColor(0)
            is DragTarget.Workspace -> {
                host.cancelReorderPreview(animate = true)
                hideDragGrid(animate = true)
            }
            else -> Unit
        }
    }

    // Where on the canvas the drop is aimed: the picture's centre for an
    // icon, its top-left cell for a widget - what is seen is what lands.
    // In the canvas's own pixels, whatever it is scaled by on screen - the
    // edit-mode zoom is still easing back as a drag starts.
    private fun dropPointOnCanvas(canvas: View, payload: DragPayload): PointF? {
        val visual = dragLayer.visualRect() ?: return null
        val slide = pageSlideX(canvas)
        canvas.getLocationOnScreen(hitLocation)
        hitLocation[0] -= slide
        val scale = canvas.screenScale()
        return if (isWidgetPayload(payload)) {
            val prefs = viewModel.state.value.prefs
            val cellW = canvas.width / max(1, prefs.gridCols)
            val cellH = canvas.height / max(1, prefs.gridRows)
            PointF(
                (visual.left - hitLocation[0]) / scale.x + cellW / 2f,
                (visual.top - hitLocation[1]) / scale.y + cellH / 2f
            )
        } else {
            PointF((visual.centerX() - hitLocation[0]) / scale.x, (visual.centerY() - hitLocation[1]) / scale.y)
        }
    }

    private fun hoverWorkspace(target: DragTarget.Workspace, rawX: Float, rawY: Float) {
        val payload = activeDragPayload ?: return
        // A page mid-flip has no stable cells; the next move after it settles
        // previews again.
        if (binding.workspacePager.scrollState != androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE) return
        val point = dropPointOnCanvas(target.canvas, payload) ?: return
        target.canvas.getLocationOnScreen(hoveredPageLocation)
        val prefs = viewModel.state.value.prefs
        val p = workspacePlacement(target.canvas, prefs, point.x, point.y)
        showDragGrid(
            target.canvas, host.hoverTargetRect(payload, p[0].toInt(), p[1].toInt(), p[4], p[5])?.first, rawX, rawY
        )
        host.onDragOverWorkspace(
            payload, target.page, target.canvas,
            max(1, target.canvas.width / max(1, prefs.gridCols)),
            max(1, target.canvas.height / max(1, prefs.gridRows)),
            p[0].toInt(), p[1].toInt(), p[4], p[5]
        )
    }

    // The drag grid on the hovered page: sparkles on the cell corners
    // around the finger, and an outline on the cells the drop would take
    // (`target`, in cells). The grid lives in the page, around the canvas, so
    // it slides away with the page on a flip.
    private fun showDragGrid(canvas: ViewGroup, target: WorkspaceGeometry.CellRect?, rawX: Float, rawY: Float) {
        val page = canvas.parent as? ViewGroup ?: return hideDragGrid(animate = true)
        if (page !== dragGridPage) hideDragGrid(animate = true)
        dragGridPage = page
        val prefs = viewModel.state.value.prefs
        // The grid views fill the page, so page coordinates are theirs.
        page.getLocationOnScreen(hitLocation)
        dragGridRect.set(
            canvas.left.toFloat(), canvas.top.toFloat(), canvas.right.toFloat(), canvas.bottom.toFloat()
        )
        val slideMs = Animations.duration(prefs, DRAG_GRID_SLIDE_MS)
        val fadeMs = Animations.duration(prefs, DRAG_GRID_FADE_MS)
        for (id in dragGridLayers) {
            page.findViewById<com.supernova.launcher.ui.DragGridView>(id)?.update(
                prefs.gridCols, prefs.gridRows, dragGridRect,
                rawX - hitLocation[0], rawY - hitLocation[1], target, slideMs, fadeMs
            )
        }
    }

    private fun hideDragGrid(animate: Boolean) {
        val page = dragGridPage ?: return
        dragGridPage = null
        val fadeMs = if (animate) Animations.duration(viewModel.state.value.prefs, DRAG_GRID_FADE_MS) else 0L
        for (id in dragGridLayers) {
            page.findViewById<com.supernova.launcher.ui.DragGridView>(id)?.dismiss(fadeMs)
        }
    }

    // Edge hover: a finger held in the band along either side of the
    // workspace flips a page after a short pause, and keeps flipping while it
    // stays. Past the last page (not looping) a fresh page opens.
    private fun trackEdgeFlip(rawX: Float, rawY: Float) {
        val pagerRect = binding.workspacePager.screenRect()
        val edge = context.dp(EDGE_FLIP_DP)
        val direction = when {
            rawY < pagerRect.top || rawY > pagerRect.bottom -> 0
            rawX <= pagerRect.left + edge -> -1
            rawX >= pagerRect.right - edge -> 1
            else -> 0
        }
        if (direction == edgeFlipDirection) return
        cancelEdgeFlip()
        edgeFlipDirection = direction
        if (direction != 0) scheduleEdgeFlip(EDGE_FLIP_DELAY_MS)
    }

    private fun scheduleEdgeFlip(delayMs: Long) {
        val run = object : Runnable {
            override fun run() {
                edgeFlipPending = null
                if (flipPageForDrag(edgeFlipDirection)) {
                    edgeFlipPending = this
                    edgeFlipHandler.postDelayed(this, EDGE_FLIP_REPEAT_MS)
                }
            }
        }
        edgeFlipPending = run
        edgeFlipHandler.postDelayed(run, delayMs)
    }

    private fun cancelEdgeFlip() {
        edgeFlipPending?.let(edgeFlipHandler::removeCallbacks)
        edgeFlipPending = null
        edgeFlipDirection = 0
    }

    private fun flipPageForDrag(direction: Int): Boolean {
        if (direction == 0) return false
        val pager = binding.workspacePager
        val next = pager.currentItem + direction
        // Edit mode's add-page slot is not a page to flip onto: off the end
        // of the real pages a fresh one is asked for instead.
        if (next in 0 until host.workspaceAdapter.itemCount && !host.workspaceAdapter.isAddPage(next)) {
            host.cancelReorderPreview(animate = false)
            hideDragGrid(animate = true)
            pager.setCurrentItem(next, true)
            return true
        }
        // Off the end. The new page is asked for once; the model emission
        // carries the drag over (observeState) and the hover then stops.
        // At the page limit addPage adds nothing, and the request, never
        // answered, used to block every later one until a restart.
        if (direction > 0 && pendingDragPageFlip < 0 && activeDragPayload != null &&
            host.workspaceAdapter.realPageCount < LauncherRepository.MAX_PAGES
        ) {
            pendingDragPageFlip = host.workspaceAdapter.realPageCount
            viewModel.addPage()
        }
        return false
    }

    private fun onLiveDragRelease(rawX: Float, rawY: Float) {
        cancelEdgeFlip()
        watchDragPage(false)
        hideDropTargetBar()
        hideDragGrid(animate = true)
        stopIconWiggle()
        val payload = activeDragPayload ?: return cancel()
        val prefs = viewModel.state.value.prefs
        val settleMs = Animations.duration(prefs, DRAG_SETTLE_MS)
        val target = findDragTarget(rawX, rawY)
        // Leaving a workspace target here would wipe the reorder preview the
        // drop is about to commit; onDropToWorkspace clears it itself.
        if (dragTarget is DragTarget.Zone) leaveDragTarget(dragTarget)
        dragTarget = DragTarget.None

        when (target) {
            is DragTarget.Zone -> {
                // A preview the drag had started on the page must not go on
                // to shift the neighbours once the item is gone.
                host.cancelReorderPreview(animate = true)
                target.view.setBackgroundColor(0)
                dropZoneActions[target.view]?.invoke(payload)
                val rect = target.view.screenRect()
                dragLayer.consume(rect.exactCenterX(), rect.exactCenterY(), settleMs) { finishDrag(accepted = true) }
                return
            }
            // Widgets never dock; findDragTarget already keeps them off it.
            is DragTarget.Dock -> if (!isWidgetPayload(payload) && !desktopLocked) {
                host.cancelReorderPreview(animate = false)
                if (host.handleDropToDock(payload, target.rank)) {
                    markSettling(payload)
                    dragLayer.settle(dockIconRect(target.slot, prefs), settleMs) { finishDrag(accepted = true) }
                    return
                }
            }
            is DragTarget.Workspace -> if (!desktopLocked) {
                val point = dropPointOnCanvas(target.canvas, payload)
                if (point != null) {
                    val p = workspacePlacement(target.canvas, prefs, point.x, point.y)
                    val accepted = host.onDropToWorkspace(
                        payload, target.page, p[0].toInt(), p[1].toInt(), p[2], p[3], p[4], p[5]
                    )
                    if (accepted) {
                        markSettling(payload)
                        dragLayer.settle(workspaceSettleRect(target.canvas, payload, p, prefs), settleMs) {
                            finishDrag(accepted = true)
                        }
                        return
                    }
                }
            }
            DragTarget.None -> Unit
        }
        host.cancelReorderPreview(animate = true)
        // Nowhere to land: an item flies back to where it came from; a drawer
        // app or a picker widget has no home on screen and just fades. An app
        // taken out of a folder goes back into the folder's icon, the card it
        // was lifted from having gone at the pickup, and fades if that icon
        // isn't in view.
        dragSourceView = currentDragSource(payload)
        val folderId = (payload as? DragPayload.ExistingItem)?.let { itemById(it.itemId) }
            ?.takeIf { it.container == ContainerType.FOLDER }?.folderId
        val source = if (folderId != null) viewsOfItem(folderId).firstOrNull(::isOnScreen) else dragSourceView
        val home = source?.takeIf { it.isShown && !dragFromDrawer && payload !is DragPayload.NewWidget }
            ?.let { sourceRect(it, payload) }
        if (home != null) {
            dragLayer.settle(RectF(home), settleMs) { finishDrag(accepted = false, flewBack = true) }
        } else {
            dragLayer.consume(rawX, rawY, settleMs) { finishDrag(accepted = false) }
        }
    }

    // The rect the picture was lifted from, for the fly-back.
    private fun sourceRect(anchor: View, payload: DragPayload): Rect = when {
        payload is DragPayload.ExistingItem && itemById(payload.itemId)?.itemType == ItemType.WIDGET -> anchor.screenRect()
        else -> anchor.findViewById<View>(R.id.icon)?.let { it.screenRect() } ?: anchor.screenRect()
    }

    private fun markSettling(payload: DragPayload) {
        settlingItemId = (payload as? DragPayload.ExistingItem)?.itemId
    }

    // An item still settling from its drop, or still being dragged: a
    // rebind that lands meanwhile adds its view hidden. Mid-drag one does
    // land when edit mode closes with infinite scroll on (every page is
    // rebuilt), and it put a copy of the dragged item back at its origin.
    fun isSettling(itemId: String): Boolean =
        itemId == settlingItemId || (activeDragPayload as? DragPayload.ExistingItem)?.itemId == itemId

    // The dragged item's view as it is now. A rebind mid-drag replaces it,
    // and the old view, detached, never shows again: a missed drop faded at
    // the finger instead of flying home, and the new view, added hidden,
    // stayed hidden.
    private fun currentDragSource(payload: DragPayload?): View? {
        val source = dragSourceView
        if (source == null || source.isAttachedToWindow) return source
        val itemId = (payload as? DragPayload.ExistingItem)?.itemId ?: return source
        val matches = viewsOfItem(itemId)
        return matches.firstOrNull(::isOnScreen) ?: matches.firstOrNull() ?: source
    }

    // The views an item is drawn in, on the pager's live pages and in the
    // dock. With infinite scroll a page is bound more than once, so there
    // can be two; isOnScreen tells the one in view.
    private fun viewsOfItem(itemId: String): List<View> {
        val matches = mutableListOf<View>()
        forEachWorkspaceIcon { _, view -> if (view.getTag(R.id.tag_item_id) == itemId) matches += view }
        for (i in 0 until binding.dockBar.childCount) {
            val slot = binding.dockBar.getChildAt(i) as? ViewGroup ?: continue
            for (c in 0 until slot.childCount) {
                val child = slot.getChildAt(c)
                if (child.getTag(R.id.tag_item_id) == itemId) matches += child
            }
        }
        return matches
    }

    private val onScreenRect = Rect()

    private fun isOnScreen(view: View): Boolean = view.getGlobalVisibleRect(onScreenRect)

    // Where the dropped item will sit, on screen: worked out in the canvas's
    // own pixels, then placed through the canvas's zoom and any page slide
    // still to come, as the drop point was.
    private fun workspaceSettleRect(canvas: View, payload: DragPayload, p: FloatArray, prefs: LauncherPrefs): RectF {
        val local = settleRectOnCanvas(canvas, payload, p, prefs)
        val slide = pageSlideX(canvas)
        canvas.getLocationOnScreen(hitLocation)
        val scale = canvas.screenScale()
        val originX = (hitLocation[0] - slide).toFloat()
        val originY = hitLocation[1].toFloat()
        return RectF(
            originX + local.left * scale.x, originY + local.top * scale.y,
            originX + local.right * scale.x, originY + local.bottom * scale.y
        )
    }

    // Where the settle flies to: the icon's box where the canvas will lay it
    // out, or the widget's cells inset by the widget padding.
    private fun settleRectOnCanvas(canvas: View, payload: DragPayload, p: FloatArray, prefs: LauncherPrefs): RectF {
        val cellW = canvas.width / max(1, prefs.gridCols).toFloat()
        val cellH = canvas.height / max(1, prefs.gridRows).toFloat()
        val widget = when (payload) {
            is DragPayload.NewWidget -> payload.spanX to payload.spanY
            is DragPayload.ExistingItem -> itemById(payload.itemId)
                ?.takeIf { it.itemType == ItemType.WIDGET }?.let { it.spanX to it.spanY }
            is DragPayload.DrawerApp, is DragPayload.DrawerShortcut, is DragPayload.DrawerFolder -> null
        }
        if (widget != null) {
            // A placed widget keeps its own padding; a new one has the setting's.
            val paddingDp = (payload as? DragPayload.ExistingItem)?.let { itemById(it.itemId) }
                ?.let(viewModel::widgetPaddingDp) ?: prefs.widgetPadding
            val inset = context.dp(paddingDp)
            val cellX = clampAnchor(p[0].toInt(), widget.first, prefs.gridCols)
            val cellY = clampAnchor(p[1].toInt(), widget.second, prefs.gridRows)
            val left = cellX * cellW + inset
            val top = cellY * cellH + inset
            return RectF(
                left, top,
                left + widget.first * cellW - inset * 2, top + widget.second * cellH - inset * 2
            )
        }
        // The canvas centres the item on its snapped point and pulls it back
        // in where it would hang over an edge (WorkspaceCanvas), which a big
        // icon's item, taller or wider than a cell, does in the outer rows
        // and columns; the icon sits at the top of the item.
        val box = host.desktopIconBoxPx(prefs)
        val item = desktopItemFrame(prefs, box)
        val left = iconOrigin(p[2], canvas.width, item.width) + item.iconLeft
        val top = iconOrigin(p[3], canvas.height, item.height) + item.iconTop
        return RectF(left.toFloat(), top.toFloat(), (left + box).toFloat(), (top + box).toFloat())
    }

    // A desktop item's size, and where its icon box sits inside it.
    private class ItemFrame(val width: Int, val height: Int, val iconLeft: Int, val iconTop: Int)

    // The frame of the item a drop is about to lay out. An item moved on the
    // desktop brings its own view, laid out as the drop will lay it out
    // again, label and all. Anything else is built from the parts: the icon
    // box over one line of label (twice labelLift, about 16dp at the default
    // size), padded all round, so the icon sits labelLift above the item's
    // centre.
    private fun desktopItemFrame(prefs: LauncherPrefs, box: Int): ItemFrame {
        val source = dragSourceView?.takeIf { it.parent is WorkspaceCanvas && it.height > 0 }
        val icon = source?.findViewById<View>(R.id.icon)
        if (source != null && icon != null) return ItemFrame(source.width, source.height, icon.left, icon.top)
        val pad = context.dp(ICON_ITEM_PADDING_DP)
        val labelLift = if (prefs.showLabels) context.dp(8) else 0
        val content = max(box, host.desktopIconBasePx())
        return ItemFrame(content + pad * 2, box + labelLift * 2 + pad * 2, pad + (content - box) / 2, pad)
    }

    private fun dockIconRect(slot: View, prefs: LauncherPrefs): RectF {
        val rect = slot.screenRect()
        val box = host.dockIconBoxPx(prefs).toFloat()
        val left = rect.exactCenterX() - box / 2f
        val top = rect.top + host.dockIconTopInsetPx().toFloat()
        return RectF(left, top, left + box, top + box)
    }

    // Home button, an incoming call, onStop: the picture disappears and the
    // source comes back where it was.
    fun cancel() {
        // A card the drag came from, invisible since the pickup, would stand
        // over everything, taking every touch.
        releaseDragWindow()
        cancelEdgeFlip()
        watchDragPage(false)
        hideDropTargetBar()
        hideDragGrid(animate = false)
        stopIconWiggle()
        leaveDragTarget(dragTarget)
        dragTarget = DragTarget.None
        dragLayer.cancel()
        finishDrag(accepted = false)
    }

    private fun finishDrag(accepted: Boolean, flewBack: Boolean = false) {
        val dragged = activeDragPayload != null
        onDragEnded(accepted, flewBack)
        revealSettled()
        if (dragged) host.onDragFinished()
    }

    // The settled item's rebound view (workspace child or dock slot child)
    // was added hidden; show it now that the picture has arrived.
    private fun revealSettled() {
        val id = settlingItemId ?: return
        settlingItemId = null
        forEachWorkspaceIcon { _, view -> if (view.getTag(R.id.tag_item_id) == id) view.alpha = 1f }
        for (i in 0 until binding.dockBar.childCount) {
            val slot = binding.dockBar.getChildAt(i) as? ViewGroup ?: continue
            for (c in 0 until slot.childCount) {
                val child = slot.getChildAt(c)
                if (child.getTag(R.id.tag_item_id) == id) child.alpha = 1f
            }
        }
    }

    // Adaptive icon animations: the workspace rocks gently while a drag
    // is live, so the grid reads as editable. Each icon is offset a little so
    // the page doesn't sway as one block.
    private val wiggleAnimators = mutableListOf<android.animation.Animator>()

    private fun startIconWiggle() {
        val prefs = viewModel.state.value.prefs
        if (!prefs.iconWiggle || !Animations.enabled(prefs)) return
        stopIconWiggle()
        val duration = Animations.duration(prefs, WIGGLE_MS)
        if (duration == 0L) return
        forEachWorkspaceIcon { index, view ->
            val animator = android.animation.ObjectAnimator.ofFloat(
                view, "rotation", -WIGGLE_DEGREES, WIGGLE_DEGREES
            ).apply {
                this.duration = duration
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
                startDelay = (index % 3) * (duration / 3)
            }
            animator.start()
            wiggleAnimators += animator
        }
    }

    private fun stopIconWiggle() {
        if (wiggleAnimators.isEmpty()) return
        wiggleAnimators.forEach { it.cancel() }
        wiggleAnimators.clear()
        forEachWorkspaceIcon { _, view -> view.rotation = 0f }
    }

    // Walks the pager's live pages; the dragged item is already alpha 0, and a
    // page recycled mid-drag simply never joins in. A widget resize frame is
    // not an item and is left out, so it doesn't rock along with the items,
    // out of step with its own widget.
    fun forEachWorkspaceIcon(action: (Int, View) -> Unit) {
        val pagerList = binding.workspacePager.getChildAt(0) as? ViewGroup ?: return
        var index = 0
        for (p in 0 until pagerList.childCount) {
            val canvas = pagerList.getChildAt(p)
                .findViewById<ViewGroup>(R.id.workspaceCanvas) ?: continue
            for (c in 0 until canvas.childCount) {
                val child = canvas.getChildAt(c)
                if (child !is WidgetResizeFrame) action(index++, child)
            }
        }
    }

    // Runs once the picture has settled (or been cancelled). flewBack: it had
    // nowhere to land and has settled back over its source.
    private fun onDragEnded(accepted: Boolean, flewBack: Boolean) {
        val source = currentDragSource(activeDragPayload)
        activeDragPayload = null
        dragPointerId = -1
        // A page asked for during the drag no longer has a drag to carry.
        pendingDragPageFlip = -1
        val prefs = viewModel.state.value.prefs
        dragSourceView = null
        dragFromDrawer = false
        // A fly-back's picture lands exactly over the source, so the source is
        // simply there as the picture goes: fading in from nothing would leave
        // its cells empty for a frame. Wherever else the picture went (a cancel
        // drops it where the finger is), the source fades in.
        fun restore(view: View) {
            if (Animations.enabled(prefs) && !flewBack) {
                view.animate().alpha(1f).setDuration(Animations.duration(prefs, 150L)).setInterpolator(null).start()
            } else {
                view.alpha = 1f
            }
        }
        // A resize frame hidden with its widget (beginLiveDrag) comes back as
        // the widget does: at once after a drop, whose settle reveals the
        // widget (revealSettled), else from nothing through the widget's own
        // restore, so the two stay in step (at once after a fly-back, fading
        // after a cancel). If the widget has left the page, the frame is down
        // already (a page flip, Remove) or comes down with the drop's rebind.
        host.activeResizeFrame?.takeIf { it.visibility != View.VISIBLE }?.let { frame ->
            frame.visibility = View.VISIBLE
            if (!accepted) {
                frame.alpha = 0f
                restore(frame)
            }
        }
        source ?: return
        if (accepted) {
            // An accepted drop's model write lands a beat after the settle, so
            // restoring now would flash the icon at its old cell first. The
            // rebind re-adds views at full alpha; this delayed restore only
            // matters for drops the repository ends up bouncing.
            // Not under a drag that has picked the same view up again since.
            source.postDelayed({ if (dragSourceView !== source) restore(source) }, 250)
        } else {
            restore(source)
        }
    }

    // --- Drop-target bar (D5, restored from ef4b918 behind an opt-in
    // toggle). The bar lives in the layout VISIBLE at alpha 0 and fades in
    // once a drag is live; the drag layer hit-tests its zones while it shows.

    private fun setupDropTargets() {
        dropZoneActions[binding.dropRemove] = { payload ->
            if (payload is DragPayload.ExistingItem && !desktopLocked) {
                itemById(payload.itemId)?.let(host::removeItemWithUndo)
            }
        }
        dropZoneActions[binding.dropAppInfo] = { payload ->
            dragPayloadApp(payload)?.let { app ->
                viewModel.appCatalog().openAppInfo(app.packageName, app.className, app.userSerial)
            }
        }
        dropZoneActions[binding.dropUninstall] = { payload ->
            dragPayloadApp(payload)?.let { app ->
                context.startActivity(viewModel.appCatalog().uninstallIntent(app.packageName, app.userSerial))
            }
        }
    }

    // The app behind a drag payload, when there is one (APP/SHORTCUT items,
    // drawer apps and shortcuts; folders and widgets resolve to null).
    private fun dragPayloadApp(payload: DragPayload): LaunchableApp? = when (payload) {
        is DragPayload.DrawerApp -> payload.app
        is DragPayload.DrawerShortcut -> host.installedApp(payload.packageName, payload.userSerial)
        is DragPayload.DrawerFolder -> null
        is DragPayload.ExistingItem -> viewModel.state.value.model.items
            .firstOrNull { it.id == payload.itemId }
            ?.takeIf { it.itemType == ItemType.APP || it.itemType == ItemType.SHORTCUT }
            ?.toLaunchableApp()
        is DragPayload.NewWidget -> null
    }

    private fun isUninstallable(packageName: String): Boolean =
        runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            info.flags and ApplicationInfo.FLAG_SYSTEM == 0 ||
                info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        }.getOrDefault(false)

    private fun showDropTargetBar() {
        val prefs = viewModel.state.value.prefs
        if (!prefs.dropTargets) return
        val payload = activeDragPayload ?: return
        // An unplaced widget can't be removed, inspected or uninstalled.
        if (payload is DragPayload.NewWidget) return
        val app = dragPayloadApp(payload)
        // Nor can a drawer folder: the bar would fade in empty.
        if (app == null && payload !is DragPayload.ExistingItem) return
        binding.dropRemove.visibility =
            if (payload is DragPayload.ExistingItem) View.VISIBLE else View.GONE
        binding.dropAppInfo.visibility = if (app != null) View.VISIBLE else View.GONE
        binding.dropUninstall.visibility =
            if (app != null && isUninstallable(app.packageName)) View.VISIBLE else View.GONE
        binding.dropTargetBar.animate().cancel()
        if (Animations.enabled(prefs)) {
            binding.dropTargetBar.animate().alpha(1f).setDuration(150).start()
        } else {
            binding.dropTargetBar.alpha = 1f
        }
    }

    private fun hideDropTargetBar() {
        if (binding.dropTargetBar.alpha == 0f) return
        binding.dropTargetBar.animate().cancel()
        if (Animations.enabled(viewModel.state.value.prefs)) {
            binding.dropTargetBar.animate().alpha(0f).setDuration(150).start()
        } else {
            binding.dropTargetBar.alpha = 0f
        }
    }

    // Last, after every field above has its value: the class body runs in
    // textual order, and the drop-zone map is filled here.
    init {
        setupDropTargets()
    }

    companion object {
        // Drag wiggle: a couple of degrees is enough to read as motion without
        // making the grid look broken.
        private const val WIGGLE_DEGREES = 2.5f
        private const val WIGGLE_MS = 140L

        // Drop-zone hover tint (accent-free so it reads on any wallpaper).
        private const val DROP_ZONE_HIGHLIGHT = 0x33FFFFFF

        // Own drag layer: how much an icon grows in the hand, how long the
        // pickup and the settle take, and the edge band that flips pages
        // after a short hover (then keeps flipping while the finger stays).
        private const val DRAG_PICKUP_SCALE = 1.15f
        private const val DRAG_PICKUP_MS = 120L
        private const val DRAG_SETTLE_MS = 220L
        private const val DRAG_GRID_FADE_MS = 150L
        private const val DRAG_GRID_SLIDE_MS = 120L
        private const val EDGE_FLIP_DP = 22
        private const val EDGE_FLIP_DELAY_MS = 450L
        private const val EDGE_FLIP_REPEAT_MS = 750L

        private const val ICON_ITEM_PADDING_DP = 4
    }
}

internal fun clampAnchor(cell: Int, span: Int, count: Int): Int =
        cell.coerceIn(0, (count - span).coerceAtLeast(0))

