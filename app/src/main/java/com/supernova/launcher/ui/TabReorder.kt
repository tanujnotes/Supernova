package com.supernova.launcher.ui

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.google.android.material.tabs.TabLayout
import com.supernova.launcher.data.DrawerGroup
import kotlin.math.abs

// A group tab held and then moved along the drawer's tab bar: its menu goes,
// the tab follows the finger, the group tabs it passes step aside, and the
// lift stores the new order. All, Work and Private keep their places; a
// group tab moves among the group tabs only. Near either end of a bar wider
// than the screen, the bar scrolls under the held tab.
//
// The bar is rebuilt from the stored order (the tab views are pooled and
// reused), so the moved views keep their offsets until then: reset() clears
// them as the rebuild starts, and a lift that changed nothing clears them at
// once.
class TabReorder(
    private val tabs: TabLayout,
    // The bar's indicator colour, put back when a drag ends.
    private val indicatorColor: () -> Int,
    // The group tabs' names in their new order.
    private val onMoved: (List<String>) -> Unit
) {
    private val slop = ViewConfiguration.get(tabs.context).scaledTouchSlop

    // The press under way on a tab view.
    private var downRawX = 0f
    private var armedView: View? = null
    private var closeMenu: (() -> Unit)? = null

    // The drag, once the held tab has moved.
    private var dragging: View? = null
    private var groupViews: List<View> = emptyList()
    private var names: List<String> = emptyList()
    private var from = 0
    private var target = 0
    private var startScrollX = 0
    private var indicatorHidden = false
    private var lastRawX = 0f
    private var edgeStep = 0
    private val edgeScroll = object : Runnable {
        override fun run() {
            if (dragging == null || edgeStep == 0) return
            val before = tabs.scrollX
            tabs.scrollBy(edgeStep, 0)
            if (tabs.scrollX != before) follow(lastRawX)
            tabs.postOnAnimation(this)
        }
    }

    // Every tab view of the bar, after each rebuild: the press is watched on
    // all of them (pooled views change tabs), and acted on only once arm()
    // says the press became a group tab's menu.
    @SuppressLint("ClickableViewAccessibility")
    fun watch(view: View) {
        view.setOnTouchListener { v, ev -> onTouch(v, ev) }
    }

    // The tab's long-press opened its menu: a move from here is a drag. Only
    // a group tab among two or more has anywhere to go.
    fun arm(view: View, closeMenu: () -> Unit) {
        if (groupTabs().size < 2 || groupTabs().none { it.view === view }) return
        armedView = view
        this.closeMenu = closeMenu
        // The bar would scroll, and the drawer close, on the move to come.
        view.parent?.requestDisallowInterceptTouchEvent(true)
    }

    // The rebuild is under way: the views go back to where they lay out.
    fun reset() {
        tabs.removeCallbacks(edgeScroll)
        edgeStep = 0
        for (i in 0 until tabs.tabCount) {
            tabs.getTabAt(i)?.view?.apply {
                animate().cancel()
                translationX = 0f
                translationZ = 0f
            }
        }
        if (indicatorHidden) tabs.setSelectedTabIndicatorColor(indicatorColor())
        indicatorHidden = false
        dragging = null
    }

    private fun groupTabs(): List<TabLayout.Tab> =
        (0 until tabs.tabCount).mapNotNull { tabs.getTabAt(it) }.filter { it.tag is DrawerGroup }

    // True once the press is a drag: the tab's own click and long-press
    // see none of it.
    private fun onTouch(view: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                armedView = null
                closeMenu = null
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging == null && armedView === view && abs(ev.rawX - downRawX) > slop) start(view)
                if (dragging === view) move(ev.rawX)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                armedView = null
                closeMenu = null
                if (dragging === view) {
                    drop(cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL)
                    return true
                }
            }
        }
        return dragging === view
    }

    private fun move(rawX: Float) {
        lastRawX = rawX
        follow(rawX)
        edgeStep = edgeStepAt(rawX)
        tabs.removeCallbacks(edgeScroll)
        if (edgeStep != 0) tabs.postOnAnimation(edgeScroll)
    }

    private fun start(view: View) {
        closeMenu?.invoke()
        closeMenu = null
        val group = groupTabs()
        groupViews = group.map { it.view }
        names = group.map { (it.tag as DrawerGroup).name }
        from = groupViews.indexOf(view)
        target = from
        startScrollX = tabs.scrollX
        dragging = view
        view.translationZ = view.resources.displayMetrics.density * LIFT_DP
        // The selection's indicator stays where the tab was; hidden, the
        // held tab carries the selection.
        // Not Color.TRANSPARENT, which the bar takes for "no tint" and draws
        // the indicator in its drawable's own white.
        tabs.setSelectedTabIndicatorColor(HIDDEN_INDICATOR)
        indicatorHidden = true
        // The press the tab's own handling never sees the end of.
        view.isPressed = false
        view.cancelLongPress()
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    // The held tab under the finger, kept within the group tabs' span, and
    // the tabs between its old and new place moved over by its width.
    private fun follow(rawX: Float) {
        val view = dragging ?: return
        val first = groupViews.first()
        val last = groupViews.last()
        val dx = (rawX - downRawX + (tabs.scrollX - startScrollX))
            .coerceIn((first.left - view.left).toFloat(), (last.right - view.right).toFloat())
        view.translationX = dx
        // Past a tab's middle with its leading edge, the held tab takes
        // that tab's place: a wide tab passes a narrow one, too.
        val centres = groupViews.map { it.left + it.width / 2f }
        target = from
        for (i in from + 1 until groupViews.size) {
            if (view.right + dx > centres[i]) target = i
        }
        for (i in from - 1 downTo 0) {
            if (view.left + dx < centres[i]) target = i
        }
        groupViews.forEachIndexed { i, other ->
            if (other === view) return@forEachIndexed
            val shift = when {
                i in (from + 1)..target -> -view.width.toFloat()
                i in target until from -> view.width.toFloat()
                else -> 0f
            }
            if (other.translationX != shift) other.animate().translationX(shift).setDuration(SHIFT_MS).start()
        }
    }

    private fun edgeStepAt(rawX: Float): Int {
        val location = IntArray(2)
        tabs.getLocationOnScreen(location)
        val zone = tabs.resources.displayMetrics.density * EDGE_ZONE_DP
        val step = (tabs.resources.displayMetrics.density * EDGE_STEP_DP).toInt()
        return when {
            rawX < location[0] + zone -> -step
            rawX > location[0] + tabs.width - zone -> step
            else -> 0
        }
    }

    private fun drop(cancelled: Boolean) {
        tabs.removeCallbacks(edgeScroll)
        edgeStep = 0
        val moved = !cancelled && target != from
        if (!moved) {
            reset()
            return
        }
        val order = names.toMutableList().apply { add(target, removeAt(from)) }
        onMoved(order)
        // Fallback for a store that never answers with the new order.
        tabs.postDelayed({ if (dragging == null && tabs.isAttachedToWindow) reset() }, RESET_FALLBACK_MS)
        dragging = null
    }

    private companion object {
        const val LIFT_DP = 6
        const val HIDDEN_INDICATOR = 0x00000001
        const val SHIFT_MS = 150L
        const val EDGE_ZONE_DP = 40
        const val EDGE_STEP_DP = 6
        const val RESET_FALLBACK_MS = 600L
    }
}
