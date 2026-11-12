package com.supernova.launcher.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.supernova.launcher.R
import com.supernova.launcher.data.LauncherPrefs

// An app held in an open home or dock folder and moved: it stays in the
// card as a picture under the finger, and once it rests over another place
// for a moment the others shift to make room there. Let go inside the card,
// it settles into that place and the folder keeps the new order. Taken past
// the card's edge, it goes on to the home screen as any drag out of a folder
// does (LiveDragController, which runs the gesture and calls in here).
//
// root is the folder window's content (FolderPopup): it holds the card,
// and the picture is drawn in a layer of its own laid over the card, as the
// home drag's is drawn over the home screen. Nothing in the card is scaled
// or moved by hand while the finger is down: the grid's own item animations
// shift the tiles, and their layout positions are what the finger is
// matched against.
class FolderReorderDrag(
    root: ViewGroup,
    private val adapter: FolderMemberAdapter,
    private val prefs: () -> LauncherPrefs,
    private val onReordered: (List<String>) -> Unit
) : LiveDragController.WindowDrag {

    private val card: View = root.findViewById(R.id.folderCard)
    private val grid: RecyclerView = root.findViewById(R.id.folderGrid)
    private val layer = DragLayer(root.context).also { layer ->
        // Above the card, whose own elevation would otherwise draw it over
        // the picture. The layer has no background, so no shadow of its own.
        layer.translationZ = root.context.dp(LAYER_Z_DP).toFloat()
        val fill = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(layer, ViewGroup.LayoutParams(fill, fill))
    }
    private val handler = Handler(Looper.getMainLooper())
    private val edge = GridEdgeScroll(grid) { itemId != null }

    // The member in the hand, and the order the folder had when it was
    // picked up (put back if the drag is called off).
    private var itemId: String? = null
    private var pickedUpOrder: List<String> = emptyList()
    // The place the picture rests over, waiting out REORDER_DELAY_MS before
    // the others make room for it; -1 none.
    private var target = -1
    private var lastX = 0f
    private var lastY = 0f
    private var inside = false

    private val reorder = Runnable { makeRoom() }

    // Whatever moves the grid under a still finger moves its aim too.
    private val reaim = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = aim()
    }

    override fun begin(anchor: View, grabRawX: Float, grabRawY: Float, rawX: Float, rawY: Float): Boolean {
        val item = grid.findContainingViewHolder(anchor)?.bindingAdapterPosition?.let(adapter.members::getOrNull)
        val picture = iconPicture(anchor.findViewById(R.id.icon))
        if (item == null || picture == null) return false
        layer.begin(
            picture.first, grabRawX, grabRawY, picture.second, PICKUP_SCALE, Animations.duration(prefs(), PICKUP_MS)
        )
        itemId = item.id
        pickedUpOrder = adapter.members.map { it.id }
        adapter.hiddenItemId = item.id
        anchor.alpha = 0f
        target = -1
        grid.addOnScrollListener(reaim)
        move(rawX, rawY)
        return true
    }

    override fun move(rawX: Float, rawY: Float): Boolean {
        lastX = rawX
        lastY = rawY
        layer.moveTo(rawX, rawY)
        // On its way out, nothing waits to make room and nothing scrolls.
        inside = card.screenRect().contains(rawX.toInt(), rawY.toInt())
        aim()
        if (inside) edge.follow(rawX, rawY) else edge.stop()
        return inside
    }

    override fun drop() {
        val id = itemId ?: return
        handler.removeCallbacks(reorder)
        // A page still settling under the picture no longer aims it.
        grid.removeOnScrollListener(reaim)
        // A place the finger was resting over when it let go is taken, as if
        // the wait had run out.
        val moved = target >= 0 && makeRoom()
        edge.stop()
        val order = adapter.members.map { it.id }
        if (order != pickedUpOrder) onReordered(order)
        // Once the grid has laid the move out, the picture flies to its tile.
        if (moved) grid.doOnPreDraw { settle(id) } else settle(id)
    }

    override fun cancel() {
        if (itemId == null) return
        layer.cancel()
        val byId = adapter.members.associateBy { it.id }
        if (byId.keys.toList() != pickedUpOrder) adapter.submit(pickedUpOrder.mapNotNull(byId::get))
        reveal()
    }

    override fun pictureRect(): RectF? = layer.visualRect()

    // The home drag has taken over, with a picture of its own; the card
    // goes with it, so the tiles stay as they are.
    override fun handOff() {
        layer.cancel()
        handler.removeCallbacks(reorder)
        edge.stop()
        grid.removeOnScrollListener(reaim)
        itemId = null
        target = -1
    }

    // The place the finger aims at, waited on before the others make room;
    // none while it is outside the card.
    private fun aim() {
        val id = itemId ?: return
        val from = adapter.positionOf(id)
        val to = if (inside) slotAt(grid, lastX, lastY) else -1
        val next = if (from >= 0 && to >= 0 && to != from) to else -1
        if (next == target) return
        target = next
        handler.removeCallbacks(reorder)
        if (next >= 0) handler.postDelayed(reorder, REORDER_DELAY_MS)
    }

    // The member in the hand moves to the place waited on. False when there
    // was nothing to move.
    private fun makeRoom(): Boolean {
        val from = itemId?.let(adapter::positionOf) ?: -1
        val to = target
        target = -1
        val moves = from >= 0 && to in 0 until adapter.itemCount && from != to
        if (moves) {
            // A grid that scrolls would follow a first tile that moves (its
            // layout anchors on that view); held at the place it shows instead.
            val layoutManager = grid.layoutManager
            val scrollable = grid.canScrollVertically(1) || grid.canScrollVertically(-1) ||
                grid.canScrollHorizontally(1) || grid.canScrollHorizontally(-1)
            val shown = if (scrollable) layoutManager?.onSaveInstanceState() else null
            adapter.move(from, to)
            if (shown != null) layoutManager?.onRestoreInstanceState(shown)
        }
        return moves
    }

    // The picture flies into the member's tile, which then shows again. A
    // tile scrolled out of the grid has no place to fly to: the picture just
    // goes.
    private fun settle(id: String) {
        val tile = grid.findViewHolderForAdapterPosition(adapter.positionOf(id))?.itemView
        val icon = tile?.findViewById<View>(R.id.icon)?.takeIf { it.isLaidOut }
        if (tile == null || icon == null) {
            layer.cancel()
            reveal()
            return
        }
        // Where the tile is going, not where its slide has it now.
        val rect = RectF(icon.screenRect()).apply { offset(-tile.translationX, -tile.translationY) }
        layer.settle(rect, Animations.duration(prefs(), SETTLE_MS)) { reveal() }
    }

    // The drag is over, here in the card: its tile shows again. Everything
    // a hand-off stops, stops here too.
    private fun reveal() {
        val position = itemId?.let(adapter::positionOf) ?: -1
        handOff()
        adapter.hiddenItemId = null
        if (position < 0) return
        grid.findViewHolderForAdapterPosition(position)?.itemView?.alpha = 1f
        // A copy of the tile kept off screen is rebound shown too.
        adapter.notifyItemChanged(position, REVEAL)
    }

    private companion object {
        // Launcher-style hover before the others make room, as on the home
        // screen's grid.
        const val REORDER_DELAY_MS = 250L
        // Matches the home drag's lift and settle.
        const val PICKUP_SCALE = 1.15f
        const val PICKUP_MS = 120L
        const val SETTLE_MS = 220L
        const val LAYER_Z_DP = 24
        const val REVEAL = "reveal"
    }
}

