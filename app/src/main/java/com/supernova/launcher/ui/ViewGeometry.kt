package com.supernova.launcher.ui

import android.graphics.PointF
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.supernova.launcher.R

// A view's on-screen rect, its own scale included (edit mode zooms the
// workspace pager out). An icon image carries no scale of its own: each
// surface sizes the icon's box to its icon size instead.
internal fun View.screenRect(): Rect {
    val location = IntArray(2)
    getLocationOnScreen(location)
    val scale = screenScale()
    return Rect(
        location[0], location[1],
        location[0] + (width * scale.x).toInt(),
        location[1] + (height * scale.y).toInt()
    )
}

// How much a view is scaled on screen, its ancestors' scales included:
// edit mode shrinks the whole pager. getLocationOnScreen follows that
// and a view's own width doesn't, so screen distances measured from its
// corner are divided by this to land in its own pixels.
internal fun View.screenScale(): PointF {
    var scaleX = 1f
    var scaleY = 1f
    var current: View? = this
    while (current != null) {
        scaleX *= current.scaleX
        scaleY *= current.scaleY
        current = current.parent as? View
    }
    return PointF(scaleX.takeIf { it > 0f } ?: 1f, scaleY.takeIf { it > 0f } ?: 1f)
}


// The canvas of the page the pager is showing (or heading to).
internal fun ViewPager2.currentWorkspaceCanvas(): ViewGroup? {
    val recycler = getChildAt(0) as? RecyclerView ?: return null
    val holder = recycler.findViewHolderForAdapterPosition(currentItem) ?: return null
    return holder.itemView.findViewById(R.id.workspaceCanvas)
}
