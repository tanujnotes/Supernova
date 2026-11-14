package com.supernova.launcher.ui

import android.graphics.Canvas
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.supernova.launcher.R
import com.supernova.launcher.feed.FeedOrder
import kotlin.math.abs

// The feed's cards put in the user's own order (#150): Move up / Move down
// from a card's menu, or a long-press to pick a card up and a drag to move
// it (FeedCardDrag). A card only moves within its group (pinned, activated,
// the rest; see FeedOrder).
class FeedCardOrder(
    private val adapter: FeedAdapter,
    private val viewModel: LauncherViewModel,
    // A built-in card's menu: its settings page.
    private val openSettings: (section: Int) -> Unit
) : FeedCardRoutes.Moves {

    // The cards as shown: a card's neighbour has to be on screen with it,
    // in the same group.
    private fun shownEntries(): List<FeedOrder.Entry> =
        adapter.rowsShown.mapNotNull { row ->
            val key = row.key
            val group = row.group
            if (key == null || group == null) null else FeedOrder.Entry(key, group, null)
        }

    override fun canMove(key: String, up: Boolean): Boolean = FeedOrder.neighbour(shownEntries(), key, up) != null

    override fun move(key: String, up: Boolean) {
        val neighbour = FeedOrder.neighbour(shownEntries(), key, up) ?: return
        placeNextTo(key, neighbour.key, after = !up)
    }

    // A dragged card came to rest: after the card above it in its group,
    // or else before the one below.
    fun dropped(key: String) {
        val rows = adapter.rowsShown
        val at = rows.indexOfFirst { it.key == key }
        val group = rows.getOrNull(at)?.group ?: return
        val above = rows.getOrNull(at - 1)?.takeIf { it.group == group }?.key
        val below = rows.getOrNull(at + 1)?.takeIf { it.group == group }?.key
        when {
            above != null -> placeNextTo(key, above, after = true)
            below != null -> placeNextTo(key, below, after = false)
        }
    }

    // Stores `key` right before or after `target` in the full order (hidden
    // built-ins and disabled cards keep their places around them).
    private fun placeNextTo(key: String, target: String, after: Boolean) {
        val order = FeedOrder.resolve(viewModel.feedOrder(), viewModel.feedCards().map { it.id })
        val moved = FeedOrder.move(order, key, target, after)
        if (moved != order) viewModel.setFeedOrder(moved)
    }

    // A card let go where it was picked up: its menu. A custom card's is
    // the one its ⋮ opens; a built-in's has its settings page and the moves.
    fun openMenu(holder: RecyclerView.ViewHolder, key: String) {
        if (adapter.openCardMenu(holder)) return
        @StringRes val section = when (key) {
            FeedOrder.MEDIA -> R.string.cards_media_title
            FeedOrder.CALENDAR -> R.string.feed_calendar_title
            FeedOrder.CONTACTS -> R.string.feed_contacts_title
            else -> return
        }
        val entries = buildList<Pair<Int, () -> Unit>> {
            add(R.string.card_menu_edit to { openSettings(section) })
            if (canMove(key, up = true)) add(R.string.card_menu_move_up to { move(key, up = true) })
            if (canMove(key, up = false)) add(R.string.card_menu_move_down to { move(key, up = false) })
        }
        val anchor = holder.itemView
        ChoiceMenu.showActions(
            anchor, viewModel.state.value.prefs, entries.map { anchor.context.getString(it.first) }
        ) { which -> entries[which].second() }
    }
}

// Long-press to pick a card up, drag to move it among its own group, let go
// to put it down; the order is stored then. A card put down without having
// moved opens its menu, which is what a long-press did before cards could
// move.
class FeedCardDrag(
    private val adapter: FeedAdapter,
    private val viewModel: LauncherViewModel,
    private val order: FeedCardOrder,
    // The card is down and settled: rows held back while it was up can come.
    private val onSettled: () -> Unit
) : ItemTouchHelper.Callback() {

    // A card is in the hand (picked up and not yet settled).
    var holding = false
        private set
    private var heldKey: String? = null
    // Rows changed places under it.
    private var reordered = false
    // The finger moved it at all; a still card opens its menu.
    private var travelled = false
    private var touchSlop = 0
    // A card was picked up during the current touch.
    private var liftedThisTouch = false

    // ItemTouchHelper only takes the touch over on a move once a card is up,
    // so a finger lifted dead still left the release with whatever was
    // under it: a widget or the app tile took it as a tap and launched,
    // and the card's menu then opened over the app. Once a card is up, the
    // release is the feed's: what was under the finger gets a cancel. Added
    // after the helper's own listener, so the helper still sees it first.
    private val keepRelease = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) liftedThisTouch = false
            return liftedThisTouch && e.actionMasked == MotionEvent.ACTION_UP
        }
    }

    fun attach(recycler: RecyclerView) {
        touchSlop = ViewConfiguration.get(recycler.context).scaledTouchSlop
        ItemTouchHelper(this).attachToRecyclerView(recycler)
        recycler.addOnItemTouchListener(keepRelease)
    }

    // Cards move, up and down; the Add card button doesn't.
    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        val row = adapter.rowsShown.getOrNull(viewHolder.bindingAdapterPosition)
        return if (row?.key == null) 0 else makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
    }

    override fun canDropOver(
        recyclerView: RecyclerView,
        current: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val rows = adapter.rowsShown
        val held = rows.getOrNull(current.bindingAdapterPosition)
        val over = rows.getOrNull(target.bindingAdapterPosition)
        return held?.key != null && over?.key != null && held.group == over.group
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        adapter.moveRow(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
        reordered = true
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState != ItemTouchHelper.ACTION_STATE_DRAG || viewHolder == null) return
        holding = true
        liftedThisTouch = true
        heldKey = adapter.rowsShown.getOrNull(viewHolder.bindingAdapterPosition)?.key
        reordered = false
        travelled = false
        val view = viewHolder.itemView
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        view.animate().scaleX(LIFT_SCALE).scaleY(LIFT_SCALE)
            .setDuration(Animations.duration(viewModel.state.value.prefs, LIFT_MS)).start()
        // Neighbours slide aside rather than jump while a card is held; the
        // feed has no item animations otherwise.
        (view.parent as? RecyclerView)?.itemAnimator = DefaultItemAnimator()
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        if (isCurrentlyActive && abs(dY) > touchSlop) travelled = true
    }

    // A card put down unmoved settles at once, so its menu isn't late.
    override fun getAnimationDuration(
        recyclerView: RecyclerView,
        animationType: Int,
        animateDx: Float,
        animateDy: Float
    ): Long = if (travelled) super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy) else 0L

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        val view = viewHolder.itemView
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        val key = heldKey
        holding = false
        heldKey = null
        recyclerView.itemAnimator = null
        when {
            key == null -> Unit
            reordered -> order.dropped(key)
            // Not over a feed that has gone meanwhile (Home pressed with a
            // card still up).
            !travelled && view.isShown -> order.openMenu(viewHolder, key)
        }
        onSettled()
    }

    private companion object {
        // A picked-up card grows this much, over this long.
        const val LIFT_SCALE = 1.03f
        const val LIFT_MS = 120L
    }
}
