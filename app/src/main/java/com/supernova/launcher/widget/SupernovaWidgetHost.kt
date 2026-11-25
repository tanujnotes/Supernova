package com.supernova.launcher.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Outline
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.TextView
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

// Host returning our own host-view subclass so every created widget gets the
// corner clip and the long-press interception.
class SupernovaWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {

    companion object {
        // The launcher's one host id. Shared with SettingsActivity's reset flow
        // and PinRequestActivity, which free or allocate widget ids through a
        // host addressed by this same id.
        const val HOST_ID = 0xBEE
    }
    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?
    ): AppWidgetHostView = SupernovaWidgetHostView(context)
}

// Jobs the stock AppWidgetHostView can't do:
//
// 1. Corner clip that survives updates. On API 31+ the framework re-runs its
//    own rounded-corner enforcement on every RemoteViews apply, replacing any
//    outline set once at creation - so the radius is re-asserted after every
//    update instead.
// 2. Long-press through interactive widgets. List/button children consume the
//    touch stream, so a listener on the host never fires; a timeout watcher on
//    the intercept pass performs the long-click and then steals the stream
//    (the children receive a cancel), mirroring Launcher3's helper.
// 3. Reporting when the hosted content is driving the gesture, so the home
//    swipe gestures can stay off a widget the user is scrolling.
class SupernovaWidgetHostView(context: Context) : AppWidgetHostView(context) {

    init {
        // The framework insets widget content with a default padding, which
        // parks the drawn card well inside the host - the corner clip at the
        // host bounds then never reaches visible pixels. The launcher supplies
        // its own spacing (the widgetPadding pref), so the host stays
        // edge-to-edge.
        setPadding(0, 0, 0, 0)
        // 4. Scrolling text. A widget's marquee runs only while its TextView
        //    holds focus (RemoteViews can't select it), and the launcher's
        //    window has one focus: a page change, the drawer search or a
        //    re-attach took it away and the text stopped at "..." (#325).
        //    Selected also runs a marquee, and every widget's can at once,
        //    so each marquee TextView that took focus (see requestChildFocus)
        //    is selected whenever content lands (applies may finish later,
        //    on the inflation executor), and selected again after a
        //    re-attach, which stops a running one. Marquee text that never
        //    asked for focus stays at "...", as in any other host (#370).
        setOnHierarchyChangeListener(object : OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                post {
                    WidgetMarquees.select(this@SupernovaWidgetHostView, marqueeHolders, restart = false)
                }
            }

            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })
        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                post { WidgetMarquees.select(v, marqueeHolders, restart = true) }
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    // Marquee TextViews that took focus in this widget: the ones a widget
    // marks with <requestFocus/>, which hold focus (and scroll) in a stock
    // host. A request made during inflation reaches here when the focused
    // view is added, before onChildViewAdded; list rows bubble up the same
    // way. Weak, so replaced content drops out.
    private val marqueeHolders: MutableSet<TextView> = Collections.newSetFromMap(WeakHashMap())

    override fun requestChildFocus(child: View?, focused: View?) {
        if (focused is TextView && focused.ellipsize == TextUtils.TruncateAt.MARQUEE) {
            marqueeHolders.add(focused)
        }
        super.requestChildFocus(child, focused)
    }

    var cornerRadiusPx: Float = 0f
        set(value) {
            field = value
            applyCornerClip()
        }

    private val outline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
        }
    }

    // The S+ host applies RemoteViews asynchronously and finishes each apply
    // by installing or resetting its own corner enforcement through these two
    // public setters - long after any synchronous re-assertion. While the
    // launcher owns a radius, competing writes are ignored.
    override fun setOutlineProvider(provider: ViewOutlineProvider?) {
        if (cornerRadiusPx > 0f && provider !== outline) return
        super.setOutlineProvider(provider)
    }

    override fun setClipToOutline(clip: Boolean) {
        if (cornerRadiusPx > 0f && !clip) return
        super.setClipToOutline(clip)
    }

    override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
        super.setAppWidget(appWidgetId, info)
        // setAppWidget is where the framework applies its default padding.
        setPadding(0, 0, 0, 0)
        applyCornerClip()
    }

    private fun applyCornerClip() {
        if (cornerRadiusPx > 0f) {
            super.setOutlineProvider(outline)
            super.setClipToOutline(true)
            invalidateOutline()
        } else {
            super.setOutlineProvider(ViewOutlineProvider.BACKGROUND)
            super.setClipToOutline(false)
        }
    }

    private var longPressFired = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressCheck = Runnable {
        if (parent != null && hasWindowFocus() && !longPressFired) {
            longPressFired = performLongClick()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressFired = false
                contentOwnsTouch = hasScrollableContent()
                downX = ev.x
                downY = ev.y
                postDelayed(longPressCheck, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) {
                    removeCallbacks(longPressCheck)
                }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPressCheck)
        }
        return longPressFired
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            // A press no child took comes here directly, past the intercept
            // above, so the slop check has to be made here too: without it a
            // slow swipe starting on a bare part of a widget opened the
            // widget's menu part way and picked the widget up.
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) {
                    removeCallbacks(longPressCheck)
                }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressCheck)
                longPressFired = false
            }
        }
        // Consume the stolen stream so an intercepted gesture doesn't fall
        // through to the workspace beneath the widget.
        return true
    }

    // True while the hosted content is driving the current gesture: either it
    // could already scroll where the finger landed, or a child asked its
    // parents to stop intercepting - what AbsListView, ScrollView and the
    // stack/flipper widgets all do the moment they start moving. The launcher
    // reads this so a scroll inside a widget isn't also read as a home swipe
    // (see LauncherActivity.trackWidgetScroll).
    var contentOwnsTouch = false
        private set

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // Requests bubble up from any depth, so this covers grandchildren too.
        if (disallowIntercept) contentOwnsTouch = true
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    // Scrollable content announces itself up front, before the finger has
    // moved far enough for anything to claim the stream.
    fun hasScrollableContent(): Boolean = scrollsVertically(this)

    // The workspace pager calls this on children when it takes over the
    // gesture for a page swipe.
    override fun cancelLongPress() {
        super.cancelLongPress()
        removeCallbacks(longPressCheck)
        longPressFired = false
    }
}

private fun scrollsVertically(view: View): Boolean =
    view.canScrollVertically(1) || view.canScrollVertically(-1) ||
        view is ViewGroup && (0 until view.childCount).any { i ->
            val child = view.getChildAt(i)
            child.visibility == View.VISIBLE && scrollsVertically(child)
        }

// Keeps the marquees of a widget's focus-taking TextViews running by
// selecting them.
private object WidgetMarquees {
    fun select(view: View, holders: Set<TextView>, restart: Boolean) {
        if (view is TextView) {
            if (view in holders && view.ellipsize == TextUtils.TruncateAt.MARQUEE) {
                if (restart && view.isSelected) view.isSelected = false
                view.isSelected = true
            }
            return
        }
        if (view !is ViewGroup) return
        for (i in 0 until view.childCount) select(view.getChildAt(i), holders, restart)
    }
}
