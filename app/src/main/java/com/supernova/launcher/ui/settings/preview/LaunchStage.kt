package com.supernova.launcher.ui.settings.preview

import android.animation.TimeInterpolator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.supernova.launcher.R
import com.supernova.launcher.ui.LaunchAnimation
import kotlin.math.hypot
import kotlin.math.max

// The App animation preview's actors over its mini home page - the
// circle, the mini app and the lifted icon - and how each style the
// launcher or the system draws from the icon moves them at a time into the
// open (LaunchPreviewView keeps the clock and the anim-resource styles).
internal class LaunchStage(
    private val host: FrameLayout,
    private val home: View,
    private val target: View?,
    appColor: Int
) {
    private val context: Context = host.context
    private val density = context.resources.displayMetrics.density
    private val targetIcon = (target as? ImageView)?.drawable
    private val circle = CircleView(context, appColor)
    val window: FrameLayout = appWindow(appColor)
    private val lift = ImageView(context).apply {
        targetIcon?.constantState?.newDrawable(context.resources)?.mutate()?.let(::setImageDrawable)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private val decelerate = DecelerateInterpolator(DECELERATE)
    private val aggressive = PathInterpolator(AGGRESSIVE_X1, 0f, 0f, 1f)
    private val accelerateCubic = AccelerateInterpolator(ACCELERATE_CUBIC)

    init {
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        host.addView(home, FrameLayout.LayoutParams(match, match))
        host.addView(circle, FrameLayout.LayoutParams(match, match))
        host.addView(window, FrameLayout.LayoutParams(match, match))
        val size = (MotionPreviewParts.ICON_DP * density).toInt()
        host.addView(lift, FrameLayout.LayoutParams(size, size))
    }

    // Everything at rest: the home page alone, its tapped icon at `press`
    // of its size.
    fun reset(press: Float = 1f) {
        listOf(home, window, lift, circle, target).forEach { view ->
            view ?: return@forEach
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationX = 0f
            view.translationY = 0f
            view.clipBounds = null
        }
        window.pivotX = 0f
        window.pivotY = 0f
        window.visibility = View.INVISIBLE
        lift.visibility = View.INVISIBLE
        circle.radius = 0f
        circle.visibility = View.INVISIBLE
        target?.scaleX = press
        target?.scaleY = press
    }

    // `o` ms into the open of a style drawn here.
    fun open(style: LaunchAnimation, o: Long, icon: Rect) {
        window.visibility = View.VISIBLE
        when (style) {
            LaunchAnimation.ADAPTIVE_ICON -> adaptive(o, icon)
            LaunchAnimation.CIRCLE -> circle(o, icon)
            LaunchAnimation.ZOOM -> scaleUp(fraction(o, ZOOM_MS, decelerate), icon)
            // Reveal, and System, whose launch is the same clip reveal. The
            // anim-resource styles end with the window in place, as here.
            else -> reveal(fraction(o, REVEAL_MS, decelerate), icon)
        }
    }

    // The app fading off the home page, `alpha` of the way back in.
    fun closing(alpha: Float) {
        window.alpha = alpha
        circle.alpha = alpha
        lift.visibility = View.INVISIBLE
        target?.alpha = 1f
    }

    private fun fraction(o: Long, duration: Long, curve: TimeInterpolator): Float =
        curve.getInterpolation((o / duration.toFloat()).coerceIn(0f, 1f))

    // The window's clip grows from the icon's bounds to the whole screen.
    private fun reveal(f: Float, icon: Rect) {
        window.clipBounds = Rect(
            lerp(icon.left.toFloat(), 0f, f).toInt(), lerp(icon.top.toFloat(), 0f, f).toInt(),
            lerp(icon.right.toFloat(), host.width.toFloat(), f).toInt(),
            lerp(icon.bottom.toFloat(), host.height.toFloat(), f).toInt()
        )
    }

    // The window grows out of the icon's bounds.
    private fun scaleUp(f: Float, icon: Rect) {
        window.scaleX = lerp(icon.width() / host.width.toFloat(), 1f, f)
        window.scaleY = lerp(icon.height() / host.height.toFloat(), 1f, f)
        window.translationX = lerp(icon.left.toFloat(), 0f, f)
        window.translationY = lerp(icon.top.toFloat(), 0f, f)
    }

    // The icon lifts toward the centre, swelling as it fades, and the app
    // scales up out of it.
    private fun adaptive(o: Long, icon: Rect) {
        target?.alpha = 0f
        lift.visibility = View.VISIBLE
        val p = fraction(o, LIFT_MS, aggressive)
        val grow = lerp(1f, max(host.width, host.height) / icon.width().toFloat() * LIFT_COVER, p)
        lift.translationX = lerp(icon.left.toFloat(), (host.width - icon.width()) / 2f, p)
        lift.translationY = lerp(icon.top.toFloat(), (host.height - icon.height()) / 2f, p)
        lift.scaleX = grow
        lift.scaleY = grow
        lift.alpha = 1f - ((o - LIFT_FADE_AT) / LIFT_FADE_MS.toFloat()).coerceIn(0f, 1f)
        if (o < SCALE_UP_AT) {
            window.visibility = View.INVISIBLE
        } else {
            scaleUp(fraction(o - SCALE_UP_AT, ZOOM_MS, decelerate), icon)
        }
    }

    // The icon's colour fills the screen as a circle from it, then the app
    // fades in (launch_fade_enter).
    private fun circle(o: Long, icon: Rect) {
        circle.visibility = View.VISIBLE
        circle.cx = icon.exactCenterX()
        circle.cy = icon.exactCenterY()
        val far = hypot(max(circle.cx, host.width - circle.cx), max(circle.cy, host.height - circle.cy))
        circle.radius = far * fraction(o, CIRCLE_MS, decelerate)
        window.alpha = fraction(o - CIRCLE_MS, FADE_MS, accelerateCubic)
    }

    private fun lerp(a: Float, b: Float, f: Float): Float = a + (b - a) * f

    // The mini app: a bar in its colour over a screen of its tint with its icon
    // in the middle, as a splash screen shows it.
    private fun appWindow(appColor: Int): FrameLayout = FrameLayout(context).apply {
        // Tinted with its colour, so it stands off the sheet's own surface.
        val screen = androidx.core.graphics.ColorUtils.blendARGB(context.getColor(R.color.surface), appColor, APP_TINT)
        setBackgroundColor(screen)
        addView(
            View(context).apply { setBackgroundColor(appColor) },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (BAR_DP * density).toInt())
        )
        val size = (APP_ICON_DP * density).toInt()
        addView(
            ImageView(context).apply {
                targetIcon?.constantState?.newDrawable(context.resources)?.mutate()?.let(::setImageDrawable)
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        )
    }

    private class CircleView(context: Context, color: Int) : View(context) {
        var cx = 0f
        var cy = 0f
        var radius = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

        override fun onDraw(canvas: Canvas) {
            if (radius > 0f) canvas.drawCircle(cx, cy, radius, paint)
        }
    }

    private companion object {
        const val REVEAL_MS = 350L
        const val ZOOM_MS = 300L
        // Launcher3's floating icon: 450ms on its aggressive ease, gone
        // 25-75ms in, the app scaling up from it at about 60ms.
        const val LIFT_MS = 450L
        const val LIFT_FADE_AT = 25L
        const val LIFT_FADE_MS = 50L
        const val LIFT_COVER = 0.5f
        const val SCALE_UP_AT = 60L
        const val AGGRESSIVE_X1 = 0.2f
        // LauncherActivity's CIRCLE_FILL_MS and launch_fade_enter's length.
        const val CIRCLE_MS = 280L
        const val FADE_MS = 150L
        const val DECELERATE = 1.5f
        const val ACCELERATE_CUBIC = 1.5f
        const val BAR_DP = 28
        const val APP_ICON_DP = 48
        const val APP_TINT = 0.14f
    }
}
