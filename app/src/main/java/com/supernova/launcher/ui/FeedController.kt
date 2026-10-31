package com.supernova.launcher.ui

import android.graphics.Rect
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import com.supernova.launcher.R
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.databinding.ActivityLauncherBinding
import com.supernova.launcher.databinding.ViewFeedPaneBinding
import com.supernova.launcher.feed.CalendarCardSource
import com.supernova.launcher.feed.CardActivation
import com.supernova.launcher.feed.ContactsCardSource
import com.supernova.launcher.feed.FeaturedType
import com.supernova.launcher.feed.FeedCard
import com.supernova.launcher.feed.MediaCardSource
import com.supernova.launcher.search.AppSearchProvider
import com.supernova.launcher.ui.theme.Styles
import com.supernova.launcher.ui.theme.SurfaceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

// Owns the feed page: a ViewStub-lazy sibling overlay pane on the workspace's
// left (never a page inside the pager's infinite-scroll trick), following the
// DrawerController pattern. Open paths: the OPEN_FEED action, the left-edge
// swipe (driven from LauncherActivity.dispatchTouchEvent through onTouch),
// and the pager's left overscroll. Card sources are strictly dormant while
// the pane is hidden.
//
// Custom cards: every enabled card is in the feed, pinned ones
// first, then the activated ones (all conditions met), then the rest. The
// shared CardActivation evaluates conditions and is held only while shown.
class FeedController(
    private val binding: ActivityLauncherBinding,
    private val viewModel: LauncherViewModel,
    private val scope: CoroutineScope,
    private val activation: CardActivation,
    private val cardBinder: CardViewBinder,
    private val host: Host,
    // Whether a swipe that started at (x, y) on the workspace may pull the
    // feed in like a pager page (first page, no wrap-around, nothing armed).
    private val canPullFromWorkspace: (x: Float, y: Float) -> Boolean = { _, _ -> false },
    // A long-press has armed an item drag (its menu is up): the gesture is
    // that drag's, wherever it started.
    private val itemDragArmed: () -> Boolean = { false }
) {

    interface Host {
        fun onOpenSearch()

        // The overflow's Settings entry: the Feed page section.
        fun openFeedSettings()

        fun launchApp(anchor: View?, app: LaunchableApp)

        // type APP/SHORTCUT; target uses the gesture payload encodings.
        fun launchTarget(type: FeaturedType, target: String, anchor: View?)

        fun requestCalendarPermission()

        fun requestContactsPermission()

        fun openCalendarEvent(eventId: Long)

        fun openContact(contact: ContactsCardSource.Starred, anchor: View)

        // null = create a new card.
        fun editCard(card: FeedCard?)
    }

    enum class TouchResult { PASS, TAKE_OVER, CONSUMED }

    private val context get() = binding.root.context

    var isOpen = false
        private set

    private var ui: ViewFeedPaneBinding? = null
    private var shown = false

    private val adapter = FeedAdapter(
        FeedAdapter.Callbacks(
            onGrantCalendar = { host.requestCalendarPermission() },
            onGrantContacts = { host.requestContactsPermission() },
            onOpenEvent = { id -> host.openCalendarEvent(id) },
            onOpenContact = { contact, view -> host.openContact(contact, view) },
            onAddCard = { host.editCard(null) }
        ),
        cardBinder
    )

    // Sources exist from construction (cheap value objects) but touch no
    // system service until onShown.
    private val mediaSource = MediaCardSource(context.applicationContext)
    private val calendarSource = CalendarCardSource(context.applicationContext, scope)
    private val contactsSource = ContactsCardSource(context.applicationContext, scope)
    // Wi-Fi / Bluetooth signal changes while shown re-sort the cards.
    private val activationListener: () -> Unit = { if (shown) rebuild() }

    private var collectJob: Job? = null
    private val boundaryRunnable = Runnable {
        if (shown) {
            rebuild()
            scheduleBoundary()
        }
    }

    // Swipe state. The drag path mutates translationX only - no allocations
    // per frame (the VelocityTracker is obtained once per gesture). Three
    // gestures share it: the left-edge swipe, the page-0 pull (both open the
    // pane under the finger) and, while open, a leftward swipe that drags it
    // back out - together they make the feed feel like the page before the
    // first.
    private var edgeTracking = false
    // Armed by the page-0 pull rather than the edge gutter.
    private var pullTracking = false
    // Armed while open: a leftward swipe closes.
    private var closeTracking = false
    private var dragging = false
    // translationX at the drag's start (0 open, -width closed).
    private var dragOrigin = 0f
    private var downX = 0f
    private var downY = 0f
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4

    private var appliedStyle: List<Any?>? = null
    private var exclusionPrefs: Pair<Boolean, Boolean>? = null
    private val exclusionLayoutListener = android.view.View.OnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
        if (r - l != oldR - oldL || b - t != oldB - oldT) setExclusionRects()
    }

    // ---- Open / close ----------------------------------------------------

    fun open() {
        if (isOpen || dragging) return
        val pane = ensureInflated().root
        isOpen = true
        beginShown()
        pane.visibility = View.VISIBLE
        pane.doOnLayout {
            if (!isOpen) return@doOnLayout
            if (Animations.enabled(viewModel.state.value.prefs)) {
                if (pane.translationX == 0f) pane.translationX = -paneWidth().toFloat()
                val duration = Animations.duration(viewModel.state.value.prefs, OPEN_MS)
                pane.animate()
                    .translationX(0f)
                    .setDuration(duration)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                animateHomeFade(1f, duration, DecelerateInterpolator())
            } else {
                pane.translationX = 0f
                applyHomeFade(1f)
            }
        }
    }

    // The search window opened from the feed's bar is see-through wherever the
    // drawer's surface or the bar's glass is, and the page must not show in it.
    fun setBehindSearch(behind: Boolean) {
        ui?.root?.alpha = if (behind) 0f else 1f
    }

    fun close() {
        if (!isOpen && !dragging) return
        isOpen = false
        dragging = false
        stopTracking()
        endShown()
        val pane = ui?.root ?: return
        val width = paneWidth().toFloat()
        pane.animate().cancel()
        if (Animations.enabled(viewModel.state.value.prefs)) {
            val duration = Animations.duration(viewModel.state.value.prefs, CLOSE_MS)
            pane.animate()
                .translationX(-width)
                .setDuration(duration)
                .withEndAction {
                    if (!isOpen && !dragging) pane.visibility = View.GONE
                }
                .start()
            animateHomeFade(0f, duration, null)
        } else {
            pane.translationX = -width
            pane.visibility = View.GONE
            applyHomeFade(0f)
        }
    }

    // ---- Desktop fade -----------------------------------------------------
    //
    // The feed page shows dimmed wallpaper, never the home screen. The
    // pane's background is only a dim, so the desktop underneath fades out
    // in step with how far the pane is in (0 = parked, 1 = open).

    private val homeViews: List<View> by lazy {
        listOf(
            binding.workspacePager, binding.dockBar, binding.pageIndicator,
            binding.dockSwipeIndicator, binding.desktopSearchBar, binding.desktopSearchWidget
        )
    }
    private var homeAnimator: android.animation.ValueAnimator? = null

    private fun applyHomeFade(shown: Float) {
        homeAnimator?.cancel()
        val alpha = 1f - shown.coerceIn(0f, 1f)
        for (view in homeViews) view.alpha = alpha
    }

    private fun animateHomeFade(shown: Float, duration: Long, interpolator: android.view.animation.Interpolator?) {
        homeAnimator?.cancel()
        if (duration == 0L) {
            applyHomeFade(shown)
            return
        }
        val from = 1f - binding.workspacePager.alpha
        homeAnimator = android.animation.ValueAnimator.ofFloat(from, shown).apply {
            this.duration = duration
            if (interpolator != null) this.interpolator = interpolator
            addUpdateListener { animator ->
                val alpha = 1f - (animator.animatedValue as Float)
                for (view in homeViews) view.alpha = alpha
            }
            start()
        }
    }

    // ---- Left-edge swipe (fed by LauncherActivity.dispatchTouchEvent) ----

    // True while a swipe that began on the workspace may still become a
    // feed pull; the pager's overscroll opener stands down for it.
    val isTracking: Boolean get() = edgeTracking || pullTracking || dragging

    fun onTouch(ev: MotionEvent): TouchResult {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeTracking = false
                pullTracking = false
                closeTracking = false
                dragging = false
                val prefs = viewModel.state.value.prefs
                if (!prefs.feedEnabled) return TouchResult.PASS
                if (isOpen) {
                    closeTracking = true
                } else {
                    edgeTracking = prefs.feedEdgeSwipe && ev.x <= context.dp(EDGE_GUTTER_DP)
                    pullTracking = !edgeTracking && canPullFromWorkspace(ev.x, ev.y)
                }
                if (edgeTracking || pullTracking || closeTracking) {
                    downX = ev.x
                    downY = ev.y
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                }
                return TouchResult.PASS
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger is a pinch or a two-finger gesture, never
                // a page pull.
                if (!dragging) stopTracking()
                return if (dragging) TouchResult.CONSUMED else TouchResult.PASS
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    velocityTracker?.addMovement(ev)
                    dragTo(ev.x)
                    return TouchResult.CONSUMED
                }
                if (edgeTracking || pullTracking || closeTracking) {
                    velocityTracker?.addMovement(ev)
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    val opening = edgeTracking || pullTracking
                    val towards = if (opening) dx else -dx
                    // ViewPager's rule: horizontal wins only when it clearly
                    // dominates (2:1), so a vertical swipe that drifts
                    // sideways never becomes a feed drag.
                    if (towards > touchSlop && towards > abs(dy) * DIRECTION_BIAS) {
                        // Re-check at the takeover: a long-press may have
                        // armed an item drag since the down. The edge swipe
                        // too - a long-press near the left edge took the
                        // feed at the touch slop, then the drag started
                        // anyway, and the feed never saw the lift: the pane
                        // froze part way in, and Back couldn't close it.
                        if ((pullTracking && !canPullFromWorkspace(downX, downY)) || itemDragArmed()) {
                            stopTracking()
                            return TouchResult.PASS
                        }
                        // Take over the stream: the activity cancels the
                        // underlying views and routes every further event here.
                        startDrag(ev.x)
                        return TouchResult.TAKE_OVER
                    }
                    // Decided vertical (or moving the wrong way): this swipe
                    // is no longer ours, whatever it does later.
                    if ((abs(dy) > touchSlop && abs(dy) >= towards) || -towards > touchSlop) {
                        stopTracking()
                    }
                }
                return TouchResult.PASS
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    settle(cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL)
                    return TouchResult.CONSUMED
                }
                stopTracking()
                return TouchResult.PASS
            }
        }
        return if (dragging) TouchResult.CONSUMED else TouchResult.PASS
    }

    private fun stopTracking() {
        edgeTracking = false
        pullTracking = false
        closeTracking = false
        recycleVelocityTracker()
    }

    private fun startDrag(currentX: Float) {
        dragging = true
        edgeTracking = false
        pullTracking = false
        closeTracking = false
        val pane = ensureInflated().root
        pane.animate().cancel()
        if (isOpen) {
            dragOrigin = 0f
        } else {
            dragOrigin = -paneWidth().toFloat()
            beginShown()
            pane.visibility = View.VISIBLE
        }
        dragTo(currentX)
    }

    private fun dragTo(x: Float) {
        val pane = ui?.root ?: return
        val width = paneWidth().toFloat()
        pane.translationX = (dragOrigin + (x - downX)).coerceIn(-width, 0f)
        applyHomeFade((width + pane.translationX) / width)
    }

    private fun settle(cancelled: Boolean) {
        val pane = ui?.root
        dragging = false
        val tracker = velocityTracker
        var xVelocity = 0f
        if (tracker != null) {
            tracker.computeCurrentVelocity(1000)
            xVelocity = tracker.xVelocity
        }
        recycleVelocityTracker()
        if (pane == null) return
        val width = paneWidth().toFloat()
        val shouldOpen = when {
            // A cancelled stream leaves things as they were.
            cancelled -> dragOrigin == 0f
            xVelocity > minFlingVelocity -> true
            xVelocity < -minFlingVelocity -> false
            else -> pane.translationX > -width / 2f
        }
        val duration = Animations.duration(viewModel.state.value.prefs, SETTLE_MS)
        if (shouldOpen) {
            isOpen = true
            if (Animations.enabled(viewModel.state.value.prefs)) {
                pane.animate()
                    .translationX(0f)
                    .setDuration(duration)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                animateHomeFade(1f, duration, DecelerateInterpolator())
            } else {
                pane.translationX = 0f
                applyHomeFade(1f)
            }
        } else {
            isOpen = false
            endShown()
            if (Animations.enabled(viewModel.state.value.prefs)) {
                pane.animate()
                    .translationX(-width)
                    .setDuration(duration)
                    .withEndAction { if (!isOpen && !dragging) pane.visibility = View.GONE }
                    .start()
                animateHomeFade(0f, duration, null)
            } else {
                pane.translationX = -width
                pane.visibility = View.GONE
                applyHomeFade(0f)
            }
        }
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    // ---- System gesture exclusion ---------------------------------------

    // Best effort against gesture-nav back always winning the left edge: a
    // 24dp-wide strip, capped at 200dp tall (the OS honors at most 200dp of
    // exclusions per window) in the bottom half of the screen, stopping 48dp
    // above the bottom edge so the home gesture area stays untouched. The OS
    // is free to trim this further; the pager-overscroll and OPEN_FEED paths
    // remain the reliable openers.
    // The strip is placed from the root's height, so it is set again
    // whenever the root changes size - a rotation, a window resize - and
    // not only when the feed prefs change. Left as it was, portrait's strip
    // sat below a landscape window, handing the whole left edge to system
    // Back, and landscape's sat mid-screen in portrait; the edge swipe
    // stopped working either way.
    fun applyGestureExclusion(prefs: LauncherPrefs) {
        val key = prefs.feedEnabled to prefs.feedEdgeSwipe
        if (key == exclusionPrefs) return
        exclusionPrefs = key
        val root = binding.root
        root.removeOnLayoutChangeListener(exclusionLayoutListener)
        root.addOnLayoutChangeListener(exclusionLayoutListener)
        root.doOnLayout { setExclusionRects() }
    }

    private fun setExclusionRects() {
        val (enabled, edgeSwipe) = exclusionPrefs ?: return
        val root = binding.root
        val rects = if (enabled && edgeSwipe) {
            val height = root.height
            val strip = min(context.dp(200), height / 2)
            val bottom = (height - context.dp(48)).coerceAtLeast(strip)
            listOf(Rect(0, bottom - strip, context.dp(EDGE_GUTTER_DP), bottom))
        } else {
            emptyList()
        }
        ViewCompat.setSystemGestureExclusionRects(root, rects)
    }

    // ---- Shown lifecycle -------------------------------------------------

    private fun beginShown() {
        if (shown) return
        shown = true
        applyStyle()
        configureHeader()
        mediaSource.onShown()
        calendarSource.onShown()
        contactsSource.onShown()
        activation.acquire(this)
        activation.addListener(activationListener)
        collectJob = scope.launch {
            launch {
                combine(
                    mediaSource.state, calendarSource.state, contactsSource.state
                ) { _, _, _ -> Unit }.collect { rebuild() }
            }
            launch {
                // Card edits made while the pane is open (editor sheet on top).
                // The first emission is the cards acquire just armed for.
                viewModel.state.map { it.model.feedCards }.distinctUntilChanged().drop(1).collect {
                    activation.refresh()
                    rebuild()
                    scheduleBoundary()
                }
            }
            launch {
                // A launch from the pane leaves it open behind the app: its
                // frequent apps and suggested shortcuts catch up. The first
                // value is where they stood as it opened.
                viewModel.launchGeneration.drop(1).collect { configureHeader() }
            }
        }
        rebuild()
        scheduleBoundary()
    }

    // The activity is being destroyed with the pane open: let go of every
    // source, or their system listeners keep the old activity alive.
    fun release() = endShown()

    private fun endShown() {
        if (!shown) return
        shown = false
        collectJob?.cancel()
        collectJob = null
        mediaSource.onHidden()
        calendarSource.onHidden()
        contactsSource.onHidden()
        activation.removeListener(activationListener)
        activation.release(this)
        ui?.root?.removeCallbacks(boundaryRunnable)
    }

    // In-card permission grants land while the pane is open; re-pull both
    // permission-gated sources.
    // A card's featured widget has one view, and a card shown elsewhere
    // meanwhile (search) takes it: the open pane binds its cards again to
    // get it back.
    fun rebindCards() = rebuild()

    fun refreshSources() {
        if (!shown) return
        calendarSource.refresh()
        contactsSource.refresh()
    }

    // DAY_TIME conditions re-evaluate exactly at the next start/end boundary
    // while shown; nothing is scheduled while hidden.
    private fun scheduleBoundary() {
        val pane = ui?.root ?: return
        pane.removeCallbacks(boundaryRunnable)
        if (!shown) return
        val minutes = activation.minutesToNextBoundary() ?: return
        pane.postDelayed(boundaryRunnable, minutes * 60_000L)
    }

    private fun rebuild() {
        if (!shown) return
        val prefs = viewModel.state.value.prefs
        val rows = ArrayList<FeedAdapter.Row>()
        if (prefs.feedShowMedia) {
            mediaSource.state.value?.let { rows.add(FeedAdapter.Row.Media(it)) }
        }
        if (prefs.feedShowCalendar) {
            when (val state = calendarSource.state.value) {
                CalendarCardSource.CalendarState.NeedsPermission ->
                    rows.add(FeedAdapter.Row.CalendarRow(state))
                is CalendarCardSource.CalendarState.Events ->
                    // Granted but empty week: no card beats an empty card.
                    if (state.events.isNotEmpty()) rows.add(FeedAdapter.Row.CalendarRow(state))
                null -> Unit
            }
        }
        if (prefs.feedShowContacts) {
            when (val state = contactsSource.state.value) {
                ContactsCardSource.ContactsState.NeedsPermission ->
                    rows.add(FeedAdapter.Row.Contacts(state))
                is ContactsCardSource.ContactsState.Contacts ->
                    if (state.contacts.isNotEmpty()) rows.add(FeedAdapter.Row.Contacts(state))
                null -> Unit
            }
        }
        activation.feedOrder().forEach { rows.add(FeedAdapter.Row.Custom(it)) }
        rows.add(FeedAdapter.Row.AddCard)
        adapter.submit(rows)
    }

    // ---- Chrome ----------------------------------------------------------

    private fun ensureInflated(): ViewFeedPaneBinding {
        ui?.let { return it }
        val root = binding.feedStub.inflate()
        val pane = ViewFeedPaneBinding.bind(root)
        ui = pane

        // System bar padding, like the search overlay; the pane accepts IME
        // overlap (it hosts no input of its own).
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, 0)
            val density = view.resources.displayMetrics.density
            pane.feedRecycler.setPadding(
                (12 * density).toInt(),
                (8 * density).toInt(),
                (12 * density).toInt(),
                (24 * density).toInt() + bars.bottom
            )
            insets
        }
        root.requestApplyInsets()

        pane.feedRecycler.adapter = adapter
        pane.feedRecycler.itemAnimator = null
        pane.feedRecycler.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(context)
        pane.feedSearchBar.onTap = { host.onOpenSearch() }
        pane.feedMenu.setOnClickListener { anchor -> showMenu(anchor) }

        // Park closed and keep parked across relayouts (rotation, inset
        // changes) - but never fight an active drag or the open state.
        root.addOnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
            if (!isOpen && !dragging) view.translationX = -(right - left).toFloat()
        }
        root.visibility = View.GONE
        return pane
    }

    private fun paneWidth(): Int {
        val width = ui?.root?.width ?: 0
        return if (width > 0) width else binding.root.width.coerceAtLeast(1)
    }

    // Settings reach an open pane as well. Changed from the pane's own menu
    // (the ⋮, then Settings) and back, it went on showing its old rows and
    // styling; turned off, an open pane could no longer be swiped shut.
    fun onPrefs(prefs: LauncherPrefs) {
        applyGestureExclusion(prefs)
        if (!shown) return
        if (!prefs.feedEnabled) {
            close()
            return
        }
        applyStyle()
        val header = listOf(prefs.feedShowSearch, prefs.feedShowFrequent, prefs.feedShowShortcuts, prefs.feedShortcutRows)
        if (header != appliedHeader) {
            appliedHeader = header
            configureHeader()
        }
        val rows = listOf(prefs.feedShowMedia, prefs.feedShowCalendar, prefs.feedShowContacts)
        if (rows != appliedRows) {
            appliedRows = rows
            rebuild()
        }
    }

    private var appliedHeader: List<Any>? = null
    private var appliedRows: List<Any>? = null

    private fun applyStyle() {
        val prefs = viewModel.state.value.prefs
        // The feed's bar wears the desktop bar's styling, so that is part of
        // the key: restyling the search bar never reached the feed's.
        val key = listOf(
            prefs.styleConfig, prefs.paletteSource, prefs.paletteSeed, prefs.accentColor, prefs.searchBarConfig
        )
        if (key == appliedStyle) return
        appliedStyle = key
        val pane = ui ?: return
        val resolved = Styles.resolve(context, prefs)
        // The pane is a dim over the wallpaper (the feed page look); its
        // text takes the DRAWER surface's text color, the embedded bar wears
        // SEARCH_BAR and the cards the POPUP surface, keeping every color
        // user-styleable through the existing pickers.
        val paneText = paneTextColor(prefs)
        pane.root.setBackgroundColor(FEED_DIM)
        // Inside the bar the menu wears the bar's text colour, like its
        // buttons; beside a hidden bar, the pane's.
        pane.feedMenu.imageTintList = android.content.res.ColorStateList.valueOf(
            if (pane.feedSearchBar.trailing === pane.feedMenu) resolved.of(SurfaceId.SEARCH_BAR).text else paneText
        )
        pane.feedNoFrequent.setTextColor((0xB3 shl 24) or (paneText and 0xFFFFFF))
        val popupSurface = resolved.of(SurfaceId.POPUP)
        adapter.setAppearance(popupSurface.surface, popupSurface.text, popupSurface.accent, paneText)
        // The feed bar borrows the desktop bar's styling (radius, wireframe,
        // logo, chips) - placement is this pane's own business.
        pane.feedSearchBar.apply(
            SearchBarConfig.decode(prefs.searchBarConfig).desktop,
            resolved.of(SurfaceId.SEARCH_BAR)
        )
    }

    // Text drawn straight on the pane (menu, frequent labels, shortcut pills,
    // Add card) sits on FEED_DIM, which is always dark: a light drawer theme's
    // dark text vanished there, so a dark drawer text falls back to white.
    private fun paneTextColor(prefs: LauncherPrefs): Int {
        val text = Styles.resolve(context, prefs).of(SurfaceId.DRAWER).text
        val dark = androidx.core.graphics.ColorUtils.calculateLuminance(text) < DARK_TEXT_LUMINANCE
        return if (dark) android.graphics.Color.WHITE else text
    }

    // Wallpaper-palette invalidation arrives without a prefs emission.
    fun invalidateStyle() {
        appliedStyle = null
        if (shown) applyStyle()
    }

    private fun configureHeader() {
        val pane = ui ?: return
        val prefs = viewModel.state.value.prefs
        pane.feedSearchBar.visibility = if (prefs.feedShowSearch) View.VISIBLE else View.GONE
        // The overflow sits inside the bar, at its right end; with the bar
        // off it stays on its own in the header row, the menu's only door.
        val menuInBar = prefs.feedShowSearch
        if (menuInBar != (pane.feedSearchBar.trailing === pane.feedMenu)) {
            val density = context.resources.displayMetrics.density
            if (menuInBar) {
                pane.feedSearchBar.trailing = pane.feedMenu
            } else {
                pane.feedSearchBar.trailing = null
                // On its own it centres its 24dp icon, as its layout had it.
                val pad = ((MENU_DP - MENU_ICON_DP) / 2 * density).toInt()
                pane.feedMenu.setPadding(pad, pad, pad, pad)
                val size = (MENU_DP * density).toInt()
                pane.feedHeaderRow.addView(pane.feedMenu, android.widget.LinearLayout.LayoutParams(size, size))
            }
            // The bar runs to the same edge margin as its start; the lone
            // menu keeps the narrower one its touch padding needs.
            (pane.feedHeaderRow.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.marginEnd = ((if (menuInBar) BAR_END_DP else MENU_END_DP) * density).toInt()
                pane.feedHeaderRow.layoutParams = it
            }
            // The menu's tint depends on where it sits.
            appliedStyle = null
            applyStyle()
        }
        if (prefs.feedShowFrequent) {
            val counts = viewModel.launchCounts()
            val frequent = viewModel.visibleApps()
                .filter { (counts[AppSearchProvider.countKey(it)] ?: 0) > 0 }
                .sortedByDescending { counts[AppSearchProvider.countKey(it)] ?: 0 }
                .take(4)
            if (frequent.isEmpty()) {
                // A placeholder keeps the row's place.
                pane.feedFrequentRow.visibility = View.GONE
                pane.feedNoFrequent.visibility = View.VISIBLE
            } else {
                pane.feedNoFrequent.visibility = View.GONE
                pane.feedFrequentRow.visibility = View.VISIBLE
                val catalog = viewModel.appCatalog()
                val textColor = paneTextColor(prefs)
                pane.feedFrequentRow.bindLabeled(
                    frequent.map { app ->
                        FrequentAppsRowView.Entry(app.label, catalog.iconFor(app)) { view ->
                            host.launchApp(view, app)
                        }
                    },
                    textColor
                )
            }
        } else {
            pane.feedFrequentRow.visibility = View.GONE
            pane.feedNoFrequent.visibility = View.GONE
        }
        configureShortcutsRow(pane, prefs)
    }

    // Suggested shortcuts (D7): icon+label pills for the first
    // shortcuts of the most-launched apps, wrapping into exactly the number
    // of rows the pref asks for (the FlowLayout hides whatever overflows).
    private fun configureShortcutsRow(
        pane: com.supernova.launcher.databinding.ViewFeedPaneBinding,
        prefs: com.supernova.launcher.data.LauncherPrefs
    ) {
        val flow = pane.feedShortcutsFlow
        flow.removeAllViews()
        if (!prefs.feedShowShortcuts) {
            flow.visibility = View.GONE
            return
        }
        val pills = SuggestedShortcuts.pills(viewModel, MAX_SUGGESTED_SHORTCUTS)
        if (pills.isEmpty()) {
            flow.visibility = View.GONE
            return
        }
        val textColor = paneTextColor(prefs)
        flow.maxLines = prefs.feedShortcutRows.coerceIn(1, 3)
        pills.forEach { pill ->
            flow.addView(
                CardViewBinder.pill(context, pill.icon, pill.label, textColor) { view ->
                    host.launchTarget(FeaturedType.SHORTCUT, pill.target, view)
                }
            )
        }
        flow.visibility = View.VISIBLE
    }

    // The overflow beside the search pill: Add card, Settings.
    private fun showMenu(anchor: View) {
        val menu = android.widget.PopupMenu(context, anchor)
        menu.menu.add(0, 1, 0, R.string.cards_add)
        menu.menu.add(0, 2, 1, R.string.feed_menu_settings)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> host.editCard(null)
                2 -> host.openFeedSettings()
            }
            true
        }
        menu.show()
    }


    private companion object {
        // The overflow menu's size, and the header row's end margin with the
        // menu inside the bar or on its own.
        const val MENU_DP = 44
        const val MENU_ICON_DP = 24
        const val BAR_END_DP = 16
        const val MENU_END_DP = 8
        // Horizontal must beat vertical by this much before a drag is ours.
        const val DIRECTION_BIAS = 2f
        const val MAX_SUGGESTED_SHORTCUTS = 12
        const val EDGE_GUTTER_DP = 24
        const val OPEN_MS = 220L
        const val CLOSE_MS = 180L
        const val SETTLE_MS = 160L
        // The feed page dims the wallpaper by about half.
        const val FEED_DIM = 0x80000000.toInt()
        // Pane text darker than this can't be read on FEED_DIM.
        const val DARK_TEXT_LUMINANCE = 0.5
    }
}