// The icon's picture at the size it is seen, and where it is on screen.
private fun iconPicture(icon: ImageView?): Pair<Bitmap, Rect>? {
    val source = icon?.screenRect()?.takeIf { it.width() > 0 && it.height() > 0 }
    val drawable = icon?.drawable
    if (source == null || drawable == null) return null
    return runCatching { drawable.toBitmap(source.width(), source.height()) }.getOrNull()?.let { it to source }
}

// The tile under a finger, matched by layout bounds: a tile sliding to its
// new place is aimed at where it is going, not where it is drawn.
private fun slotAt(grid: RecyclerView, rawX: Float, rawY: Float): Int {
    val at = IntArray(2).also(grid::getLocationOnScreen)
    val slots = (0 until grid.childCount).mapNotNull { index ->
        val child = grid.getChildAt(index)
        grid.getChildAdapterPosition(child).takeIf { it != RecyclerView.NO_POSITION }?.let { position ->
            FolderReorderMath.Slot(
                position, child.left.toFloat(), child.top.toFloat(), child.right.toFloat(), child.bottom.toFloat()
            )
        }
    }
    val rowMajor = (grid.layoutManager as? GridLayoutManager)?.orientation != RecyclerView.HORIZONTAL
    return FolderReorderMath.targetSlot(slots, rawX - at[0], rawY - at[1], rowMajor)
}

