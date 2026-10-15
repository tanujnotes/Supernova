package com.supernova.launcher.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.core.view.doOnLayout
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.tabs.TabLayout
import com.supernova.launcher.R
import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.search.AppSearchProvider
import com.supernova.launcher.databinding.ActivityLauncherBinding
import com.supernova.launcher.ui.theme.Styles
import com.supernova.launcher.ui.theme.SurfaceId
import kotlin.math.roundToInt

// Owns the app drawer: sheet behavior, adapter, tabs, style, search, and the
// fast scroller. LauncherActivity supplies item behavior (launch, long-press)
// and forwards state emissions.
class DrawerController(
    private val binding: ActivityLauncherBinding,
    private val viewModel: LauncherViewModel,
    private val decorateIcon: (Drawable?, LaunchableApp) -> Drawable?,
    private val onAppClick: (View?, LaunchableApp) -> Unit,
    private val onAppLongPress: (View, LaunchableApp) -> Unit,
    private val onWebSearch: (String) -> Unit,
    // Empty-state "Search with" marks hand off to the same opener the overlay
    // uses, so a drawer search and a global one end up in the same place.
    private val onChipSearch: (com.supernova.launcher.search.SearchChip, String) -> Unit,
    private val onGlobalSearch: () -> Unit,
    // FOLDER-type groups: icon composition and tap handling live in the
    // activity, which owns FolderPopup and the icon pipeline.
    private val buildFolderIcon: (List<LaunchableApp>) -> Drawable,
    // A drawer folder's badge (its members' summed count), applied at bind
    // so a count moving needs no new composition.
    private val decorateFolderIcon: (Drawable, List<LaunchableApp>) -> Drawable = { icon, _ -> icon },
    private val onFolderClick: (View, DrawerGroup, List<LaunchableApp>) -> Unit,
    // A folder long-pressed: its menu, and a drag to the home screen.
    private val onFolderLongPress: (View, DrawerGroup, List<LaunchableApp>) -> Unit,
    // Activated custom cards above the list (the drawer cards); null
    // leaves the slot empty.
    private val cards: CardHost? = null
) {

    // The activity owns activation and the card renderer; the drawer only
    // asks which cards are activated and for a view per card, and reports
    // shown/hidden so the condition monitor runs no longer than needed.
    interface CardHost {
        fun onShown()

        fun onHidden()

        fun activatedCards(): List<com.supernova.launcher.feed.FeedCard>

        fun cardView(parent: ViewGroup, card: com.supernova.launcher.feed.FeedCard): View
    }

    // Tab identity: null = All (or Personal when the work tab is on).
    private object WorkTab

    val adapter = newCellAdapter()

    private fun newCellAdapter(): AppDrawerAdapter = AppDrawerAdapter(
        appCatalog = viewModel.appCatalog(),
        decorateIcon = decorateIcon,
        decorateFolderIcon = { icon, folder -> decorateFolderIcon(icon, folder.members) },
        onClick = { view, app -> onAppClick(view, app) },
        onLongPress = onAppLongPress,
        onWebSearch = { onWebSearch(binding.drawerSearch.text?.toString().orEmpty().trim()) },
        onChip = { chip, query -> onChipSearch(chip, query) },
        onFolderClick = { view, folder -> onFolderClick(view, folder.group, folder.members) },
        onFolderLongPress = { view, folder -> onFolderLongPress(view, folder.group, folder.members) }
    )

    // The search field's background; mutated in place by applyStyle so the
    // config's radius/fill survive re-styles.
    private val searchPill = GradientDrawable()

    private val behavior: BottomSheetBehavior<LinearLayout> =
        BottomSheetBehavior.from(binding.drawerSheet).apply {
            isHideable = true
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_HIDDEN
            peekHeight = 0
        }

    // Fires on the settled sheet states, covering drag-dismiss as well as
    // programmatic open/hide; the activity drives the wallpaper zoom from it.
    var onOpenStateChanged: ((Boolean) -> Unit)? = null

    private var populated = false
    private var needsRefresh = false
    // A badge moved while the sheet was closed; the next open rebinds rows.
    private var badgesStale = false
    private var suppressFilterEvents = false
    // Mirrors the IME inset so a drag-dismiss knows whether there was a
    // keyboard to take down with the sheet.
    private var imeVisible = false
    // Set when a drag took the keyboard away; the sheet settling back open
    // (rather than dismissing) puts it back.
    private var imeSuspended = false
    private var transition = DrawerTransition.SLIDE_UP
    // Where the open choreography last drew everything (0 = closed, 1 =
    // open); the pull, the slides and the sheet's own drag all feed it. See
    // DrawerOpenCurve.
    private var progress = 0f
    private var progressAnimator: android.animation.ValueAnimator? = null
    // Last progress the desktop and scrim were drawn at, so a park from an
    // already-closed state leaves views something else may be animating alone.
    private var homeProgress = -1f
    // ZOOM/FADE park the sheet at STATE_EXPANDED and toggle its visibility
    // instead of sliding, so the settle we trigger to re-park must not read
    // as the drawer opening.
    private var suppressExpandCallback = false
    private var selectedTab: Any? = null
    private var renderedTabsKey: String? = null
    private var renderedDefaultTab: String? = null
    private var appliedStyle: List<Any?>? = null
    private var currentStyle = DrawerStyle.VERTICAL_GRID
    // The drawer's search is the search window. With the search controller
    // attached, a query swaps the drawer's list for the shared results view
    // (the top hit, tiles with the match in bold, "Search with") and the
    // field completes inline; an empty field brings the list back. Without
    // it the drawer falls back to filtering its own list.
    var externalSearch: SearchController.DrawerSearch? = null
    private var searchingExternally = false
    private var tabsHiddenForSearch = false
    // searchBarConfig.drawer: field visibility and the global-on-tap reroute.
    private var searchFieldVisible = true
    private var searchGlobalOnTap = false
    // BOTTOM placement: the IME would cover the field (the window never
    // resizes), so an inset listener lifts the row above the keyboard.
    private var searchAtBottom = false

    // A horizontal fling on the list switches to the neighbouring group tab
    // (not for the paged grid, which uses horizontal scroll for its pages).
    private val tabSwipeDetector by lazy {
        GestureDetector(binding.root.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                if (!tabSwipeable()) return false
                if (kotlin.math.abs(velocityX) < kotlin.math.abs(velocityY) * 1.5f) return false
                if (kotlin.math.abs(velocityX) < TAB_FLING_MIN_VELOCITY) return false
                // Swipe left -> next tab, swipe right -> previous.
                selectAdjacentTab(if (velocityX < 0) 1 else -1)
                return false
            }
        })
    }

    private fun tabSwipeable(): Boolean =
        currentStyle != DrawerStyle.PAGED_GRID &&
            !viewModel.state.value.prefs.drawerTabsNoSwipe &&
            binding.drawerTabs.visibility == View.VISIBLE && binding.drawerTabs.tabCount >= 2

    private fun attachTabSwipe() {
        val slop = android.view.ViewConfiguration.get(binding.root.context).scaledTouchSlop
        binding.drawerRecycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var downX = 0f
            private var downY = 0f

            // Watching only, so vertical scrolling is untouched, until the
            // gesture reads as a sideways swipe. Then it is taken: the row
            // under the finger gets a cancel and drops its press, where
            // before its click fired on the same lift and the swipe that
            // switched tabs also opened the app it crossed.
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                tabSwipeDetector.onTouchEvent(e)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!tabSwipeable()) return false
                        val dx = kotlin.math.abs(e.x - downX)
                        val dy = kotlin.math.abs(e.y - downY)
                        if (dx > slop && dx > dy * 1.5f) return true
                    }
                }
                return false
            }

            // The rest of a taken swipe, to its fling.
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                tabSwipeDetector.onTouchEvent(e)
            }
        })
    }

    private fun selectAdjacentTab(delta: Int) {
        val tabs = binding.drawerTabs
        val current = (0 until tabs.tabCount).firstOrNull { tabs.getTabAt(it)?.isSelected == true } ?: return
        val next = (current + delta).coerceIn(0, tabs.tabCount - 1)
        if (next != current) tabs.getTabAt(next)?.select()
    }

    // drawerPullToSearch pref: dragging on past the end of the list opens the
    // global search overlay. Observe-only like the tab swipe: the anchor is
    // taken the moment the list stops being able to scroll further, so only
    // the genuine overscroll distance counts.
    private var pullToSearch = false

    private fun attachPullToSearch() {
        binding.drawerRecycler.addOnItemTouchListener(newPullToSearchListener())
    }

    // Built per list: the paged style's pages are their own RecyclerViews, and
    // each needs its own anchor state.
    private fun newPullToSearchListener(): RecyclerView.OnItemTouchListener {
        val threshold = binding.root.resources.displayMetrics.density * PULL_TO_SEARCH_DP
        var anchorY = -1f
        var armed = false
        return object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        anchorY = -1f
                        armed = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!pullToSearch) return false
                        if (rv.canScrollVertically(1)) {
                            // Still scrollable content below: not an overscroll.
                            anchorY = -1f
                            armed = false
                            return false
                        }
                        if (anchorY < 0f) {
                            anchorY = e.y
                            return false
                        }
                        // Arm on the threshold but fire only on release:
                        // hiding the sheet mid-gesture fights the active drag.
                        armed = anchorY - e.y > threshold
                    }
                    MotionEvent.ACTION_UP -> {
                        val fire = armed && pullToSearch
                        anchorY = -1f
                        armed = false
                        if (fire) onGlobalSearch()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        anchorY = -1f
                        armed = false
                    }
                }
                return false
            }
        }
    }

    // The first drag on the list sends the keyboard away so
    // the whole list is readable while scrolling. Only a touch drag reaches
    // DRAGGING, so the scroll-to-top on open never triggers it.
    private fun newScrollKeyboardHider(): RecyclerView.OnScrollListener =
        object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING &&
                    binding.drawerSearch.hasFocus()
                ) {
                    hideKeyboard()
                }
            }
        }

    // Edge-to-edge: the sheet's background runs under the system bars while
    // everything inside it pads clear of them. Re-applied from applyStyle too,
    // because which edge the search row and tab bar sit on decides who carries
    // the bottom inset.
    private var barInsets: androidx.core.graphics.Insets = androidx.core.graphics.Insets.NONE

    // The activity pushes the window insets it resolves here directly. The
    // sheet's own listener below is left in place, but a listener installed
    // on the CoordinatorLayout root replaces that ViewGroup's dispatch to its
    // children, so the sheet cannot rely on being told: in landscape that
    // left the last column of icons running under a side navigation bar.
    fun setSystemInsets(insets: androidx.core.graphics.Insets) {
        if (insets == barInsets) return
        barInsets = insets
        applyInsetPadding()
        // The column count is derived from the width the sheet is left with.
        appliedStyle = null
        applyStyle(viewModel.state.value.prefs)
    }

    // Swaps the drawer's list (grid or pager, and the tab bar) for the search
    // results and back.
    private fun showSearchResults(show: Boolean) {
        if (show == searchingExternally) return
        searchingExternally = show
        binding.drawerSearchResults.visibility = if (show) View.VISIBLE else View.GONE
        applyListVisibility()
        if (show) {
            if (binding.drawerTabs.visibility == View.VISIBLE) {
                tabsHiddenForSearch = true
                binding.drawerTabs.visibility = View.GONE
            }
        } else if (tabsHiddenForSearch) {
            tabsHiddenForSearch = false
            binding.drawerTabs.visibility = View.VISIBLE
        }
    }

    // Exactly one of the three lists shows: the results while searching,
    // else the pager for the paged style, else the grid.
    private fun applyListVisibility() {
        val paged = currentStyle == DrawerStyle.PAGED_GRID
        binding.drawerRecycler.visibility =
            if (searchingExternally || paged) View.GONE else View.VISIBLE
        binding.drawerPager.visibility =
            if (!searchingExternally && paged) View.VISIBLE else View.GONE
    }

    private fun applyInsetPadding() {
        binding.drawerSheet.setPadding(barInsets.left, barInsets.top, barInsets.right, 0)
        binding.drawerSearchResults.setPadding(0, 0, 0, context.dp(18) + barInsets.bottom)
        // clipToPadding is false, so the list scrolls under the navigation bar
        // and comes to rest above it.
        binding.drawerRecycler.setPadding(0, 0, 0, context.dp(18) + barInsets.bottom)
        // A page doesn't scroll, so the paged style keeps its pages where the
        // list comes to rest, and cuts them to fit there (syncPages).
        binding.drawerPager.setPadding(0, 0, 0, context.dp(18) + barInsets.bottom)
        binding.drawerFastScroller.setPadding(0, context.dp(12), 0, context.dp(12) + barInsets.bottom)
        // A 44dp field, on the line the old 52dp one was centred on.
        binding.drawerSearchRow.setPadding(
            context.dp(16),
            context.dp(16),
            context.dp(16),
            context.dp(10) + if (searchAtBottom) barInsets.bottom else 0
        )
        val tabsAtBottom = binding.drawerSheet.indexOfChild(binding.drawerTabs) ==
            binding.drawerSheet.childCount - 1
        binding.drawerTabs.setPadding(0, 0, 0, if (tabsAtBottom) barInsets.bottom else 0)
    }

    // The recents row scrolls away with the app list rather than sitting
    // fixed above it, so the row and its hairline ride as the list's first,
    // full-width item. The paged style has no scrolling list; there they
    // stay put above the pages. Built with the controller, before init: taken
    // out of the sheet on first use instead, as it once was, that could land
    // inside the sheet's view-state restore and crash it.
    private val listHeader: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        binding.drawerSheet.removeView(binding.drawerFrequentRow)
        binding.drawerSheet.removeView(binding.drawerFrequentDivider)
        addView(binding.drawerFrequentRow)
        addView(binding.drawerFrequentDivider)
    }

    init {
        binding.drawerSearchField.background = searchPill
        binding.drawerRecycler.clipToPadding = false
        // Park once laid out: the first layout measures the sheet (column
        // counts read its width), then it waits expanded-and-GONE for the
        // first open or pull.
        binding.drawerSheet.doOnLayout {
            if (!isVisible && !isPulling && binding.drawerSheet.visibility == View.VISIBLE) parkSheet()
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.drawerSheet) { _, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            if (bars != barInsets) {
                barInsets = bars
                applyInsetPadding()
            }
            insets
        }
        binding.drawerRecycler.adapter = adapter
        // Full-list rebinds shouldn't cross-fade every tile, and a deeper
        // view cache keeps flings from re-inflating rows.
        binding.drawerRecycler.itemAnimator = null
        binding.drawerRecycler.setItemViewCacheSize(24)
        binding.drawerRecycler.addOnScrollListener(newScrollKeyboardHider())
        // Pages are cut to the pager's height (syncPages), which can change
        // after the cut; posted, so the re-cut isn't made inside a layout.
        binding.drawerPager.addOnLayoutChangeListener { pager, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            pager.post {
                val prefs = viewModel.state.value.prefs
                if (currentStyle == DrawerStyle.PAGED_GRID && pageRows(prefs) != cutRows) syncPages()
            }
        }
        // Paging is the paged style's scrolling, so it does the same.
        binding.drawerPager.registerOnPageChangeCallback(
            object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {
                    if (state == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_DRAGGING &&
                        binding.drawerSearch.hasFocus()
                    ) {
                        hideKeyboard()
                    }
                }
            }
        )
        attachTabSwipe()
        attachPullToSearch()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.drawerSearchRow) { row, insets ->
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            imeVisible = ime > 0
            row.translationY = if (searchAtBottom && ime > 0) -ime.toFloat() else 0f
            insets
        }
        binding.drawerSearchResults.addOnScrollListener(newScrollKeyboardHider())
        binding.drawerSearch.doAfterTextChanged { text ->
            if (suppressFilterEvents) return@doAfterTextChanged
            val query = text?.toString().orEmpty()
            val external = externalSearch
            if (external != null) {
                showSearchResults(query.isNotBlank())
                external.query(query)
            } else {
                adapter.filter(query)
                syncPages()
            }
            updateFastScrollerVisibility()
            updateFrequentRow()
        }
        // Global-on-tap mode makes the field non-focusable, so the tap never
        // reaches the IME; the click routes to the global overlay instead.
        binding.drawerSearch.setOnClickListener {
            if (searchGlobalOnTap) onGlobalSearch()
        }
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (suppressExpandCallback && newState == BottomSheetBehavior.STATE_EXPANDED) {
                    suppressExpandCallback = false
                    return
                }
                when (newState) {
                    // A swipe-down dismissal never routes through hide(), so
                    // the keyboard has to follow the finger from here or it
                    // outlives the sheet and sits over the workspace.
                    BottomSheetBehavior.STATE_DRAGGING -> {
                        // ime() insets only report from API 30 on, so a
                        // focused field stands in for them below that.
                        if (imeVisible || binding.drawerSearch.hasFocus()) {
                            imeSuspended = true
                            hideKeyboard()
                        }
                    }
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        // The drag settled back open instead of dismissing.
                        if (imeSuspended) {
                            imeSuspended = false
                            if (viewModel.state.value.prefs.drawerAutoKeyboard) showKeyboard()
                        }
                        setProgress(1f, translate = false)
                        onOpenStateChanged?.invoke(true)
                    }
                    BottomSheetBehavior.STATE_HIDDEN -> {
                        imeSuspended = false
                        // Also the drag-dismiss reset. Deferred to the settled
                        // state because the filter rebinds the whole list, and
                        // that used to land mid exit-animation.
                        resetSearch()
                        // A drag-dismiss is the only way the sheet reaches
                        // HIDDEN; park it again so the next open (or pull)
                        // has nothing to settle.
                        parkSheet()
                        onOpenStateChanged?.invoke(false)
                    }
                }
            }

            // The behavior's own drag-dismiss moves the sheet by layout
            // offset, so only the content, desktop and scrim follow from
            // here; everything else that moves the sheet drives progress
            // itself.
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                if (!isVisible || isPulling || progressAnimator != null) return
                val state = behavior.state
                if (state != BottomSheetBehavior.STATE_DRAGGING &&
                    state != BottomSheetBehavior.STATE_SETTLING
                ) return
                setProgress(slideOffset.coerceIn(0f, 1f), translate = false)
            }
        })
        binding.drawerSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                externalSearch?.submit() ?: adapter.firstVisibleApp()?.let { onAppClick(null, it) }
                true
            } else {
                false
            }
        }
        binding.drawerTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedTab = tab.tag
                submit()
                adapter.filter(binding.drawerSearch.text?.toString().orEmpty())
                syncPages()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        binding.drawerFastScroller.attach(binding.drawerRecycler, binding.drawerFastScrollBubble)
        binding.drawerFastScroller.sections = { adapter.fastScrollSections() }
    }

    // The in-place transitions leave the sheet at STATE_EXPANDED while it is
    // closed, so both accessors gate on the view being on screen as well.
    val isExpanded: Boolean
        get() = binding.drawerSheet.visibility == View.VISIBLE &&
            behavior.state == BottomSheetBehavior.STATE_EXPANDED

    // True while the sheet is on screen AT ALL, including drag/settle
    // transitions. Home gestures must gate on this, not isExpanded: during a
    // closing swipe the state leaves EXPANDED while the finger is still down,
    // and the swipe-down binding would fire the notification shade over a
    // half-closed drawer.
    val isVisible: Boolean
        get() = binding.drawerSheet.visibility == View.VISIBLE &&
            behavior.state != BottomSheetBehavior.STATE_HIDDEN

    fun open(focusSearch: Boolean = true) {
        val prefs = viewModel.state.value.prefs
        prepareContent(prefs)
        if (isPulling) return
        if (!isVisible) {
            // Focus (and the keyboard it may bring) waits for the settle:
            // the IME coming up mid-slide stalls the sheet's last pixels.
            val settled = { focusOnOpen(prefs, focusSearch) }
            when (transition) {
                DrawerTransition.SLIDE_UP -> slideIn(prefs, settled)
                DrawerTransition.ZOOM, DrawerTransition.FADE -> revealInPlace(prefs, settled)
            }
        }
    }

    // Everything an open needs ready before the sheet shows: cards armed,
    // list current, scroll parked. Shared by open() and the finger pull.
    private fun prepareContent(prefs: LauncherPrefs) {
        // Cards first: the host arms its condition monitor, so the activated
        // set computed by the refresh below is as fresh as it can be.
        if (!cardsShown) {
            cardsShown = true
            cards?.onShown()
        }
        // Most-used ordering shifts with every launch; those changes no longer
        // re-emit state, so refresh the ordering as the drawer opens.
        val mostUsed = DrawerSort.fromName(prefs.drawerSort) == DrawerSort.MOST_USED
        if (!populated || needsRefresh || mostUsed) {
            populated = true
            needsRefresh = false
            submit()
            // submit() diffs by app, and an app whose badge moved is the same
            // app: its row would keep the old badge until recycled.
            if (badgesStale) {
                badgesStale = false
                notifyContentChanged()
            }
        } else {
            // Launch counts move between opens without a state emission.
            updateFrequentRow()
        }
        // By default the drawer reopens at the top; the remember pref keeps the
        // previous scroll (the sheet is only hidden, never torn down).
        if (!prefs.drawerRememberPosition) {
            binding.drawerRecycler.scrollToPosition(0)
        }
    }

    // The IME halves a paged drawer's screen, so only auto-focus on
    // vertically scrolling styles - unless the user explicitly asked for the
    // keyboard. A hidden or global-on-tap field never takes focus.
    //
    // Landscape overrides even that explicit ask: the window is short enough
    // that the keyboard covers the app list entirely, and the sheet does not
    // resize under it (adjustNothing), so raising it on open means opening a
    // drawer with nothing in it. The field stays focusable - one tap still
    // brings the keyboard up.
    private fun focusOnOpen(prefs: LauncherPrefs, focusSearch: Boolean) {
        val autoKeyboard = prefs.drawerAutoKeyboard
        val wanted = autoKeyboard || (focusSearch && currentStyle != DrawerStyle.PAGED_GRID)
        if (searchFieldVisible && !searchGlobalOnTap && wanted && !isLandscape()) {
            binding.drawerSearch.requestFocus()
            // Post: the IME request needs the sheet's window focus to have
            // settled after the expand.
            if (autoKeyboard) binding.drawerSearch.post { showKeyboard() }
        }
    }

    // instant: close with no animation at all, whatever the transition and the
    // animation prefs say. For a close nobody is watching - the screen has
    // just turned off - where the next thing the user sees is the unlock.
    fun hide(instant: Boolean = false) {
        imeSuspended = false
        isPulling = false
        hideKeyboard()
        val sheet = binding.drawerSheet
        if (sheet.visibility == View.VISIBLE && behavior.state != BottomSheetBehavior.STATE_HIDDEN) {
            when (transition) {
                DrawerTransition.SLIDE_UP -> slideOut(viewModel.state.value.prefs, instant)
                DrawerTransition.ZOOM, DrawerTransition.FADE ->
                    dismissInPlace(viewModel.state.value.prefs, instant)
            }
        } else {
            // Already off screen (onNewIntent, onStop, or mid drag-dismiss,
            // whose STATE_HIDDEN callback parks and resets on its own).
            resetSearch()
        }
    }

    // ---- Finger pull (swipe-up on the workspace) --------------------------
    //
    // The sheet is parked expanded and GONE while closed, in every transition
    // mode, so a pull can put it on screen instantly at any offset: the
    // activity's swipe tracker calls beginPull once the gesture reads as a
    // swipe-up, pullTo per move, endPull on the lift.
    //
    // ZOOM and FADE have no position for a finger to hold: the swipe opens
    // the drawer outright, and pullTo/endPull stay no-ops while the
    // activity's pull swallows the rest of the stream.

    var isPulling = false
        private set

    fun beginPull() {
        if (isPulling) return
        if (transition != DrawerTransition.SLIDE_UP) {
            open()
            return
        }
        val prefs = viewModel.state.value.prefs
        isPulling = true
        prepareContent(prefs)
        val sheet = binding.drawerSheet
        sheet.animate().cancel()
        progressAnimator?.cancel()
        ensureParkedExpanded()
        sheet.alpha = 1f
        sheet.scaleX = 1f
        sheet.scaleY = 1f
        setProgress(0f)
        sheet.visibility = View.VISIBLE
    }

    // distance = how far the finger has travelled upward since the pull began.
    fun pullTo(distance: Float) {
        if (!isPulling) return
        setProgress((distance / sheetHeight()).coerceIn(0f, 1f))
    }

    // velocityY in px/s, negative = upward. A fling decides; otherwise the
    // half-way point does. A cancelled stream always settles closed.
    fun endPull(velocityY: Float, cancelled: Boolean = false) {
        if (!isPulling) return
        isPulling = false
        val prefs = viewModel.state.value.prefs
        val sheet = binding.drawerSheet
        val height = sheetHeight()
        val minFling = android.view.ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4f
        val open = when {
            cancelled -> false
            velocityY < -minFling -> true
            velocityY > minFling -> false
            else -> sheet.translationY < height / 2f
        }
        if (open) {
            // The fling settles in about a quarter second whatever is
            // left to travel, decelerating; the pref-scaled slide matches.
            animateProgress(
                1f, Animations.duration(prefs, SLIDE_MS),
                android.view.animation.DecelerateInterpolator()
            ) { focusOnOpen(prefs, focusSearch = true) }
            onOpenStateChanged?.invoke(true)
        } else {
            slideOut(prefs)
        }
    }

    // The laid-out height, or the parent's before the sheet's first layout
    // (it starts GONE, and a GONE child is never measured).
    private fun sheetHeight(): Float {
        val height = binding.drawerSheet.height
        return (if (height > 0) height else binding.root.height).coerceAtLeast(1).toFloat()
    }

    private fun ensureParkedExpanded() {
        if (behavior.state != BottomSheetBehavior.STATE_EXPANDED) {
            suppressExpandCallback = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    // ---- Open choreography ------------------------------------------------
    //
    // One progress value (0 = closed, 1 = open) drives everything an open
    // touches, on the measured open curve (DrawerOpenCurve): the sheet's
    // translation and its content's alpha for the slide and the pull, and the
    // desktop's fade-and-drift plus the wallpaper scrim in every mode. The
    // pull sets it per move, the slides animate it, and the sheet behavior's
    // own drag-dismiss feeds it from onSlide.

    private val homeViews: List<View> by lazy {
        listOf(
            binding.workspacePager, binding.dockBar, binding.pageIndicator,
            binding.dockSwipeIndicator, binding.desktopSearchBar, binding.desktopSearchWidget
        )
    }

    // translate: the sheet rides on progress (the slide and the pull); off
    // when the sheet moves on its own. content: the children below the
    // search row fade in late; off for the in-place modes, which fade the
    // whole sheet instead.
    private fun setProgress(p: Float, translate: Boolean = true, content: Boolean = true) {
        progress = p
        if (translate) binding.drawerSheet.translationY = (1f - p) * sheetHeight()
        if (content) applyContentAlpha(DrawerOpenCurve.contentAlpha(p))
        applyHome(p)
    }

    private fun animateProgress(
        to: Float,
        duration: Long,
        interpolator: android.view.animation.Interpolator,
        translate: Boolean = true,
        content: Boolean = true,
        end: (() -> Unit)? = null
    ) {
        progressAnimator?.cancel()
        val sheet = binding.drawerSheet
        if (translate) sheet.animate().cancel()
        if (duration == 0L) {
            setProgress(to, translate, content)
            end?.invoke()
            return
        }
        if (translate) {
            // The sheet itself rides a RenderThread animator, as it always
            // did: the keyboard coming up with the drawer stalls the main
            // thread for a few frames, and a main-thread animator held the
            // sheet short of the top and then dropped it there in one jump.
            // The desktop, scrim and content are cheap to catch up.
            sheet.animate().translationY((1f - to) * sheetHeight())
                .setDuration(duration)
                .setInterpolator(interpolator)
                .start()
        }
        progressAnimator = android.animation.ValueAnimator.ofFloat(progress, to).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { setProgress(it.animatedValue as Float, translate = false, content) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (progressAnimator === animation) progressAnimator = null
                    if (!cancelled) end?.invoke()
                }
            })
            start()
        }
    }

    // The desktop fades out and drifts up under the rising sheet, and a scrim
    // dims the wallpaper in step with it, so a translucent drawer opens onto
    // dimmed wallpaper rather than the home screen.
    private fun applyHome(p: Float) {
        if (p == homeProgress) return
        homeProgress = p
        val alpha = DrawerOpenCurve.homeAlpha(p)
        val shift = -DrawerOpenCurve.homeShift(p) * sheetHeight()
        for (view in homeViews) {
            view.alpha = alpha
            view.translationY = shift
        }
        val scrim = binding.drawerScrim
        scrim.alpha = DrawerOpenCurve.scrimAlpha(p)
        scrim.visibility = if (p > 0f) View.VISIBLE else View.GONE
    }

    // Everything on the sheet but the search row: the row rides the sheet's
    // top edge from the first pixel, the rest arrives once it is well up.
    private fun applyContentAlpha(alpha: Float) {
        val sheet = binding.drawerSheet
        for (i in 0 until sheet.childCount) {
            val child = sheet.getChildAt(i)
            if (child !== binding.drawerSearchRow) child.alpha = alpha
        }
    }

    // SLIDE_UP open: the parked sheet slides in along the same choreography
    // as the finger pull, under the animation speed pref.
    private fun slideIn(prefs: LauncherPrefs, onSettled: (() -> Unit)? = null) {
        val sheet = binding.drawerSheet
        sheet.animate().cancel()
        ensureParkedExpanded()
        sheet.alpha = 1f
        sheet.scaleX = 1f
        sheet.scaleY = 1f
        if (sheet.visibility != View.VISIBLE) setProgress(0f)
        sheet.visibility = View.VISIBLE
        animateProgress(
            1f, Animations.duration(prefs, SLIDE_MS),
            android.view.animation.DecelerateInterpolator(),
            end = onSettled
        )
        // The sheet never changes state here, so the callback that normally
        // announces an open never fires.
        onOpenStateChanged?.invoke(true)
    }

    private fun slideOut(prefs: LauncherPrefs, instant: Boolean = false) {
        binding.drawerSheet.animate().cancel()
        animateProgress(
            0f, if (instant) 0L else Animations.duration(prefs, SLIDE_MS),
            android.view.animation.AccelerateInterpolator()
        ) {
            parkSheet()
            resetSearch()
            onOpenStateChanged?.invoke(false)
        }
    }

    // The activity is being destroyed with the drawer showing its cards.
    fun releaseCards() {
        if (cardsShown) {
            cardsShown = false
            cards?.onHidden()
        }
    }

    // Clears the query once the sheet is off screen, so the next open starts
    // on the full list.
    private fun resetSearch() {
        if (cardsShown) {
            cardsShown = false
            cards?.onHidden()
        }
        // Only the drawer's own keyboard. The window has one IME, and when
        // the drawer closed for global search (its Search button, pull to
        // search) the overlay's field has taken it by now; hiding it for the
        // window here closed the keyboard search had just opened.
        if (binding.drawerSearch.hasFocus()) hideKeyboard()
        if (binding.drawerSearch.text.isNullOrEmpty()) return
        suppressFilterEvents = true
        binding.drawerSearch.text?.clear()
        suppressFilterEvents = false
        externalSearch?.query("")
        showSearchResults(false)
        adapter.filter("")
        syncPages()
        updateFastScrollerVisibility()
        updateFrequentRow()
    }

    // Closed-state parking, every mode: the sheet stays expanded (nothing to
    // settle on the next open, and a pull can place it anywhere instantly)
    // and leaves the screen by going GONE.
    private fun parkSheet() {
        val sheet = binding.drawerSheet
        sheet.animate().cancel()
        progressAnimator?.cancel()
        sheet.alpha = 1f
        sheet.scaleX = 1f
        sheet.scaleY = 1f
        sheet.translationY = 0f
        ensureParkedExpanded()
        sheet.visibility = View.GONE
        // Whatever path closed it: the desktop is back, the scrim is gone,
        // and the content is opaque again for a mode that shows it at once.
        progress = 0f
        applyContentAlpha(1f)
        applyHome(0f)
    }

    private fun revealInPlace(prefs: LauncherPrefs, onSettled: (() -> Unit)? = null) {
        val sheet = binding.drawerSheet
        sheet.animate().cancel()
        ensureParkedExpanded()
        sheet.translationY = 0f
        val zoom = transition == DrawerTransition.ZOOM
        // Default pivot is the view centre, which is what ZOOM wants.
        sheet.alpha = 0f
        sheet.scaleX = if (zoom) ZOOM_FROM else 1f
        sheet.scaleY = if (zoom) ZOOM_FROM else 1f
        sheet.visibility = View.VISIBLE
        val duration = Animations.duration(prefs, TRANSITION_MS)
        if (duration == 0L) {
            sheet.alpha = 1f
            sheet.scaleX = 1f
            sheet.scaleY = 1f
        } else {
            sheet.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(duration)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
        // The sheet fades in whole, so only the desktop and scrim follow.
        animateProgress(
            1f, duration, android.view.animation.DecelerateInterpolator(),
            translate = false, content = false, end = onSettled
        )
        // The sheet never changes state here, so the callback that normally
        // announces an open never fires.
        onOpenStateChanged?.invoke(true)
    }

    private fun dismissInPlace(prefs: LauncherPrefs, instant: Boolean = false) {
        val sheet = binding.drawerSheet
        sheet.animate().cancel()
        val finish = Runnable {
            parkSheet()
            resetSearch()
            onOpenStateChanged?.invoke(false)
        }
        val duration = if (instant) 0L else Animations.duration(prefs, TRANSITION_MS)
        animateProgress(
            0f, duration, android.view.animation.AccelerateInterpolator(),
            translate = false, content = false
        )
        if (duration == 0L) {
            finish.run()
        } else {
            val zoom = transition == DrawerTransition.ZOOM
            sheet.animate().alpha(0f)
                .scaleX(if (zoom) ZOOM_FROM else 1f)
                .scaleY(if (zoom) ZOOM_FROM else 1f)
                .setDuration(duration)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction(finish)
                .start()
        }
    }

    // Moves the closed sheet between the two parking schemes when the pref
    // changes; a drawer that is open keeps what it has until it closes.
    private fun applyTransition(prefs: LauncherPrefs) {
        val next = DrawerTransition.fromName(prefs.drawerTransition)
        if (next == transition) return
        val wasOpen = isVisible
        transition = next
        // Every mode parks the same way; only the open/close pictures differ.
        if (!wasOpen) parkSheet()
    }

    private fun showKeyboard() {
        if (!searchFieldVisible || searchGlobalOnTap) return
        if (!binding.drawerSearch.hasFocus()) binding.drawerSearch.requestFocus()
        binding.root.context.getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(binding.drawerSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    // Takes the keyboard down from outside - the item menu opens over it
    // otherwise. A no-op when it isn't up, so callers don't have to ask first.
    fun hideKeyboardIfShowing() {
        if (imeVisible || binding.drawerSearch.hasFocus()) hideKeyboard()
    }

    private fun hideKeyboard() {
        binding.drawerSearch.clearFocus()
        binding.root.context.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.drawerSearch.windowToken, 0)
        imeVisible = false
    }

    fun focusSearch() {
        if (searchFieldVisible && !searchGlobalOnTap) binding.drawerSearch.requestFocus()
    }

    // The private keyboard setting covers the drawer's own field too, not
    // just the search overlay's. Set on every state change, well before the
    // field can take focus, so the flag is in place when the input
    // connection starts.
    private fun applyImeOptions(prefs: LauncherPrefs) {
        val target = EditorInfo.IME_ACTION_SEARCH or
            (if (prefs.searchPrivateKeyboard) EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else 0)
        if (binding.drawerSearch.imeOptions != target) binding.drawerSearch.imeOptions = target
    }

    // Called on every state emission from the activity's collector.
    fun onState(state: LauncherUiState) {
        applyStyle(state.prefs)
        applyImeOptions(state.prefs)
        pullToSearch = state.prefs.drawerPullToSearch
        val drawerScale = iconScale(state.prefs)
        lastDisplay = Triple(drawerScale, state.prefs.drawerLabels, state.prefs)
        adapter.setDisplay(drawerScale, state.prefs.drawerLabels, state.prefs)
        rebuildTabs(state.prefs)
        val iconsChanged = state.iconGeneration != lastIconGeneration
        lastIconGeneration = state.iconGeneration
        if (populated) {
            submit()
            // The app list is unchanged when only an override moved, so DiffUtil
            // won't rebind; force it so the re-rendered icons show immediately.
            if (iconsChanged) adapter.notifyDataSetChanged()
            syncPages()
        } else {
            needsRefresh = true
        }
    }

    fun notifyContentChanged() {
        adapter.notifyDataSetChanged()
        syncPages()
        // The recents row's icons carry badges too, and its key can't see a
        // count move.
        frequentRowKey = null
        updateFrequentRow()
    }

    // A badge count moved. Drawer rows carry badges (the activity's
    // decorateIcon applies them), but a closed sheet has nothing to show: mark
    // it instead, and prepareContent rebuilds on the next open. That is the
    // case that mattered - the counts flow replays on every return to the home
    // screen, and this used to rebuild the whole list each time, while hidden.
    fun onBadgeChanged() {
        if (isVisible) {
            notifyContentChanged()
        } else {
            needsRefresh = true
            badgesStale = true
        }
    }

    private fun workTabActive(): Boolean =
        viewModel.state.value.prefs.drawerWorkTab && viewModel.hasWorkApps()

    private fun appsForTab(tab: Any?): List<LaunchableApp> = when (tab) {
        null -> {
            val all = if (workTabActive()) viewModel.personalApps() else viewModel.visibleApps()
            val isolated = viewModel.isolatedTabKeys()
            if (isolated.isEmpty()) all else all.filterNot { viewModel.hiddenKey(it) in isolated }
        }
        WorkTab -> viewModel.workApps()
        is DrawerGroup -> viewModel.appsInGroup(tab)
        else -> viewModel.visibleApps()
    }

    private fun submit() {
        val prefs = viewModel.state.value.prefs
        val tab = selectedTab
        // FOLDER-type groups render on the All (or Personal) list only; their
        // member apps leave the flat list and live inside the folder.
        val folderGroups = if (tab == null) viewModel.drawerFolderGroups() else emptyList()
        val folders = folderGroups.mapNotNull { group ->
            val members = viewModel.appsInGroup(group)
            if (members.isEmpty()) {
                null
            } else {
                AppDrawerAdapter.FolderEntry(group, buildFolderIcon(members), members)
            }
        }
        // Unless the folder keeps its apps in the main list too.
        val excluded = folderGroups.filter { viewModel.leavesMainList(it) }.flatMapTo(mutableSetOf()) { it.members }
        val apps = if (excluded.isEmpty()) {
            appsForTab(tab)
        } else {
            appsForTab(tab).filterNot {
                "${it.userSerial}/${it.packageName}/${it.className}" in excluded
            }
        }
        adapter.submit(apps, folders, prefs.drawerFoldersFirst, listOrder(prefs))
        syncPages()
        updateFrequentRow()
    }

    private fun listOrder(prefs: LauncherPrefs): AppDrawerAdapter.Order =
        when (DrawerSort.fromName(prefs.drawerSort)) {
            DrawerSort.ALPHA -> AppDrawerAdapter.Order.A_Z
            DrawerSort.ALPHA_REVERSE -> AppDrawerAdapter.Order.Z_A
            DrawerSort.INSTALL_DATE, DrawerSort.MOST_USED -> AppDrawerAdapter.Order.UNSORTED
        }

    // The most-launched strip above the list: All tab only, hidden while a
    // query filters, rebound only when its membership or the icons change.
    private var frequentRowKey: String? = null
    // Last icon generation the list was bound at; a change means per-app
    // icons moved even though the app list is identical (DiffUtil would skip).
    private var lastIconGeneration = -1

    private fun updateFrequentRow() {
        updateCards()
        val prefs = viewModel.state.value.prefs
        if (!prefs.drawerFrequentRow || selectedTab != null ||
            !binding.drawerSearch.text.isNullOrBlank()
        ) {
            setFrequentRowVisible(false)
            return
        }
        // The recents row: the recently opened apps, labelled, one per grid
        // column.
        val columns = columnCount(prefs)
        val apps = viewModel.recentApps(columns)
        if (apps.isEmpty()) {
            setFrequentRowVisible(false)
            return
        }
        setFrequentRowVisible(true)
        val textColor = Styles.resolve(context, prefs).of(SurfaceId.DRAWER).text
        // The icon box the list under it draws (AppDrawerAdapter.sizeIcon):
        // the style's base icon at the drawer's icon scale, kept inside one
        // column, so the row's icons match the list's at any size.
        val baseDp = if (currentStyle == DrawerStyle.VERTICAL_LIST) ROW_ICON_DP else TILE_ICON_DP
        val iconSizePx = (context.dp(baseDp) * iconScale(prefs)).toInt()
            .coerceAtMost((gridWidthPx() / columns - context.dp(TILE_SIDE_PADDING_DP)).coerceAtLeast(1))
        val key = apps.joinToString { "${it.userSerial}/${it.packageName}/${it.className}" } +
            "|$columns|$textColor|$lastIconGeneration|$iconSizePx|${styleKey(prefs)}"
        if (key == frequentRowKey) return
        frequentRowKey = key
        // Long-pressed, each is a drawer icon like any other: its menu, and a
        // drag to the home screen.
        val entries = apps.map { app ->
            FrequentAppsRowView.Entry(
                app.label,
                decorateIcon(viewModel.appCatalog().iconFor(app), app),
                onLongPress = { view -> onAppLongPress(view, app) }
            ) { view ->
                onAppClick(view, app)
            }
        }
        val look = FrequentAppsRowView.CellLook(iconSizePx) { label ->
            LabelStyler.apply(label, prefs, onWallpaper = false, context = LabelContext.DRAWER)
            label.visibility = if (prefs.drawerLabels) View.VISIBLE else View.GONE
        }
        binding.drawerFrequentRow.bindLabeled(entries, textColor, columns, sidePaddingDp = 0, look = look)
    }

    // The label prefs LabelStyler reads for the drawer and the badge prefs
    // decorateIcon reads, so a change to any of them rebinds the recents row.
    private fun styleKey(prefs: LauncherPrefs): String =
        listOf(
            prefs.labelSize, prefs.labelColor, prefs.labelShadow, prefs.labelFont,
            prefs.labelSingleLine, prefs.labelConfig, prefs.drawerLabels,
            prefs.badges, prefs.badgeStyle, prefs.badgeCounts, prefs.badgeApps,
            prefs.badgeColor, prefs.badgePosition, prefs.badgeScale, prefs.badgeTextColor,
            prefs.badgeCornerRadius, prefs.badgeOutline
        ).joinToString("/")

    // Whether the host has been told the drawer is showing cards.
    private var cardsShown = false
    private var cardsKey: List<String>? = null

    // Activated cards above the list: All tab only, hidden while a query
    // filters, rebuilt only when the activated set changes. The sheet's
    // scroll container is the list itself, so a long card stack is capped by
    // the frequent-row rule rather than by height.
    private fun updateCards() {
        val host = cards
        val container = binding.drawerCards
        val activated = if (host == null || !cardsShown || selectedTab != null ||
            !binding.drawerSearch.text.isNullOrBlank()
        ) {
            emptyList()
        } else {
            host.activatedCards()
        }
        if (activated.isEmpty()) {
            if (container.childCount > 0) container.removeAllViews()
            cardsKey = null
            container.visibility = View.GONE
            return
        }
        val key = activated.map { it.id + ":" + it.hashCode() }
        if (key != cardsKey || container.childCount == 0) {
            cardsKey = key
            container.removeAllViews()
            activated.forEach { card -> container.addView(host!!.cardView(container, card)) }
        }
        container.visibility = View.VISIBLE
    }

    // Condition signals or card edits changed while open.
    fun refreshCards() {
        cardsKey = null
        updateCards()
    }

    // A launch was recorded while open - most often from the drawer itself,
    // kept open behind the app. The row's key skips a rebind if its apps
    // didn't move.
    fun refreshFrequentRow() = updateFrequentRow()

    // The strip and its rule are one unit: a bare divider above the list
    // reads as a stray line.
    private fun setFrequentRowVisible(visible: Boolean) {
        val state = if (visible) View.VISIBLE else View.GONE
        binding.drawerFrequentRow.visibility = state
        binding.drawerFrequentDivider.visibility = state
        headerVisible = visible
        placeListHeader()
    }

    private var headerVisible = false

    private fun placeListHeader() {
        val header = listHeader
        val sheet = binding.drawerSheet
        if (currentStyle == DrawerStyle.PAGED_GRID) {
            adapter.setHeader(null)
            if (header.parent !== sheet) {
                (header.parent as? ViewGroup)?.removeView(header)
                sheet.addView(header, sheet.indexOfChild(binding.drawerListContainer))
            }
        } else {
            if (header.parent === sheet) sheet.removeView(header)
            adapter.setHeader(if (headerVisible) header else null)
        }
    }

    private fun rebuildTabs(prefs: LauncherPrefs) {
        val tabBarMode = DrawerTabBar.fromName(prefs.drawerTabBar)
        val groups = viewModel.drawerTabGroups()
        val workTab = workTabActive()
        val hideAll = prefs.drawerHideAllTab && groups.isNotEmpty()
        val key = "${tabBarMode.name}|${groups.joinToString { "${it.name}:${it.order}" }}|$workTab|$hideAll|${prefs.drawerDefaultTab}"
        if (key == renderedTabsKey) return
        renderedTabsKey = key
        // Read before the tabs go: selecting one below overwrites it.
        val previous = selectedTab
        // A newly picked default tab shows straight away; any other rebuild
        // (a group added, renamed or moved, the work tab coming or going)
        // keeps the tab the user is on.
        val defaultChanged = prefs.drawerDefaultTab != renderedDefaultTab
        renderedDefaultTab = prefs.drawerDefaultTab

        val tabs = binding.drawerTabs
        tabs.removeAllTabs()
        // NONE hides the bar outright; with tabs unreachable the drawer must
        // show the All list, so the selection resets too.
        if (tabBarMode == DrawerTabBar.NONE || (groups.isEmpty() && !workTab)) {
            tabs.visibility = View.GONE
            if (selectedTab != null) {
                selectedTab = null
                if (populated) submit()
            }
            return
        }

        tabs.visibility = View.VISIBLE
        // Added unselected: the one-argument addTab selects the first tab
        // it adds, and the listener then overwrote selectedTab mid-rebuild,
        // so every rebuild landed on the default tab.
        if (!hideAll) {
            tabs.addTab(tabs.newTab().setText(R.string.drawer_all).also { it.tag = null }, false)
        }
        if (workTab) {
            tabs.addTab(tabs.newTab().setText(R.string.drawer_work).also { it.tag = WorkTab }, false)
        }
        groups.forEach { group ->
            tabs.addTab(tabs.newTab().setText(group.name).also { it.tag = group }, false)
        }

        // The tab the user was on, else the default tab pref, else the first.
        fun indexOf(match: (Any?) -> Boolean): Int? =
            (0 until tabs.tabCount).firstOrNull { match(tabs.getTabAt(it)?.tag) }
        val kept = if (defaultChanged) null else when (previous) {
            is DrawerGroup -> indexOf { it is DrawerGroup && it.name == previous.name }
            WorkTab -> indexOf { it === WorkTab }
            else -> indexOf { it == null }
        }
        val defaultName = prefs.drawerDefaultTab.takeIf { it.isNotEmpty() }
        val target = kept
            ?: defaultName?.let { name -> indexOf { it is DrawerGroup && it.name == name } }
            ?: 0
        tabs.getTabAt(target)?.select()
        selectedTab = tabs.getTabAt(target)?.tag
    }

    // Reconfigures layout manager, cell sizing, background, and scrubber when
    // the drawer-affecting prefs change; guarded so emissions are cheap.
    private fun applyStyle(prefs: LauncherPrefs) {
        val key = listOf(
            prefs.drawerStyle, prefs.drawerColumns, prefs.drawerRows,
            prefs.drawerBgOpacity,
            prefs.drawerFastScroller, prefs.drawerBgColor,
            prefs.styleConfig, prefs.paletteSource, prefs.paletteSeed,
            prefs.searchBarConfig, prefs.drawerTabBar, prefs.drawerTabStyle, prefs.drawerScrubberColor,
            prefs.drawerActionButtons, prefs.drawerTransition,
            prefs.drawerInfiniteScroll, prefs.drawerPageEffect,
            // The scrubber's visibility and direction follow the sort.
            prefs.drawerSort,
            // The automatic column count and the page rows follow the icon
            // size (columnCount, syncPages).
            iconScale(prefs)
        )
        if (key == appliedStyle) return
        appliedStyle = key

        applyTransition(prefs)

        // Tab bar placement: physically reorder the sheet's children so
        // BOTTOM sits below the list; TOP restores the original slot after
        // the header row. Visibility (incl. NONE) is rebuildTabs' job.
        val sheet = binding.drawerSheet
        val tabs = binding.drawerTabs
        val wantBottom = DrawerTabBar.fromName(prefs.drawerTabBar) == DrawerTabBar.BOTTOM
        val currentIndex = sheet.indexOfChild(tabs)
        val targetIndex = if (wantBottom) sheet.childCount - 1 else 1
        if (currentIndex != targetIndex) {
            sheet.removeView(tabs)
            sheet.addView(tabs, if (wantBottom) sheet.childCount else 1)
        }

        val searchConfig = SearchBarConfig.decode(prefs.searchBarConfig).drawer
        val barPlacement = DrawerBarPlacement.fromConfig(searchConfig)
        searchFieldVisible = barPlacement != DrawerBarPlacement.HIDDEN
        searchAtBottom = barPlacement == DrawerBarPlacement.BOTTOM
        searchGlobalOnTap = searchConfig.globalOnTap
        binding.drawerSearchField.visibility = if (searchFieldVisible) View.VISIBLE else View.GONE

        // Placement: physically move the whole search row like the tab bar
        // below; BOTTOM sits after everything else in the sheet.
        val searchRow = binding.drawerSearchRow
        val rowIndex = sheet.indexOfChild(searchRow)
        val rowTarget = if (searchAtBottom) sheet.childCount - 1 else 0
        if (rowIndex != rowTarget) {
            sheet.removeView(searchRow)
            sheet.addView(searchRow, if (searchAtBottom) sheet.childCount else 0)
        }

        // Field styling (C7): filled pill vs outline, radius, start icon.
        val resolvedForBar = Styles.resolve(context, prefs).of(SurfaceId.DRAWER)
        val density = context.resources.displayMetrics.density
        searchPill.cornerRadius =
            (searchConfig.radius ?: DrawerSearchBarConfig.MAX_RADIUS).coerceIn(0, DrawerSearchBarConfig.MAX_RADIUS) * density
        if (searchConfig.filled) {
            searchPill.setColor((0x24 shl 24) or (resolvedForBar.text and 0xFFFFFF))
            searchPill.setStroke(0, android.graphics.Color.TRANSPARENT)
        } else {
            searchPill.setColor(android.graphics.Color.TRANSPARENT)
            searchPill.setStroke(
                (2 * density).toInt(),
                (0x5A shl 24) or (resolvedForBar.text and 0xFFFFFF)
            )
        }
        // The magnifier on the left is fixed, in the drawer's text colour.
        binding.drawerSearchIcon.visibility = View.VISIBLE
        binding.drawerSearchIcon.setImageResource(R.drawable.ic_search)
        binding.drawerSearchIcon.imageTintList = android.content.res.ColorStateList.valueOf(resolvedForBar.text)
        // The field sits on the drawer surface, not on a Material card, so its
        // own text has to follow the resolved drawer palette.
        binding.drawerSearch.setTextColor(resolvedForBar.text)
        binding.drawerSearchGhost.setTextColor((0x80 shl 24) or (resolvedForBar.text and 0xFFFFFF))
        binding.drawerSearchGhost.typeface = binding.drawerSearch.typeface
        binding.drawerSearch.setHintTextColor((0x8A shl 24) or (resolvedForBar.text and 0xFFFFFF))
        binding.drawerSearch.highlightColor =
            (0x40 shl 24) or (resolvedForBar.accent and 0xFFFFFF)

        // The group tabs follow the drawer palette too. The Tab type: a
        // short bar under the label (Classic) or a pill behind it (Bubble).
        tabs.setTabTextColors(withAlpha(resolvedForBar.text, TAB_TEXT_ALPHA), resolvedForBar.text)
        if (DrawerTabStyle.fromName(prefs.drawerTabStyle) == DrawerTabStyle.BUBBLE) {
            val pill = GradientDrawable().apply {
                cornerRadius = TAB_BUBBLE_RADIUS_DP * density
                setColor(android.graphics.Color.WHITE)
            }
            val inset = (TAB_BUBBLE_INSET_DP * density).toInt()
            tabs.setSelectedTabIndicator(android.graphics.drawable.InsetDrawable(pill, 0, inset, 0, inset))
            tabs.setSelectedTabIndicatorGravity(TabLayout.INDICATOR_GRAVITY_STRETCH)
            tabs.isTabIndicatorFullWidth = true
            tabs.setSelectedTabIndicatorColor(withAlpha(resolvedForBar.accent, TAB_BUBBLE_ALPHA))
        } else {
            tabs.setSelectedTabIndicator(R.drawable.tab_indicator_drawer)
            tabs.setSelectedTabIndicatorGravity(TabLayout.INDICATOR_GRAVITY_BOTTOM)
            tabs.isTabIndicatorFullWidth = false
            tabs.setSelectedTabIndicatorColor(resolvedForBar.accent)
        }

        // Header action buttons (C10): inside the field, at its right end,
        // rebuilt on every style pass; previous ones are tagged for removal.
        val field = binding.drawerSearchField
        val actionTag = "drawer-action-button"
        for (index in field.childCount - 1 downTo 0) {
            if (field.getChildAt(index).tag == actionTag) field.removeViewAt(index)
        }
        val actionButtons = DrawerActionButton.decode(prefs.drawerActionButtons)
        // Buttons carry their own touch padding, so the field's end inset shrinks.
        field.setPaddingRelative(
            field.paddingStart, field.paddingTop,
            ((if (actionButtons.isEmpty()) FIELD_END_DP else FIELD_END_WITH_BUTTONS_DP) * density).toInt(),
            field.paddingBottom
        )
        actionButtons.forEach { button ->
            val view = android.widget.ImageButton(context).apply {
                tag = actionTag
                setImageResource(
                    when (button) {
                        DrawerActionButton.PLAY_STORE -> R.drawable.ic_play_store
                        DrawerActionButton.SETTINGS -> R.drawable.ic_tab_settings
                    }
                )
                imageTintList = android.content.res.ColorStateList.valueOf(resolvedForBar.text)
                background = null
                val pad = (ACTION_BUTTON_PAD_DP * density).toInt()
                setPadding(pad, pad, pad, pad)
                contentDescription = context.getString(
                    when (button) {
                        DrawerActionButton.PLAY_STORE -> R.string.chip_play_store
                        DrawerActionButton.SETTINGS -> R.string.settings
                    }
                )
                setOnClickListener {
                    when (button) {
                        DrawerActionButton.PLAY_STORE -> runCatching {
                            context.packageManager.getLaunchIntentForPackage("com.android.vending")
                                ?.let(context::startActivity)
                        }
                        DrawerActionButton.SETTINGS -> context.startActivity(
                            android.content.Intent(context, com.supernova.launcher.SettingsActivity::class.java)
                        )
                    }
                }
            }
            val size = (ACTION_BUTTON_DP * density).toInt()
            field.addView(view, android.widget.LinearLayout.LayoutParams(size, size))
        }
        // Focusability is the pre-IME interception point: a non-focusable
        // EditText delivers a plain click and never starts an input connection.
        binding.drawerSearch.isFocusable = !searchGlobalOnTap
        binding.drawerSearch.isFocusableInTouchMode = !searchGlobalOnTap
        binding.drawerSearch.isCursorVisible = !searchGlobalOnTap

        applyInsetPadding()

        currentStyle = DrawerStyle.fromName(prefs.drawerStyle)
        val recycler = binding.drawerRecycler

        when (currentStyle) {
            DrawerStyle.VERTICAL_GRID -> {
                adapter.displayMode = AppDrawerAdapter.DisplayMode.TILE
                val columns = columnCount(prefs)
                adapter.tileWidthPx = gridWidthPx() / columns
                recycler.layoutManager = DrawerLayoutManager(context, columns).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int =
                            adapter.spanSizeAt(position, columns)
                    }
                }
            }
            DrawerStyle.VERTICAL_LIST -> {
                adapter.displayMode = AppDrawerAdapter.DisplayMode.ROW
                // One column: a list, with the grid's scrollbar arithmetic.
                recycler.layoutManager = DrawerLayoutManager(context, 1)
            }
            DrawerStyle.PAGED_GRID -> {
                adapter.displayMode = AppDrawerAdapter.DisplayMode.TILE
            }
        }
        val paged = currentStyle == DrawerStyle.PAGED_GRID
        applyListVisibility()
        placeListHeader()
        binding.drawerPager.setPageTransformer(
            if (paged) PageTransformers.forEffect(ScrollEffect.fromName(prefs.drawerPageEffect)) else null
        )
        syncPages()
        adapter.notifyDataSetChanged()

        // Mutate the shape's color rather than replacing the background, so
        // bg_drawer's 24dp top corners survive. The resolved DRAWER surface
        // reproduces the legacy drawerBgColor/drawerBgOpacity pair when no
        // style override exists.
        val resolvedDrawer = Styles.resolve(context, prefs).of(SurfaceId.DRAWER)
        // Fast scrollbar: the thumb and its letter bubble follow the accent
        // (or a fixed color from the pref, "Scroll accent color"), the
        // track the drawer text at Launcher3's 30/255.
        (binding.drawerFrequentDivider.background?.mutate() as? GradientDrawable)
            ?.setColor((0x33 shl 24) or (resolvedDrawer.text and 0xFFFFFF))
        binding.drawerFastScroller.setColors(
            thumb = LabelStyler.parseColor(prefs.drawerScrubberColor, resolvedDrawer.accent),
            track = (FAST_SCROLL_TRACK_ALPHA shl 24) or (resolvedDrawer.text and 0xFFFFFF),
            bubbleText = context.getColor(R.color.onAccent)
        )
        (binding.drawerSheet.background?.mutate() as? GradientDrawable)?.setColor(resolvedDrawer.surface)
        // Elevation shadow under a translucent sheet reads as muddy.
        binding.drawerSheet.elevation =
            if (resolvedDrawer.surface ushr 24 < 250) 0f else 12f * context.resources.displayMetrics.density

        updateFastScrollerVisibility()
    }

    // Re-applies colors after an out-of-band style invalidation (wallpaper
    // palette change) that no prefs emission announces.
    fun invalidateStyle() {
        appliedStyle = null
        applyStyle(viewModel.state.value.prefs)
    }

    // The fast scrollbar rides the list: none over search results, or over
    // the paged style's pages. A list not sorted by name keeps it, moving
    // item by item without a letter.
    private fun updateFastScrollerVisibility() {
        val prefs = viewModel.state.value.prefs
        val searchEmpty = binding.drawerSearch.text.isNullOrBlank()
        val show = prefs.drawerFastScroller && searchEmpty &&
            DrawerStyle.fromName(prefs.drawerStyle) != DrawerStyle.PAGED_GRID
        binding.drawerFastScroller.visibility = if (show) View.VISIBLE else View.GONE
        // Its track stands in for the list's own scrollbar.
        binding.drawerRecycler.isVerticalScrollBarEnabled = !show
        if (show) binding.drawerFastScroller.updateThumb()
    }

    // Width the grid actually gets: the sheet once it has been laid out,
    // else the window. In landscape the two differ by the side insets, and
    // sizing off the raw display width there hands out a column that lives
    // under the navigation bar.
    private fun gridWidthPx(): Int {
        val laidOut = binding.drawerSheet.width - binding.drawerSheet.paddingLeft -
            binding.drawerSheet.paddingRight
        if (laidOut > 0) return laidOut
        val metrics = context.resources.displayMetrics
        return (metrics.widthPixels - barInsets.left - barInsets.right).coerceAtLeast(1)
    }

    // Columns for the current window. A count the user set is a PORTRAIT
    // count: honouring it literally in landscape stretches four columns over
    // a window twice as wide, so it is scaled to keep the cell the same size
    // it has in portrait. With no count set, both orientations derive from
    // the available width and land there anyway.
    private fun columnCount(prefs: LauncherPrefs): Int {
        val metrics = context.resources.displayMetrics
        // The automatic cell grows with the icon it has to hold, and starts
        // wider on a tablet: ten 48dp icons across a 10" screen is a contact
        // sheet, not a drawer.
        val baseCellDp = if (com.supernova.launcher.data.DeviceProfile.isWide(context)) WIDE_CELL_DP else MIN_CELL_DP
        val minCellPx = (metrics.density * baseCellDp * iconScale(prefs).coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        val width = gridWidthPx()
        if (prefs.drawerColumns <= 0) return (width / minCellPx).coerceAtLeast(3)
        if (!isLandscape()) return prefs.drawerColumns
        // The portrait window is the short edge of this display, minus the
        // side insets the sheet is already clear of.
        val portraitWidth = minOf(metrics.widthPixels, metrics.heightPixels)
            .minus(barInsets.left + barInsets.right)
            .coerceAtLeast(1)
        val cell = (portraitWidth.toFloat() / prefs.drawerColumns).coerceAtLeast(1f)
        // Never below the columns asked for, even past MAX_COLUMNS.
        return (width / cell).roundToInt().coerceIn(prefs.drawerColumns, maxOf(prefs.drawerColumns, MAX_COLUMNS))
    }

    // For search's app tiles, which line up with the drawer's grid.
    fun columns(): Int = columnCount(viewModel.state.value.prefs)

    // The drawer's own icon size, or the desktop's when it follows that.
    private fun iconScale(prefs: LauncherPrefs): Float =
        if (prefs.drawerMatchDesktop) prefs.iconScale else prefs.drawerIconScale

    private fun isLandscape(): Boolean =
        context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // The window rotated or was resized under a live drawer. Column counts,
    // page cuts and inset padding are all measured against it, and none of
    // them are announced by a prefs emission.
    fun onWindowResized() {
        appliedStyle = null
        applyStyle(viewModel.state.value.prefs)
        applyInsetPadding()
        binding.drawerSheet.doOnLayout {
            appliedStyle = null
            applyStyle(viewModel.state.value.prefs)
        }
    }

    // Paged style: the flat list, cut into fixed rows x columns pages. Real
    // page views are what let the page effect and the wrap-around work; the
    // flat adapter still holds the filtered list and pages mirror it.
    private inner class DrawerPagerAdapter : RecyclerView.Adapter<PageHolder>() {
        var pages: List<List<Any>> = emptyList()
        var infinite = false
        var columns = 4

        override fun getItemCount(): Int = when {
            pages.isEmpty() -> 0
            infinite && pages.size > 1 -> pages.size * INFINITE_LOOPS
            else -> pages.size
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val recycler = RecyclerView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                layoutManager = GridLayoutManager(parent.context, columns)
                itemAnimator = null
                isNestedScrollingEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                addOnItemTouchListener(newPullToSearchListener())
                addOnScrollListener(newScrollKeyboardHider())
            }
            return PageHolder(recycler)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            // A recycled page can carry the page effect's transform from
            // another position; see WorkspacePagerAdapter.onBindViewHolder.
            PageTransformers.resetTransforms(holder.itemView)
            holder.bind(pages[position % pages.size], columns)
        }
    }

    private inner class PageHolder(private val recycler: RecyclerView) :
        RecyclerView.ViewHolder(recycler) {

        private val pageAdapter = newCellAdapter().also { recycler.adapter = it }

        fun bind(items: List<Any>, columns: Int) {
            (recycler.layoutManager as GridLayoutManager).spanCount = columns
            pageAdapter.displayMode = AppDrawerAdapter.DisplayMode.TILE
            pageAdapter.tileWidthPx = gridWidthPx() / columns
            lastDisplay?.let { pageAdapter.setDisplay(it.first, it.second, it.third) }
            pageAdapter.submitPage(items)
        }
    }

    private val pagerAdapter by lazy { DrawerPagerAdapter() }
    // The rows per page of the last cut: the pager's height can change after
    // it (the header above it fills in on the first open, the tab bar comes
    // and goes), and a pager that now fits a different count is cut again.
    private var cutRows = 0
    // The setDisplay arguments the flat adapter last got, replayed onto every
    // page adapter as it binds.
    private var lastDisplay: Triple<Float, Boolean, LauncherPrefs>? = null

    // Re-cuts the pages from the flat list. Cheap enough to call after every
    // change to that list; a no-op unless the paged style is showing.
    private fun syncPages() {
        if (currentStyle != DrawerStyle.PAGED_GRID) return
        val pager = binding.drawerPager
        if (pager.height == 0) {
            pager.doOnLayout { syncPages() }
            return
        }
        val prefs = viewModel.state.value.prefs
        val columns = columnCount(prefs)
        val rows = pageRows(prefs)
        cutRows = rows
        val perPage = (rows * columns).coerceAtLeast(1)
        val pages = adapter.items().chunked(perPage)
        // No matches means no pages: show the flat list so the empty state has
        // somewhere to render instead of leaving a blank pager.
        val empty = pages.isEmpty()
        // The search results own the container while a query is typed.
        if (!searchingExternally) {
            binding.drawerRecycler.visibility = if (empty) View.VISIBLE else View.GONE
            pager.visibility = if (empty) View.GONE else View.VISIBLE
        }
        val wasEmpty = pagerAdapter.pages.isEmpty()
        pagerAdapter.columns = columns
        pagerAdapter.infinite = prefs.drawerInfiniteScroll
        pagerAdapter.pages = pages
        if (pager.adapter !== pagerAdapter) {
            pager.adapter = pagerAdapter
        } else {
            pagerAdapter.notifyDataSetChanged()
        }
        // Wrapping works by starting deep inside a repeated list, so the first
        // page has somewhere to scroll back to.
        if (pagerAdapter.infinite && pages.size > 1 && (wasEmpty || pager.currentItem == 0)) {
            pager.setCurrentItem(pages.size * (INFINITE_LOOPS / 2), false)
        }
    }

    // Rows a page holds. 0 keeps the original derive-from-height behavior. A
    // row count the user set is, like the column count, a portrait number:
    // landscape has room for fewer rows, and honouring it there cuts pages
    // taller than the pager that silently lose their last row.
    private fun pageRows(prefs: LauncherPrefs): Int {
        val pager = binding.drawerPager
        // A row is a tile's height: at least the minimum item_drawer_app
        // declares, never less, or the count runs a row past the page. A
        // tile's icon box grows with the icon size (AppDrawerAdapter), and
        // the row with it; a smaller icon leaves the tile at its minimum.
        val iconGrowth = (context.dp(TILE_ICON_DP) * (iconScale(prefs) - 1f)).roundToInt().coerceAtLeast(0)
        val rowHeight = context.dp(TILE_MIN_HEIGHT_DP) + iconGrowth
        // The pager's bottom padding keeps the navigation bar's strip.
        val fits = ((pager.height - pager.paddingBottom) / rowHeight).coerceAtLeast(2)
        return prefs.drawerRows.takeIf { it > 0 }
            ?.let { if (isLandscape()) minOf(it, fits) else it }
            ?: fits.coerceAtLeast(3)
    }


    private val context: Context get() = binding.root.context

    private companion object {
        // Launcher3's MAX_TRACK_ALPHA.
        const val FAST_SCROLL_TRACK_ALPHA = 30
        // Narrowest a drawer cell is allowed to get when the column count is
        // derived from the width rather than set, and the ceiling on the
        // count landscape scaling may reach.
        const val MIN_CELL_DP = 72f
        const val WIDE_CELL_DP = 96f
        // The icon box and the minimum height item_drawer_app declares.
        const val TILE_ICON_DP = 48
        const val TILE_MIN_HEIGHT_DP = 98
        // The icon box item_drawer_app_row declares.
        const val ROW_ICON_DP = 40
        // The search field's end inset alone, and beside its action buttons
        // (36dp, padded), which sit inside it.
        const val FIELD_END_DP = 16
        const val FIELD_END_WITH_BUTTONS_DP = 4
        const val ACTION_BUTTON_DP = 36
        const val ACTION_BUTTON_PAD_DP = 8
        // item_drawer_app's 4dp side padding, both sides.
        const val TILE_SIDE_PADDING_DP = 8
        const val MAX_COLUMNS = 12
        const val TAB_FLING_MIN_VELOCITY = 900f
        // The Bubble tab type's pill: fully round, 6dp inside the bar's
        // height at top and bottom.
        const val TAB_BUBBLE_RADIUS_DP = 100f
        const val TAB_BUBBLE_INSET_DP = 6f
        // An unselected tab's label, and the Bubble pill's fill.
        const val TAB_TEXT_ALPHA = 0xB3
        const val TAB_BUBBLE_ALPHA = 0x3D

        fun withAlpha(color: Int, alpha: Int): Int = androidx.core.graphics.ColorUtils.setAlphaComponent(color, alpha)
        // Repeats of the real page list that stand in for a wrap-around.
        const val INFINITE_LOOPS = 200
        const val TRANSITION_MS = 200L
        const val SLIDE_MS = 240L
        const val ZOOM_FROM = 0.92f
        const val PULL_TO_SEARCH_DP = 56f
    }
}
