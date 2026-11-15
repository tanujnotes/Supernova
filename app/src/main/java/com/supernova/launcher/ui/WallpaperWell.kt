package com.supernova.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.LinearLayout

// A rounded hole through a sheet: before drawing its children it clears its
// own rounded box in the sheet's (translucent) window, wiping out the sheet
// painted beneath it, so what lies under the window shows through - the
// wallpaper, when the launcher's content behind is faded out (AppEditSheet).
// The settings Home preview clears its pane the same way; it holds while
// nothing between here and the window draws into a layer of its own.
class WallpaperWell @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val box = RectF()
    private val radius = RADIUS_DP * resources.displayMetrics.density

    init {
        setWillNotDraw(false)
    }

    override fun dispatchDraw(canvas: Canvas) {
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(box, radius, radius, clear)
        super.dispatchDraw(canvas)
    }

    private companion object {
        const val RADIUS_DP = 20f
    }
}
