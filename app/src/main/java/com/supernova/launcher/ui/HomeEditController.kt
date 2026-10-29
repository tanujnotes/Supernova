package com.supernova.launcher.ui

import android.appwidget.AppWidgetProviderInfo
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.supernova.launcher.databinding.ActivityLauncherBinding
import com.supernova.launcher.databinding.ViewEditModeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Build

// Long-press edit mode: the page zooms out under a home-page marker,
// Wallpapers/Settings float as chips, and an add sheet peeks from the bottom
// (widgets and shortcuts per app; swipe up to browse, swipe down to leave).
// The workspace stays live so pages can still be swiped while editing, and
// past the last page an add-page slot rides along: an empty page with a "+"
// that opens a real page there and leaves edit mode on it.
class HomeEditController(
    private val activity: AppCompatActivity,
    private val binding: ActivityLauncherBinding,
    private val viewModel: LauncherViewModel,
    private val host: Host
) {
    interface Host {
        fun currentPage(): Int
        fun defaultPage(): Int
        fun setDefaultPage(page: Int)
        fun openWallpapers()
        fun openSettings()
        fun pickWidget(info: AppWidgetProviderInfo)
        // A widget long-pressed in the sheet; true picks it up for a drag.
        fun dragWidget(info: AppWidgetProviderInfo, preview: View): Boolean
        fun pickShortcut(target: ShortcutTarget)
        fun cellSizePx(): Pair<Int, Int>
        fun grid(): Pair<Int, Int>

        // The add-page slot past the last page: shown for the length of edit
        // mode; onAddPage while the pager sits on it. addPageFromSlot asks
        // for the page it stands for, and runs `then` once edit mode has
        // closed on that page.
        fun setAddPageSlot(show: Boolean)
        // Remove page, beside the marker: offered on an empty page while
        // there is another to fall back on.
        fun canRemoveCurrentPage(): Boolean
        fun removeCurrentPage()
        fun onAddPage(): Boolean
        fun addPageFromSlot(then: (() -> Unit)?)
    }

    var isOpen = false
        private set

    // Inflated on first use; the overlay carries the marker and the chips.
    private var overlay: ViewEditModeBinding? = null

    private val behavior: BottomSheetBehavior<*> =
        BottomSheetBehavior.from(binding.editWidgetSheet).apply {
            isHideable = true
            skipCollapsed = false
            peekHeight = 0
            state = BottomSheetBehavior.STATE_HIDDEN
        }

    private val adapter = WidgetPickerAdapter(
        onPickWidget = { info -> leaveFor { host.pickWidget(info) } },
        onPickShortcut = { target -> leaveFor { host.pickShortcut(target) } },
        // Edit mode stays open while the finger rests; the drag itself (in
        // the activity) closes the sheet once the widget actually moves.
        onDragWidget = { info, preview -> host.dragWidget(info, preview) }
    )

    // Edge-to-edge: the widget sheet runs under the navigation bar (enter()
    // paints the bar with the sheet's own surface), so only its list pads out.
    // The overlay's marker takes the status bar inset when edit mode opens.
    private var barInsets: androidx.core.graphics.Insets = androidx.core.graphics.Insets.NONE

    init {
        ViewCompat.setOnApplyWindowInsetsListener(binding.editWidgetSheet) { view, insets ->
            barInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val density = view.resources.displayMetrics.density
            binding.editWidgetRecycler.setPadding(
                0, 0, 0, (24 * density).toInt() + barInsets.bottom
            )
            overlay?.let(::applyOverlayInsets)
            insets
        }
        binding.editWidgetRecycler.layoutManager = LinearLayoutManager(activity)
        binding.editWidgetRecycler.adapter = adapter
        binding.editWidgetRecycler.itemAnimator = null
        binding.editWidgetSearch.doAfterTextChanged { adapter.filter(it?.toString().orEmpty()) }
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                // Swiping the sheet away leaves edit mode as a whole.
                if (newState == BottomSheetBehavior.STATE_HIDDEN && isOpen) exit()
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
        })
    }

    // A pick from the sheet lands on the current page. On the add-page slot
    // that page doesn't exist yet: it is asked for first, the way the "+"
    // asks, and the pick runs once edit mode has closed on it.
    private fun leaveFor(action: () -> Unit) {
        if (host.onAddPage()) {
            host.addPageFromSlot(action)
        } else {
            exit()
            action()
        }
    }

    private fun applyOverlayInsets(ov: ViewEditModeBinding) {
        for (view in listOf(ov.editHomeMarker, ov.editRemovePage)) {
            (view.layoutParams as FrameLayout.LayoutParams).topMargin = barInsets.top + activity.dp(8)
            view.requestLayout()
        }
    }

    fun enter() {
        if (isOpen) return
        isOpen = true
        val ov = overlay ?: ViewEditModeBinding.bind(binding.editModeStub.inflate()).also { inflated ->
            overlay = inflated
            inflated.editHomeMarker.setOnClickListener {
                // The slot isn't a page to make home.
                if (host.onAddPage()) return@setOnClickListener
                host.setDefaultPage(host.currentPage())
                // Saved in the background, so the stored home page is still
                // the old one here: the marker lights for the page just set,
                // where before it stayed dim until a swipe away and back.
                showMarker(isDefault = true)
            }
            inflated.editRemovePage.setOnClickListener {
                if (!host.onAddPage() && host.canRemoveCurrentPage()) host.removeCurrentPage()
            }
            inflated.editChipWallpapers.setOnClickListener {
                exit()
                host.openWallpapers()
            }
            inflated.editChipSettings.setOnClickListener {
                exit()
                host.openSettings()
            }
        }
        applyOverlayInsets(ov)
        ov.root.visibility = View.VISIBLE
        host.setAddPageSlot(true)

        // Layout, bottom up: the sheet peeks, the chips sit just above it, and
        // the page shrinks toward the top until its bottom clears the chips.
        val rootHeight = binding.root.height
        val peek = (rootHeight * PEEK_FRACTION).toInt()
        behavior.peekHeight = peek
        val chips = ov.editChips
        (chips.layoutParams as FrameLayout.LayoutParams).bottomMargin = peek + activity.dp(12)
        chips.requestLayout()
        chips.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val chipHeight = chips.measuredHeight.takeIf { it > 0 } ?: activity.dp(100)
        refreshMarker()

        val pager = binding.workspacePager
        // The page's top edge moves down to clear the marker; its bottom must
        // clear the chips, which fixes the scale.
        val markerBottom = (ov.editHomeMarker.layoutParams as FrameLayout.LayoutParams).topMargin +
            ov.editHomeMarker.layoutParams.height
        val shift = (markerBottom + activity.dp(10) - pager.top).coerceAtLeast(0)
        val chipsTop = rootHeight - peek - activity.dp(12) - chipHeight
        val available = chipsTop - activity.dp(16) - (pager.top + shift)
        val scale = (available.toFloat() / pager.height.coerceAtLeast(1)).coerceIn(MIN_SCALE, MAX_SCALE)

        val prefs = viewModel.state.value.prefs
        val duration = Animations.duration(prefs, ZOOM_MS)
        pager.pivotX = pager.width / 2f
        pager.pivotY = 0f
        pager.animate().scaleX(scale).scaleY(scale).translationY(shift.toFloat()).setDuration(duration).start()
        binding.dockBar.animate().alpha(0f).setDuration(duration).start()
        binding.dockSwipeIndicator.animate().alpha(0f).setDuration(duration).start()
        binding.desktopSearchBar.animate().alpha(0f).setDuration(duration).start()
        binding.pageIndicator.animate().alpha(0f).setDuration(duration).start()

        // The sheet wears the drawer surface color, and the nav bar takes the
        // same color so the sheet reads as running under it; the window's
        // normal nav color comes back on exit. Opaque, whatever the drawer's
        // transparency: the home screen showing through the list made it
        // unreadable.
        val surface = androidx.core.graphics.ColorUtils.setAlphaComponent(
            com.supernova.launcher.ui.theme.Styles.resolve(activity, prefs)
                .of(com.supernova.launcher.ui.theme.SurfaceId.DRAWER).surface,
            OPAQUE
        )
        (binding.editWidgetSheet.background?.mutate() as? android.graphics.drawable.GradientDrawable)
            ?.setColor(surface)
        // Contrast scrim control is Android 10+; Android 9 keeps its default.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced = false
        }
        activity.window.navigationBarColor = surface
        binding.navBarBand.setBackgroundColor(surface)

        binding.editWidgetSearch.setText("")
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED

        activity.lifecycleScope.launch {
            val (cellWidth, cellHeight) = host.cellSizePx()
            val (cols, rows) = host.grid()
            val groups = withContext(Dispatchers.Default) {
                listOf(WidgetPickerGroups.launcherGroup(activity)) +
                    WidgetPickerGroups.build(activity, cellWidth, cellHeight, cols, rows)
            }
            if (isOpen) adapter.submit(groups, expandAll = false)
        }
    }

    fun exit() {
        if (!isOpen) return
        isOpen = false
        overlay?.root?.visibility = View.GONE
        host.setAddPageSlot(false)
        val prefs = viewModel.state.value.prefs
        val duration = Animations.duration(prefs, ZOOM_MS)
        binding.workspacePager.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(duration).start()
        binding.dockBar.animate().alpha(1f).setDuration(duration).start()
        binding.dockSwipeIndicator.animate().alpha(1f).setDuration(duration).start()
        binding.desktopSearchBar.animate().alpha(1f).setDuration(duration).start()
        binding.pageIndicator.animate().alpha(1f).setDuration(duration).start()
        // Mirror applySystemChrome's resting nav-bar state.
        activity.window.navigationBarColor =
            if (prefs.transparentNav) android.graphics.Color.TRANSPARENT else android.graphics.Color.BLACK
        binding.navBarBand.setBackgroundColor(android.graphics.Color.BLACK)
        // Contrast scrim control is Android 10+; Android 9 keeps its default.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced = !prefs.transparentNav
        }
        if (behavior.state != BottomSheetBehavior.STATE_HIDDEN) {
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
    }

    // The marker reflects whether the page under it is the home page.
    fun onPageChanged() {
        if (isOpen) refreshMarker()
    }

    private fun refreshMarker() {
        showMarker(!host.onAddPage() && host.currentPage() == host.defaultPage())
        overlay?.editRemovePage?.visibility =
            if (!host.onAddPage() && host.canRemoveCurrentPage()) View.VISIBLE else View.GONE
    }

    private fun showMarker(isDefault: Boolean) {
        val marker = overlay?.editHomeMarker ?: return
        marker.isActivated = isDefault
        marker.alpha = if (isDefault) 1f else 0.55f
    }


    private companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 0.84f
        const val PEEK_FRACTION = 0.22f
        const val ZOOM_MS = 220L
        const val OPAQUE = 0xFF
    }
}