// A folder grid that scrolls, held near one of its ends during a drag: a
// vertical grid scrolls a little more each frame the deeper the finger is
// in the band; a paged one turns a page after a hover there, then keeps
// turning while the finger stays.
private class GridEdgeScroll(private val grid: RecyclerView, private val active: () -> Boolean) {

    private val handler = Handler(Looper.getMainLooper())
    private var x = 0f
    private var y = 0f
    private var scrolling = false
    private var flipSide = 0

    private val vertical: Boolean
        get() = (grid.layoutManager as? GridLayoutManager)?.orientation != RecyclerView.HORIZONTAL

    private val scroll = object : Runnable {
        override fun run() {
            val step = if (active()) step() else 0
            scrolling = step != 0
            if (scrolling) {
                grid.scrollBy(0, step)
                grid.postOnAnimation(this)
            }
        }
    }

    private val flip = object : Runnable {
        override fun run() {
            if (!active() || flipSide == 0) return
            grid.smoothScrollBy(flipSide * grid.width, 0)
            handler.postDelayed(this, PAGE_FLIP_REPEAT_MS)
        }
    }

    fun follow(rawX: Float, rawY: Float) {
        x = rawX
        y = rawY
        if (vertical) {
            if (!scrolling && step() != 0) {
                scrolling = true
                grid.postOnAnimation(scroll)
            }
            return
        }
        val at = IntArray(2).also(grid::getLocationOnScreen)
        val side = FolderReorderMath
            .edgeSide(x, at[0].toFloat(), (at[0] + grid.width).toFloat(), grid.dp(EDGE_DP).toFloat())
            .takeIf { it != 0 && grid.canScrollHorizontally(it) } ?: 0
        if (side == flipSide) return
        flipSide = side
        handler.removeCallbacks(flip)
        if (side != 0) handler.postDelayed(flip, PAGE_FLIP_DELAY_MS)
    }

    fun stop() {
        grid.removeCallbacks(scroll)
        scrolling = false
        handler.removeCallbacks(flip)
        flipSide = 0
    }

    // This frame's scroll for a vertical grid: 0 outside its edge bands, or
    // where it has nothing more to show.
    private fun step(): Int {
        val at = IntArray(2).also(grid::getLocationOnScreen)
        val step = FolderReorderMath.edgeScrollStep(
            y, at[1].toFloat(), (at[1] + grid.height).toFloat(),
            grid.dp(EDGE_DP).toFloat(), grid.dp(EDGE_SCROLL_MAX_DP).toFloat()
        ).toInt()
        return step.takeIf { it != 0 && grid.canScrollVertically(it) } ?: 0
    }

    private companion object {
        // The band at a scrolling grid's ends where a held finger scrolls it.
        const val EDGE_DP = 28
        const val EDGE_SCROLL_MAX_DP = 6
        const val PAGE_FLIP_DELAY_MS = 500L
        const val PAGE_FLIP_REPEAT_MS = 750L
    }
}
