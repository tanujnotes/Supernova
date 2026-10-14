package com.supernova.launcher.ui.settings.preview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.widget.FrameLayout

// The pinned preview's frame under the section toolbar. For the Home screen
// page it shows the real wallpaper behind its preview: the
// settings window then shows the wallpaper under itself (SettingsActivity),
// and the pane clears its own rectangle to transparent before drawing its
// children, wiping out the screen background painted beneath it. That is how
// a SurfaceView punches its hole, and it works because nothing between here
// and the window draws into a layer of its own.
class PreviewPane @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var showsWallpaper = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun dispatchDraw(canvas: Canvas) {
        if (showsWallpaper) canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        super.dispatchDraw(canvas)
    }
}
