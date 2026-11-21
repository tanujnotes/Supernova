package com.supernova.launcher.ui.settings.preview

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.ui.DrawerOpenCurve
import com.supernova.launcher.ui.DrawerTransition
import com.supernova.launcher.ui.theme.Styles
import com.supernova.launcher.ui.theme.SurfaceId

// The drawer Transition animation picker's preview (#294): a mini home page
// whose drawer opens in the chosen style, stays a moment and closes, on a
// loop. It plays the drawer's own choreography (DrawerOpenCurve): the
// desktop fades and drifts, the wallpaper dims, and the sheet slides up
// with its content arriving late, or zooms or fades in place, at the
// launcher's durations under the Animation speed pref. The sheet wears the
// drawer's colours. Built in code with its icons, never from a layout.
@android.annotation.SuppressLint("ViewConstructor")
class DrawerPreviewView(
    context: Context,
    prefs: LauncherPrefs,
    icons: List<Drawable>,
    columns: Int
) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density
    private val home = MotionPreviewParts.page(context, icons, columns)
    private val scrim = View(context).apply { setBackgroundColor(Color.BLACK) }
    // Everything on the sheet but the search pill, which rides its top edge:
    // the apps in another order than the home page's, so the drawer reads
    // as a list of its own, closer under the pill than a page under the
    // status bar.
    private val content = MotionPreviewParts.page(context, icons.reversed(), columns).apply {
        setPadding(paddingLeft, paddingBottom, paddingRight, paddingBottom)
    }
    private val sheet: FrameLayout
    private val speed = prefs.animationSpeed.factor
    private var transition = DrawerTransition.SLIDE_UP
    private val clock = PreviewClock(this, ::onFrame)
    private val decelerate = DecelerateInterpolator()
    private val accelerate = AccelerateInterpolator()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val look = Styles.resolve(context, prefs).of(SurfaceId.DRAWER)
        val corner = SHEET_CORNER_DP * density
        val pill = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = PILL_HEIGHT_DP * density / 2
                setColor(PILL_ALPHA or (look.text and RGB))
            }
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val side = (PILL_MARGIN_DP * density).toInt()
            addView(
                pill,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (PILL_HEIGHT_DP * density).toInt())
                    .apply { setMargins(side, side, side, 0) }
            )
            addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        sheet = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(corner, corner, corner, corner, 0f, 0f, 0f, 0f)
                setColor(look.surface)
            }
            addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(home, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(sheet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        draw(0f)
    }

    fun show(transition: DrawerTransition) {
        this.transition = transition
        replay()
    }

    fun replay() {
        draw(0f)
        if (isAttachedToWindow) clock.restart()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        clock.restart()
    }

    override fun onDetachedFromWindow() {
        clock.stop()
        super.onDetachedFromWindow()
    }

    private fun onFrame(elapsed: Long) {
        val base =
            if (transition == DrawerTransition.SLIDE_UP) DrawerOpenCurve.SLIDE_MS else DrawerOpenCurve.IN_PLACE_MS
        val move = (base * speed).toLong().coerceAtLeast(1L)
        val closeAt = IDLE_MS + move + HOLD_MS
        val t = elapsed % (closeAt + move + PAUSE_MS)
        draw(
            when {
                t < IDLE_MS -> 0f
                t < IDLE_MS + move -> decelerate.getInterpolation((t - IDLE_MS) / move.toFloat())
                t < closeAt -> 1f
                t < closeAt + move -> 1f - accelerate.getInterpolation((t - closeAt) / move.toFloat())
                else -> 0f
            }
        )
    }

    // The whole picture at open progress p, as the drawer draws it.
    private fun draw(p: Float) {
        val travel = height.toFloat()
        home.alpha = DrawerOpenCurve.homeAlpha(p)
        home.translationY = -DrawerOpenCurve.homeShift(p) * travel
        scrim.alpha = DrawerOpenCurve.scrimAlpha(p)
        sheet.visibility = if (p > 0f) VISIBLE else INVISIBLE
        if (transition == DrawerTransition.SLIDE_UP) {
            sheet.translationY = (1f - p) * travel
            sheet.alpha = 1f
            sheet.scaleX = 1f
            sheet.scaleY = 1f
            content.alpha = DrawerOpenCurve.contentAlpha(p)
        } else {
            val zoom = transition == DrawerTransition.ZOOM
            val scale = if (zoom) DrawerOpenCurve.ZOOM_FROM + (1f - DrawerOpenCurve.ZOOM_FROM) * p else 1f
            sheet.translationY = 0f
            sheet.alpha = p
            sheet.scaleX = scale
            sheet.scaleY = scale
            content.alpha = 1f
        }
    }

    private companion object {
        const val IDLE_MS = 700L
        const val HOLD_MS = 1000L
        const val PAUSE_MS = 300L
        const val SHEET_CORNER_DP = 16f
        const val PILL_HEIGHT_DP = 28f
        const val PILL_MARGIN_DP = 12f
        const val PILL_ALPHA = 0x24000000
        const val RGB = 0xFFFFFF
    }
}
