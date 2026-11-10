package com.supernova.launcher.ui

import android.view.View
import android.view.ViewTreeObserver

// A view-tree listener kept only while the view is in a window: `add` runs
// when it goes into one (or at once, if it is in one), `remove` when it
// comes out.
//
// The observer such a listener goes on belongs to the window, not the view,
// and an activity that is recreated can be handed its old window to draw
// in. A listener left on it then outlives the screen it was for: it keeps
// that screen's views alive and, through them, the whole old activity, and
// goes on being called on every layout or frame of the new one.
fun View.watchTreeWhileInWindow(add: ViewTreeObserver.() -> Unit, remove: ViewTreeObserver.() -> Unit) {
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = view.viewTreeObserver.add()
        override fun onViewDetachedFromWindow(view: View) = view.viewTreeObserver.remove()
    })
    if (isAttachedToWindow) viewTreeObserver.add()
}
