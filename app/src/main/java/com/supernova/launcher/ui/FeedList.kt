package com.supernova.launcher.ui

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

// The feed's card list. A press on a widget's own scrolling content (a
// bookmarks or notes list) is the widget's: the list scrolls under the
// finger and the feed holds still, even once the list reaches its end, as
// in the reference launcher (#354). The list took every vertical drag at
// the touch slop before, so a widget's list never moved.
class FeedRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    // True from a press on scrolling widget content until its lift.
    var widgetHolds = false
        private set

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // A press that stops a fling stays the list's, as it always was.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            widgetHolds = scrollState != SCROLL_STATE_SETTLING && scrollingWidgetAt(this, ev.rawX, ev.rawY, false)
        }
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            widgetHolds = false
        }
        return handled
    }
}

// Scrolls only while the list lets it. held: a widget has the press (and no
// card is being dragged, whose edge scroll needs the list).
class FeedLayoutManager(context: Context, private val held: () -> Boolean) : LinearLayoutManager(context) {
    override fun canScrollVertically(): Boolean = !held() && super.canScrollVertically()
}

// Whether a view under the screen point (x, y), inside a hosted widget,
// can scroll up or down.
private fun scrollingWidgetAt(view: View, x: Float, y: Float, inWidget: Boolean): Boolean {
    if (view.visibility != View.VISIBLE || !contains(view, x, y)) return false
    val widget = inWidget || view is AppWidgetHostView
    return (widget && (view.canScrollVertically(1) || view.canScrollVertically(-1))) ||
        view is ViewGroup && (0 until view.childCount).any { scrollingWidgetAt(view.getChildAt(it), x, y, widget) }
}

private val location = IntArray(2)

private fun contains(view: View, x: Float, y: Float): Boolean {
    view.getLocationOnScreen(location)
    return x >= location[0] && x < location[0] + view.width && y >= location[1] && y < location[1] + view.height
}
