package com.supernova.launcher.ui

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.databinding.ActivityLauncherBinding
import com.supernova.launcher.databinding.ViewFeedPaneBinding
import com.supernova.launcher.feed.CalendarCardSource
import com.supernova.launcher.feed.ContactsCardSource
import com.supernova.launcher.feed.FeedOrder
import com.supernova.launcher.feed.MediaCardSource
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

// Owns the feed page: a ViewStub-lazy sibling overlay pane on the workspace's
// left (never a page inside the pager's infinite-scroll trick), following the
// DrawerController pattern. Open paths: the OPEN_FEED action, the left-edge
// swipe (driven from LauncherActivity.dispatchTouchEvent through onTouch),
// and the pager's left overscroll. Card sources are strictly dormant while
// the pane is hidden.
//
// Cards: every enabled card is in the feed, pinned ones first, then the rest
// with the built-in cards among them, each group in the user's order
// (FeedOrder).
//
// A long-press picks a card up and a drag moves it within its group; let go
// where it was, it opens the card's menu instead (FeedCardDrag).
class FeedController(
    private val binding: ActivityLauncherBinding,
    private val viewModel: LauncherViewModel,
    private val scope: CoroutineScope,
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

        // The footer's Feed settings: the Feed page section.
        fun openFeedSettings()

        fun requestCalendarPermission()

        fun requestContactsPermission()

        // The media card reads sessions through notification access.
        fun requestMediaAccess()

        fun openCalendarEvent(eventId: Long)

        fun openContact(contact: ContactsCardSource.Starred, anchor: View)

        // Add card: the add sheet (a widget, apps and shortcuts, the
        // ready-made cards). The empty page goes straight to either kind.
        fun addCard()

        fun addWidgetCard()

        fun addShortcutsCard()
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
            onAddCard = { host.addCard() },
            onFeedSettings = { host.openFeedSettings() },
            onAddWidget = { host.addWidgetCard() },
            onAddShortcuts = { host.addShortcutsCard() },
            onShowBuiltIn = { key -> showBuiltIn(key) }
        ),
        cardBinder
    )

    // Sources exist from construction (cheap value objects) but touch no
    // system service until onShown.
    private val mediaSource = MediaCardSource(context.applicationContext)
    private val calendarSource = CalendarCardSource(context.applicationContext, scope)
    private val contactsSource = ContactsCardSource(context.applicationContext, scope)
    private var collectJob: Job? = null

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
            // Said outright: the view's animator keeps whichever interpolator
            // it was last given, and that may be a swipe's settle.
            pane.animate()
                .translationX(-width)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator())
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
                // A card picked up in the pane is being moved: a sideways
                // drift is that card's, not a swipe closing the pane.
                if (cardDrag.holding) {
                    stopTracking()
                    return TouchResult.PASS
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
        // The pane carries on from the finger's own speed rather than leaping
        // into a fixed slide (SwipeSettle).
        val nominal = Animations.duration(viewModel.state.value.prefs, SwipeSettle.NOMINAL_MS)
        val settle = if (shouldOpen) {
            SwipeSettle.of(-pane.translationX, xVelocity, nominal)
        } else {
            SwipeSettle.of(width + pane.translationX, -xVelocity, nominal)
        }
        val duration = settle.durationMs
        if (shouldOpen) {
            isOpen = true
            if (Animations.enabled(viewModel.state.value.prefs)) {
                pane.animate()
                    .translationX(0f)
                    .setDuration(duration)
                    .setInterpolator(settle)
                    .start()
                animateHomeFade(1f, duration, settle)
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
                    .setInterpolator(settle)
                    .withEndAction { if (!isOpen && !dragging) pane.visibility = View.GONE }
                    .start()
                animateHomeFade(0f, duration, settle)
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

    // ---- Shown lifecycle -------------------------------------------------

    private fun beginShown() {
        if (shown) return
        shown = true
        applyStyle()
        configureHeader()
        mediaSource.onShown()
        calendarSource.onShown()
        contactsSource.onShown()
        collectJob = scope.launch {
            launch {
                combine(
                    mediaSource.state, calendarSource.state, contactsSource.state
                ) { _, _, _ -> Unit }.collect { rebuild() }
            }
            launch {
                // Card edits made while the pane is open (editor sheet on top),
                // and moves. The first emission is where they stood as it
                // opened, which the rebuild below shows.
                viewModel.state.map { it.model.feedCards to it.model.feedOrder }
                    .distinctUntilChanged().drop(1).collect { rebuild() }
            }
        }
        rebuild()
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

    private fun rebuild() {
        if (!shown) return
        // Never under a held card: the rows it is being moved among would
        // be swapped for new ones mid-drag. The drop catches up.
        if (cardDrag.holding) {
            rebuildPending = true
            return
        }
        rebuildPending = false
        val prefs = viewModel.state.value.prefs
        // The built-in cards with something to show, by their order key.
        val builtIns = HashMap<String, FeedAdapter.Row>()
        if (prefs.feedShowMedia) {
            mediaSource.state.value?.let { builtIns[FeedOrder.MEDIA] = FeedAdapter.Row.Media(it) }
        }
        if (prefs.feedShowCalendar) {
            when (val state = calendarSource.state.value) {
                CalendarCardSource.CalendarState.NeedsPermission ->
                    builtIns[FeedOrder.CALENDAR] = FeedAdapter.Row.CalendarRow(state)
                is CalendarCardSource.CalendarState.Events ->
                    // Granted but empty week: no card beats an empty card.
                    if (state.events.isNotEmpty()) builtIns[FeedOrder.CALENDAR] = FeedAdapter.Row.CalendarRow(state)
                null -> Unit
            }
        }
        if (prefs.feedShowContacts) {
            when (val state = contactsSource.state.value) {
                ContactsCardSource.ContactsState.NeedsPermission ->
                    builtIns[FeedOrder.CONTACTS] = FeedAdapter.Row.Contacts(state)
                is ContactsCardSource.ContactsState.Contacts ->
                    if (state.contacts.isNotEmpty()) builtIns[FeedOrder.CONTACTS] = FeedAdapter.Row.Contacts(state)
                null -> Unit
            }
        }
        val rows = ArrayList<FeedAdapter.Row>()
        FeedOrder.layout(viewModel.feedOrder(), viewModel.feedCards(), builtIns.keys).forEach { entry ->
            val card = entry.card
            rows.add(if (card != null) FeedAdapter.Row.Custom(card, entry.group) else builtIns.getValue(entry.key))
        }
        // Nothing to show: the page says what it is for, with the
        // ready-made cards that are off. Otherwise Add card and Feed
        // settings close the list.
        rows.add(if (rows.isEmpty()) FeedAdapter.Row.Empty(readyMadeOff(prefs)) else FeedAdapter.Row.Footer)
        adapter.submit(rows)
    }

    private fun readyMadeOff(prefs: LauncherPrefs): List<String> = listOfNotNull(
        FeedOrder.MEDIA.takeUnless { prefs.feedShowMedia },
        FeedOrder.CALENDAR.takeUnless { prefs.feedShowCalendar },
        FeedOrder.CONTACTS.takeUnless { prefs.feedShowContacts }
    )

    // A ready-made card turned on from the empty page; calendar and contacts
    // ask for their access as they would from settings.
    private fun showBuiltIn(key: String) {
        when (key) {
            FeedOrder.MEDIA -> {
                viewModel.set(com.supernova.launcher.data.Pref.feedShowMedia, true)
                host.requestMediaAccess()
            }
            FeedOrder.CALENDAR -> {
                viewModel.set(com.supernova.launcher.data.Pref.feedShowCalendar, true)
                host.requestCalendarPermission()
            }
            FeedOrder.CONTACTS -> {
                viewModel.set(com.supernova.launcher.data.Pref.feedShowContacts, true)
                host.requestContactsPermission()
            }
        }
    }

    private var rebuildPending = false

    // Moving cards: a long-press picks one up and a drag moves it.
    private val cardOrder = FeedCardOrder(adapter, viewModel)
    private val cardDrag = FeedCardDrag(adapter, viewModel, cardOrder) { if (rebuildPending) rebuild() }

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
        FeedFillPages(adapter, cardBinder) { cardDrag.holding }.attach(pane.feedRecycler)
        val list = pane.feedRecycler
        list.layoutManager = FeedLayoutManager(context) { list.widgetHolds && !cardDrag.holding }
        cardDrag.attach(pane.feedRecycler)
        pane.feedSearchBar.onTap = { host.onOpenSearch() }
        pane.feedSearchBar.prefs = { viewModel.state.value.prefs }
        // The feed's Settings button opens the Feed page.
        pane.feedSearchBar.onChip = { chip ->
            (chip == com.supernova.launcher.ui.SearchBarChip.SETTINGS).also { if (it) host.openFeedSettings() }
        }

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
        if (!shown) return
        if (!prefs.feedEnabled) {
            close()
            return
        }
        applyStyle()
        if (prefs.feedShowSearch != appliedHeader) {
            appliedHeader = prefs.feedShowSearch
            configureHeader()
        }
        val rows = listOf(prefs.feedShowMedia, prefs.feedShowCalendar, prefs.feedShowContacts)
        if (rows != appliedRows) {
            appliedRows = rows
            rebuild()
        }
    }

    private var appliedHeader: Boolean? = null
    private var appliedRows: List<Any>? = null

    private fun applyStyle() {
        val prefs = viewModel.state.value.prefs
        // The feed's bar wears the desktop bar's styling, so that is part of
        // the key: restyling the search bar never reached the feed's.
        val key = listOf(
            prefs.styleConfig, prefs.paletteSource, prefs.paletteSeed, prefs.accentColor, prefs.searchBarConfig,
            prefs.feedBarButtons
        )
        if (key == appliedStyle) return
        appliedStyle = key
        val pane = ui ?: return
        val resolved = Styles.resolve(context, prefs)
        // The pane is a dim over the wallpaper (the feed page look); its
        // text takes the DRAWER surface's text color, the embedded bar wears
        // SEARCH_BAR and the cards the CARDS surface (POPUP's colours until
        // styled), keeping every color user-styleable through the pickers.
        val paneText = paneTextColor(prefs)
        pane.root.setBackgroundColor(FEED_DIM)
        val cards = resolved.of(SurfaceId.CARDS)
        adapter.setAppearance(cards.surface, cards.text, cards.accent, paneText)
        // The feed bar borrows the desktop bar's styling (radius, wireframe,
        // glass) - placement is this pane's own business, and so are its
        // buttons (feedBarButtons).
        val buttons = SearchBarButtons.decode(prefs.feedBarButtons).map { it.name }
        pane.feedSearchBar.apply(
            SearchBarConfig.decode(prefs.searchBarConfig).desktop.copy(chips = buttons),
            resolved.of(SurfaceId.SEARCH_BAR)
        )
    }

    // Text drawn straight on the pane (the footer, the empty page) sits on
    // FEED_DIM, which is always dark: a light drawer theme's
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

    // The header is the search pill alone; with it off there is none.
    private fun configureHeader() {
        val pane = ui ?: return
        pane.feedHeaderRow.visibility = if (viewModel.state.value.prefs.feedShowSearch) View.VISIBLE else View.GONE
    }

    private companion object {
        // Horizontal must beat vertical by this much before a drag is ours.
        const val DIRECTION_BIAS = 2f
        const val EDGE_GUTTER_DP = 24
        const val OPEN_MS = 220L
        const val CLOSE_MS = 180L
        // The feed page dims the wallpaper by about half.
        const val FEED_DIM = 0x80000000.toInt()
        // Pane text darker than this can't be read on FEED_DIM.
        const val DARK_TEXT_LUMINANCE = 0.5
    }
}
