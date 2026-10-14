package com.supernova.launcher.ui

import android.app.Activity
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.SizeF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.supernova.launcher.R
import com.supernova.launcher.data.LauncherItem
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.LauncherRepository
import com.supernova.launcher.data.WorkspaceGeometry
import com.supernova.launcher.databinding.ActivityLauncherBinding
import com.supernova.launcher.widget.SpanBounds
import com.supernova.launcher.widget.SupernovaWidgetHost
import com.supernova.launcher.widget.SupernovaWidgetHostView
import com.supernova.launcher.widget.WidgetResizeFrame
import com.supernova.launcher.widget.WidgetSizing
import kotlinx.coroutines.launch

// Everything that hosts an app widget: the AppWidgetHost and its host views
// (workspace items, feed cards, the search bar's stand-in), the add flow
// (picker, bind, configure, finalize, and the pending widget that survives a
// recreate), the resize frame, per-widget padding, and the two touch
// trackers that keep a resize handle drag and a widget's own scrolling out
// of the home gestures. The activity keeps the menus and the model writes it
// already had; Host is what this class asks of it.
class WidgetController(
    private val activity: AppCompatActivity,
    private val binding: ActivityLauncherBinding,
    private val viewModel: LauncherViewModel,
    private val host: Host
) {

    interface Host {
        val workspaceAdapter: WorkspacePagerAdapter

        // An open feed pane covers the resize frame; a press there is the pane's.
        val feedOpen: Boolean
        fun currentCellSizePx(): Pair<Int, Int>

        // The home gesture detector saw a down that a widget has now claimed.
        fun cancelGesture(cancel: MotionEvent)

        // A workspace widget's long-press: the item menu, armed for a drag.
        fun onWidgetLongPress(view: View, item: LauncherItem)
        fun onSearchBarWidgetLongPress(anchor: View)

        // The picker's shortcut rows (create, action, activities).
        fun pickShortcutTarget(target: ShortcutTarget)
    }

    private val appWidgetManager: AppWidgetManager = AppWidgetManager.getInstance(activity)
    private val appWidgetHost = SupernovaWidgetHost(activity, SupernovaWidgetHost.HOST_ID)

    fun startListening() {
        // The system hands back every widget's pending update in one reply,
        // which a big collection widget can push past the binder's limit;
        // the host rethrows that as a RuntimeException, a crash on every
        // return home. Listening is set up before the reply fails, and each
        // widget fills in on bind anyway, so it is let slide, as Launcher3
        // does.
        runCatching { appWidgetHost.startListening() }
            .onFailure { android.util.Log.w("Supernova", "Widget host startListening failed", it) }
    }

    fun stopListening() = appWidgetHost.stopListening()

    // A configuration change relays every widget its size again.
    fun clearReportedSizes() = lastReportedSizes.clear()

    // A widget's provider label, for the export-PNG row.
    fun label(widgetId: Int): String? =
        runCatching { appWidgetManager.getAppWidgetInfo(widgetId)?.loadLabel(activity.packageManager)?.toString() }.getOrNull()

    // A widget item is leaving the model: its host id goes with it (widgets
    // are exempt from undo), and so do its cached view and size.
    fun onWidgetItemRemoved(item: LauncherItem) {
        // Its resize frame goes now rather than at the rebind: after a drop
        // on Remove the widget stays hidden, and a write landing after the
        // drag's end would bring the frame back around nothing.
        if (activeResizeFrame?.itemId == item.id) dismissResizeFrame()
        item.appWidgetId?.let(appWidgetHost::deleteAppWidgetId)
        item.appWidgetId?.let(lastReportedSizes::remove)
        item.appWidgetId?.let(widgetHostViews::remove)
    }

    // One host view per card widget, reused on every bind. The feed rebinds
    // its cards as it opens and on every media, calendar or contacts change,
    // and a fresh view each time blanked and reloaded the widget, scrolling
    // a list widget back to its top on every track change.
    fun cardWidgetView(appWidgetId: Int): View? {
        val info = appWidgetManager.getAppWidgetInfo(appWidgetId)
        return if (info == null) {
            cardWidgetViews.remove(appWidgetId)
            null
        } else {
            cardWidgetViews[appWidgetId]
                ?: createWidgetHostView(appWidgetId, info)?.also { cardWidgetViews[appWidgetId] = it }
        }
    }

    // A pending widget is bound for one of two destinations: a workspace cell
    // or a custom feed card.
    sealed interface Target {
        data class Workspace(
            val page: Int,
            val cellX: Int,
            val cellY: Int,
            // Neighbours the drop previewed aside (a drag from the add sheet);
            // applied when the widget finally lands.
            val displace: List<WorkspaceGeometry.ReorderMove> = emptyList()
        ) : Target
        data class Card(val cardId: String) : Target
        // Replace: the widget stands in for the desktop search bar.
        data object SearchBar : Target
    }

    private data class PendingWidget(
        val widgetId: Int,
        val provider: ComponentName,
        val target: Target
    )

    // Kept in the view model: a dark-mode, font-size or language change
    // recreates the activity while a widget's bind or setup screen is up,
    // and the answer then reached a new activity that had lost the widget -
    // it said the add was interrupted and deleted the id.
    private var pendingWidget: PendingWidget?
        get() = viewModel.pendingWidgetAdd as? PendingWidget
        set(value) {
            viewModel.pendingWidgetAdd = value
        }

    private val lastReportedSizes = mutableMapOf<Int, SizeF>()
    // Feed and search cards' featured widgets, by widget id.
    private val cardWidgetViews = mutableMapOf<Int, View>()

    // Live workspace widget views, reused across page rebinds: recreating an
    // AppWidgetHostView leaves it blank until the provider redelivers its
    // RemoteViews, which flashed the whole screen after every drop.
    private val widgetHostViews = mutableMapOf<Int, AppWidgetHostView>()

    // Touch stream currently owned by a widget's own scrolling content, plus
    // the widget the finger went down on while it is still undecided.
    private var scrollingWidget: SupernovaWidgetHostView? = null
    private var touchedWidget: SupernovaWidgetHostView? = null
    private val widgetHitRect = Rect()
    private val widgetHitLocation = IntArray(2)


    // The widget resize frame that is up, always on the canvas holding its
    // widget (onPageBound), and hidden while that widget is being dragged
    // (beginLiveDrag); every way down goes through dismissResizeFrame.
    var activeResizeFrame: WidgetResizeFrame? = null
        private set
    // From a press on that frame's handle to its lift. See trackResizeDrag.
    private var resizeDragActive = false
    private var hostedSearchWidgetId: Int = 0

    private val bindWidgetLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = pendingWidget
        if (pending == null) {
            // Process was recreated mid-flow: abort deterministically.
            deleteWidgetIdFromResult(result.data)
            Toast.makeText(activity, R.string.widget_add_interrupted, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        if (result.resultCode == Activity.RESULT_OK) {
            val info = appWidgetManager.getAppWidgetInfo(pending.widgetId)
            if (info == null) {
                abortPendingWidget()
                Toast.makeText(activity, R.string.widget_provider_missing, Toast.LENGTH_SHORT).show()
            } else {
                proceedToConfigure(info)
            }
        } else {
            abortPendingWidget()
        }
    }

    private val configureWidgetLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleConfigureResult(result.resultCode, result.data)
    }

    // Reclaims host ids leaked by any interrupted add flow or by uninstall-driven
    // item removal; deleting an already-deleted id is a no-op.
    fun sweepOrphanIds() {
        activity.lifecycleScope.launch {
            val referenced = viewModel.persistedWidgetIds() ?: return@launch
            appWidgetHost.appWidgetIds
                .filter { it !in referenced && it != pendingWidget?.widgetId }
                .forEach {
                    appWidgetHost.deleteAppWidgetId(it)
                    widgetHostViews.remove(it)
                    cardWidgetViews.remove(it)
                }
        }
    }

    // True from a press on the resize frame's handle until its lift. The
    // frame's own state can't answer in time: the press reaches the trackers
    // here before super.dispatchTouchEvent hands it to the frame, so the
    // handle is hit-tested up front. An open feed pane covers the frame, and
    // a press there is the pane's.
    fun trackResizeDrag(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizeDragActive = !host.feedOpen && activeResizeFrame?.hasHandleAt(ev.rawX, ev.rawY) == true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (resizeDragActive) {
                resizeDragActive = false
                return true
            }
        }
        return resizeDragActive
    }

    // True while the widget under the finger is handling this gesture itself.
    // Two signals, because they arrive at different times: content that can
    // already scroll is known at the down, while a stack or flipper only
    // claims the stream once the drag passes its own slop.
    fun trackWidgetScroll(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchedWidget = widgetHostUnder(ev.rawX, ev.rawY)
                scrollingWidget = touchedWidget?.takeIf { it.hasScrollableContent() }
            }
            MotionEvent.ACTION_MOVE -> if (scrollingWidget == null) {
                touchedWidget?.takeIf { it.contentOwnsTouch }?.let {
                    scrollingWidget = it
                    // The detector already saw the down and the moves before
                    // the takeover; cancel so that half-read swipe can't fire.
                    val cancel = MotionEvent.obtain(ev)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    host.cancelGesture(cancel)
                    cancel.recycle()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val owned = scrollingWidget != null
                scrollingWidget = null
                touchedWidget = null
                return owned
            }
        }
        return scrollingWidget != null
    }

    // Widget host views are laid out inside the pager pages, so a plain
    // screen-rect test is enough - and it skips the pages parked off-screen.
    private fun widgetHostUnder(rawX: Float, rawY: Float): SupernovaWidgetHostView? {
        val x = rawX.toInt()
        val y = rawY.toInt()
        for (host in widgetHostViews.values) {
            if (host !is SupernovaWidgetHostView || !host.isShown) continue
            host.getLocationOnScreen(widgetHitLocation)
            widgetHitRect.set(
                widgetHitLocation[0],
                widgetHitLocation[1],
                widgetHitLocation[0] + host.width,
                widgetHitLocation[1] + host.height
            )
            if (widgetHitRect.contains(x, y)) return host
        }
        return null
    }

    // Inflates the Replace widget into the bar's slot, or clears it. Returns
    // the hosted view, or null when the built-in bar should show.
    fun bindSearchBarWidget(prefs: LauncherPrefs): View? {
        val slot = binding.desktopSearchWidget
        val id = prefs.searchBarWidgetId
        if (id == 0) {
            if (slot.childCount > 0) slot.removeAllViews()
            hostedSearchWidgetId = 0
            return null
        }
        if (hostedSearchWidgetId == id && slot.childCount > 0) return slot.getChildAt(0)
        val info = appWidgetManager.getAppWidgetInfo(id)
        if (info == null) {
            // The provider went away with an uninstall; fall back rather than
            // leaving an empty slot.
            slot.removeAllViews()
            hostedSearchWidgetId = 0
            viewModel.setSearchBarWidget(0, "")
            Toast.makeText(activity, R.string.search_bar_widget_gone, Toast.LENGTH_SHORT).show()
            return null
        }
        slot.removeAllViews()
        // Guarded like every other widget view: a binder failure shows the
        // built-in bar this time rather than crashing.
        val view = runCatching { appWidgetHost.createView(activity.applicationContext, id, info) }.getOrNull()
        if (view == null) {
            hostedSearchWidgetId = 0
            return null
        }
        view.setOnLongClickListener { host.onSearchBarWidgetLongPress(slot); true }
        slot.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        hostedSearchWidgetId = id
        return view
    }

    // Only widgets that declare themselves search boxes are listed;
    // anything else in that slot would be the wrong shape.
    fun showSearchWidgetPicker() {
        val prefs = viewModel.state.value.prefs
        val (cellWidth, cellHeight) = host.currentCellSizePx()
        WidgetPickerSheet.show(
            activity, cellWidth, cellHeight, prefs.gridCols, prefs.gridRows,
            filter = { info ->
                info.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_SEARCHBOX != 0
            },
            prefs = prefs,
            onPicked = { info -> bind(info, Target.SearchBar) }
        )
    }

    fun restoreBuiltInSearchBar() {
        val id = viewModel.state.value.prefs.searchBarWidgetId
        if (id != 0) appWidgetHost.deleteAppWidgetId(id)
        viewModel.setSearchBarWidget(0, "")
    }

    // Per-widget padding, as an amount rather than an on/off: the Widget
    // padding setting (0 by default) sets every
    // widget's, and this one's can differ. The widget previews the amount as
    // the bar moves; Default goes back to following the setting.
    fun showPaddingDialog(item: LauncherItem, widgetView: View) {
        val params = widgetView.layoutParams as? WorkspaceCanvas.WidgetParams
        val originalInset = params?.inset
        val row = com.supernova.launcher.databinding.ItemSettingSliderBinding.inflate(activity.layoutInflater)
        var value = viewModel.widgetPaddingDp(item)
        fun preview(dp: Int) {
            params ?: return
            params.inset = activity.dp(dp)
            widgetView.requestLayout()
        }
        fun render() {
            row.settingValue.text = activity.getString(R.string.padding_dp_value, value)
            row.settingSeek.progress = value
            preview(value)
        }
        row.settingTitle.text = item.label.ifBlank { activity.getString(R.string.widget) }
        row.settingSeek.max = LauncherRepository.MAX_WIDGET_PADDING_DP
        row.settingSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                value = progress
                render()
            }

            override fun onStartTrackingTouch(bar: android.widget.SeekBar) = Unit

            override fun onStopTrackingTouch(bar: android.widget.SeekBar) = Unit
        })
        row.stepDown.setOnClickListener {
            value = (value - 1).coerceAtLeast(0)
            render()
        }
        row.stepUp.setOnClickListener {
            value = (value + 1).coerceAtMost(LauncherRepository.MAX_WIDGET_PADDING_DP)
            render()
        }
        render()
        var committed = false
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.padding)
            .setView(row.root)
            .setPositiveButton(R.string.save) { _, _ ->
                committed = true
                viewModel.setWidgetPadding(item.id, value)
            }
            .setNeutralButton(R.string.option_default) { _, _ ->
                committed = true
                preview(viewModel.state.value.prefs.widgetPadding)
                viewModel.setWidgetPadding(item.id, null)
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                // Cancel, back or a tap outside: the preview goes back.
                if (!committed && params != null && originalInset != null) {
                    params.inset = originalInset
                    widgetView.requestLayout()
                }
            }
            .show()
            .window?.let { window ->
                // In the half of the screen the widget isn't in, over a light
                // dim, so the preview stays in view.
                val location = IntArray(2).also(widgetView::getLocationOnScreen)
                val widgetCenterY = location[1] + widgetView.height / 2
                val upperHalf = widgetCenterY < activity.resources.displayMetrics.heightPixels / 2
                window.setGravity(if (upperHalf) Gravity.BOTTOM else Gravity.TOP)
                window.setDimAmount(PREVIEW_DIALOG_DIM)
            }
    }

    fun spanBounds(item: LauncherItem): SpanBounds {
        val prefs = viewModel.state.value.prefs
        val (cellWidth, cellHeight) = host.currentCellSizePx()
        val info = item.appWidgetId?.let(appWidgetManager::getAppWidgetInfo)
        val defaults = info
            ?.let { WidgetSizing.defaultSpans(activity, it, cellWidth, cellHeight, prefs.gridCols, prefs.gridRows) }
            ?: (item.spanX to item.spanY)
        return WidgetSizing.spanBounds(
            info, defaults.first, defaults.second, cellWidth, cellHeight, prefs.gridCols, prefs.gridRows
        )
    }

    // Overlays a draggable resize frame on top of the widget's host view; the
    // frame snaps to whole cells and commits the span on release. It stays up
    // for the next drag, through the commit's rebind (onPageBound), until a
    // tap on the page's empty space (onCanvasTap), a page change or a home
    // press takes it down.
    fun showResizeFrame(item: LauncherItem, widgetView: View) {
        dismissResizeFrame()
        val frame = WidgetResizeFrame(
            context = activity,
            itemId = item.id,
            // Growth stops at the first neighbouring item, not just the grid edge.
            roomProvider = { cellX, cellY, spanX, spanY ->
                val state = viewModel.state.value
                val current = state.model.items.firstOrNull { it.id == item.id } ?: item
                WorkspaceGeometry.resizeRoom(
                    state.model, current.copy(cellX = cellX, cellY = cellY, spanX = spanX, spanY = spanY),
                    state.prefs.gridCols, state.prefs.gridRows
                )
            },
            snapDurationMs = Animations.duration(viewModel.state.value.prefs, RESIZE_SNAP_MS),
            onCommit = { cellX, cellY, spanX, spanY ->
                val current = viewModel.state.value.model.items.firstOrNull { it.id == item.id }
                if (current != null && (cellX != current.cellX || cellY != current.cellY ||
                        spanX != current.spanX || spanY != current.spanY)
                ) {
                    viewModel.resizeWidget(item.id, cellX, cellY, spanX, spanY) {
                        // Nothing was saved, so no rebind is coming: the page
                        // is rebuilt to put the widget, and the frame with it
                        // (onPageBound), back at the size it has.
                        host.workspaceAdapter.invalidatePagesWith { it.id == item.id }
                    }
                }
            }
        )
        if (anchorResizeFrame(frame, item, widgetView)) activeResizeFrame = frame
    }

    // Puts the frame around the widget's host view, on top of its canvas.
    private fun anchorResizeFrame(frame: WidgetResizeFrame, item: LauncherItem, widgetView: View): Boolean {
        val canvas = widgetView.parent as? ViewGroup ?: return false
        if (!frame.anchor(widgetView, spanBounds(item))) return false
        if (frame.parent === canvas) {
            // The bind has just added the page's items over it.
            frame.bringToFront()
        } else {
            (frame.parent as? ViewGroup)?.removeView(frame)
            canvas.addView(frame)
        }
        return true
    }

    // Every bind of a page ends here. The open resize frame follows its
    // widget: re-anchored to the widget's new view and cells when they are on
    // this canvas, and taken down when the canvas it stood on shows no widget
    // of its own any more (the widget was removed or moved off the page, or
    // the canvas now holds another page).
    fun onPageBound(canvas: ViewGroup) {
        val frame = activeResizeFrame ?: return
        val widgetView = canvas.children.firstOrNull {
            it !is WidgetResizeFrame && it.getTag(R.id.tag_item_id) == frame.itemId
        }
        val item = viewModel.state.value.model.items.firstOrNull { it.id == frame.itemId }
        if (widgetView != null && item != null && anchorResizeFrame(frame, item, widgetView)) return
        if (frame.parent === canvas) dismissResizeFrame()
    }

    // Takes the resize frame down. Returns whether one was up.
    fun dismissResizeFrame(): Boolean {
        val frame = activeResizeFrame ?: return false
        activeResizeFrame = null
        (frame.parent as? ViewGroup)?.removeView(frame)
        return true
    }

    fun reportWidgetSize(item: LauncherItem, view: View) {
        val widgetId = item.appWidgetId ?: return
        val hostView = view as? AppWidgetHostView ?: return
        if (view.width <= 0 || view.height <= 0) return
        val size = SizeF(view.width.toFloat(), view.height.toFloat())
        if (lastReportedSizes[widgetId] == size) return
        lastReportedSizes[widgetId] = size
        WidgetSizing.reportSize(hostView, view.width, view.height, activity.resources.displayMetrics.density)
    }

    // The one AppWidgetHostView factory: workspace items and feed cards both
    // host through it so the activity.applicationContext inflation constraint and the
    // corner clip apply everywhere.
    private fun createWidgetHostView(widgetId: Int, info: AppWidgetProviderInfo): AppWidgetHostView? {
        val hostView: AppWidgetHostView = runCatching {
            // Never the activity context: AppCompat installs an inflater
            // factory that rewrites ImageView into AppCompatImageView, and
            // RemoteViews then refuses reflected calls on the substituted
            // class ("can't use method with RemoteViews") - which broke every
            // Glance-based widget. The application context has no factory.
            appWidgetHost.createView(activity.applicationContext, widgetId, info)
        }.getOrNull() ?: return null
        applyWidgetCornerRadius(hostView)
        return hostView
    }

    fun buildWidgetView(item: LauncherItem): View {
        val widgetId = item.appWidgetId ?: return fallbackWidgetView(item)
        val hostView = widgetHostViews[widgetId]?.also(::applyWidgetCornerRadius)
            ?: run {
                val info = appWidgetManager.getAppWidgetInfo(widgetId) ?: return fallbackWidgetView(item)
                createWidgetHostView(widgetId, info)?.also { widgetHostViews[widgetId] = it }
                    ?: return fallbackWidgetView(item)
            }
        // Rebound every bind: the closure must see the item's current cell
        // and spans, not the ones from when the view was first created.
        hostView.setOnLongClickListener { v ->
            host.onWidgetLongPress(v, item)
            true
        }
        return hostView
    }

    // Changes propagate like widgetPadding: the pref is part of the page
    // signature, so bound widgets rebuild through here on the next emission.
    // Our host-view subclass re-asserts the clip after every RemoteViews
    // update, because API 31+ replaces a one-time outline with the
    // framework's own rounded-corner enforcement.
    private fun applyWidgetCornerRadius(hostView: View) {
        val radiusDp = viewModel.state.value.prefs.widgetCornerRadius
        if (hostView is com.supernova.launcher.widget.SupernovaWidgetHostView) {
            hostView.cornerRadiusPx = if (radiusDp > 0) activity.dp(radiusDp).toFloat() else 0f
            return
        }
        if (radiusDp > 0) {
            hostView.outlineProvider = RoundedOutlineProvider(activity.dp(radiusDp).toFloat())
            hostView.clipToOutline = true
        } else {
            hostView.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            hostView.clipToOutline = false
        }
    }

    private class RoundedOutlineProvider(private val radiusPx: Float) : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
        }
    }

    private fun fallbackWidgetView(item: LauncherItem): View {
        return View(activity).apply {
            setBackgroundResource(R.drawable.bg_widget_fallback)
            setOnLongClickListener { v ->
                host.onWidgetLongPress(v, item)
                true
            }
        }
    }


    // The popup-menu "Widgets" row: the same picker narrowed to one app.
    fun showPickerFor(packageName: String?) {
        packageName ?: return
        val prefs = viewModel.state.value.prefs
        val (cellWidth, cellHeight) = host.currentCellSizePx()
        WidgetPickerSheet.show(
            activity, cellWidth, cellHeight, prefs.gridCols, prefs.gridRows,
            filterPackage = packageName, prefs = prefs, onPicked = ::onWidgetPicked,
            onPickShortcut = host::pickShortcutTarget
        )
    }

    // A widget picked for the current page: bound at its first free area.
    fun onWidgetPicked(info: AppWidgetProviderInfo) {
        val prefs = viewModel.state.value.prefs
        val page = viewModel.state.value.currentPage
        val (cellWidth, cellHeight) = host.currentCellSizePx()
        val spans = WidgetSizing.defaultSpans(activity, info, cellWidth, cellHeight, prefs.gridCols, prefs.gridRows)

        // Widgets need a free area of their real span, checked before any id
        // is allocated so cancelling costs nothing.
        val free = viewModel.firstFreeWorkspaceCell(page, spans.first, spans.second)
        if (free == null) {
            Toast.makeText(activity, R.string.no_room_on_page, Toast.LENGTH_SHORT).show()
            return
        }
        bind(info, Target.Workspace(page, free.first, free.second))
    }

    // Widget-featured custom card: bind into the card instead of a cell.
    private fun onWidgetPickedForCard(info: AppWidgetProviderInfo, cardId: String) {
        bind(info, Target.Card(cardId))
    }

    fun bind(info: AppWidgetProviderInfo, target: Target) {
        val prefs = viewModel.state.value.prefs
        val (cellWidth, cellHeight) = host.currentCellSizePx()
        val spans = WidgetSizing.defaultSpans(activity, info, cellWidth, cellHeight, prefs.gridCols, prefs.gridRows)
        val widgetId = appWidgetHost.allocateAppWidgetId()
        pendingWidget = PendingWidget(widgetId, info.provider, target)

        val options = WidgetSizing.initialBindOptions(
            spans.first, spans.second, cellWidth, cellHeight, activity.resources.displayMetrics.density
        )
        val bound = runCatching {
            appWidgetManager.bindAppWidgetIdIfAllowed(widgetId, info.profile, info.provider, options)
        }.getOrDefault(false)

        if (bound) {
            proceedToConfigure(info)
        } else {
            bindWidgetLauncher.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
                }
            )
        }
    }

    fun showPickerForCard(cardId: String) {
        val prefs = viewModel.state.value.prefs
        val (cellWidth, cellHeight) = host.currentCellSizePx()
        WidgetPickerSheet.show(
            activity, cellWidth, cellHeight, prefs.gridCols, prefs.gridRows,
            prefs = prefs, onPicked = { info -> onWidgetPickedForCard(info, cardId) }
        )
    }

    private fun proceedToConfigure(info: AppWidgetProviderInfo) {
        val pending = pendingWidget ?: return
        if (info.configure == null) {
            finalizeWidget()
            return
        }

        // Preferred path: the host launches configure with a system permission
        // token, which also works for non-exported configure activities. Its
        // result arrives in onActivityResult (the host API predates contracts).
        val tokenLaunch = runCatching {
            appWidgetHost.startAppWidgetConfigureActivityForResult(
                activity, pending.widgetId, 0, REQUEST_CONFIGURE_WIDGET, null
            )
        }
        if (tokenLaunch.isSuccess) return

        val plainLaunch = runCatching {
            configureWidgetLauncher.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                    component = info.configure
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pending.widgetId)
                }
            )
        }
        if (plainLaunch.isSuccess) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        ) {
            finalizeWidget()
        } else {
            abortPendingWidget()
            Toast.makeText(activity, R.string.widget_configure_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // The configure screen launched with the host's token answers through the
    // activity's onActivityResult (the host API predates contracts). True
    // when the result was this flow's.
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CONFIGURE_WIDGET) return false
        handleConfigureResult(resultCode, data)
        return true
    }

    private fun handleConfigureResult(resultCode: Int, data: Intent?) {
        if (pendingWidget == null) {
            deleteWidgetIdFromResult(data)
            Toast.makeText(activity, R.string.widget_add_interrupted, Toast.LENGTH_SHORT).show()
            return
        }
        if (resultCode == Activity.RESULT_OK) {
            finalizeWidget()
        } else {
            abortPendingWidget()
        }
    }

    private fun finalizeWidget() {
        val pending = pendingWidget ?: return
        val info = appWidgetManager.getAppWidgetInfo(pending.widgetId)
        if (info == null) {
            abortPendingWidget()
            Toast.makeText(activity, R.string.widget_provider_missing, Toast.LENGTH_SHORT).show()
            return
        }
        when (val target = pending.target) {
            is Target.Workspace -> {
                val prefs = viewModel.state.value.prefs
                val (cellWidth, cellHeight) = host.currentCellSizePx()
                val spans = WidgetSizing.defaultSpans(activity, info, cellWidth, cellHeight, prefs.gridCols, prefs.gridRows)
                viewModel.addWidget(
                    page = target.page,
                    cellX = target.cellX,
                    cellY = target.cellY,
                    appWidgetId = pending.widgetId,
                    provider = info.provider?.flattenToString().orEmpty(),
                    label = info.loadLabel(activity.packageManager)?.toString().orEmpty().ifBlank { activity.getString(R.string.widget) },
                    spanX = spans.first,
                    spanY = spans.second,
                    displace = target.displace
                )
            }
            is Target.Card -> {
                viewModel.setCardWidget(
                    target.cardId, pending.widgetId, info.provider?.flattenToString().orEmpty()
                )
            }
            Target.SearchBar -> {
                // The old stand-in is dropped only once the new one is bound,
                // so a cancelled pick leaves the bar as it was.
                val previous = viewModel.state.value.prefs.searchBarWidgetId
                if (previous != 0 && previous != pending.widgetId) {
                    appWidgetHost.deleteAppWidgetId(previous)
                }
                viewModel.setSearchBarWidget(
                    pending.widgetId, info.provider?.flattenToString().orEmpty()
                )
            }
        }
        pendingWidget = null
    }

    private fun abortPendingWidget() {
        pendingWidget?.widgetId?.let(appWidgetHost::deleteAppWidgetId)
        pendingWidget = null
    }

    private fun deleteWidgetIdFromResult(data: Intent?) {
        data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            ?.takeIf { it > 0 }
            ?.let(appWidgetHost::deleteAppWidgetId)
    }

    companion object {
        private const val REQUEST_CONFIGURE_WIDGET = 4001
        // Widget resize: each snap to a new cell edge eases there.
        private const val RESIZE_SNAP_MS = 120L
        // The dim behind the padding dialog, which previews on the home screen.
        private const val PREVIEW_DIALOG_DIM = 0.15f
    }
}
