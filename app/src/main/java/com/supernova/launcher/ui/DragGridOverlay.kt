package com.supernova.launcher.ui

import android.graphics.RectF
import android.view.ViewGroup
import com.supernova.launcher.R
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.WorkspaceGeometry

// The placement grid on a workspace page: cell-corner sparkles under the
// page's items and the target outline over them, the two DragGridViews the
// page's layout carries (view_workspace_page.xml). One of these drives the
// grid for one kind of gesture and remembers the page it is up on, so it can
// be taken down again: a live drag (LiveDragController) has one, and a widget
// resize (WidgetController) another, both showing the same grid (#377).
internal class DragGridOverlay(private val currentPrefs: () -> LauncherPrefs) {

    // The page showing the grid, if any; the grid's two halves are its views.
    private var page: ViewGroup? = null
    private val gridRect = RectF()
    private val pageLocation = IntArray(2)

    // One frame of the gesture: the grid on the page holding `canvas`, over
    // that canvas's cells, with the sparkles brightest at the screen point
    // (rawX, rawY) and the cells of `target` outlined (null for none). The
    // grid lives in the page, around the canvas, so it slides away with the
    // page on a flip.
    fun show(canvas: ViewGroup, target: WorkspaceGeometry.CellRect?, rawX: Float, rawY: Float) {
        val page = canvas.parent as? ViewGroup ?: return hide(animate = true)
        if (page !== this.page) hide(animate = true)
        this.page = page
        val prefs = currentPrefs()
        // The grid views fill the page, so page coordinates are theirs. Edit
        // mode scales the pager, and a resize can run on a page scaled like
        // that, so the finger is measured in the page's own pixels.
        page.getLocationOnScreen(pageLocation)
        val scale = page.screenScale()
        gridRect.set(
            canvas.left.toFloat(), canvas.top.toFloat(), canvas.right.toFloat(), canvas.bottom.toFloat()
        )
        val slideMs = Animations.duration(prefs, SLIDE_MS)
        val fadeMs = Animations.duration(prefs, FADE_MS)
        for (id in LAYERS) {
            page.findViewById<DragGridView>(id)?.update(
                prefs.gridCols, prefs.gridRows, gridRect,
                (rawX - pageLocation[0]) / scale.x, (rawY - pageLocation[1]) / scale.y,
                target, slideMs, fadeMs
            )
        }
    }

    fun hide(animate: Boolean) {
        val page = page ?: return
        this.page = null
        val fadeMs = if (animate) Animations.duration(currentPrefs(), FADE_MS) else 0L
        for (id in LAYERS) page.findViewById<DragGridView>(id)?.dismiss(fadeMs)
    }

    private companion object {
        val LAYERS = intArrayOf(R.id.dragSparkles, R.id.dragOutline)

        const val FADE_MS = 150L
        // A resize snaps to each new cell in the same time (RESIZE_SNAP_MS),
        // so the outline and the frame standing on it move as one.
        const val SLIDE_MS = 120L
    }
}
