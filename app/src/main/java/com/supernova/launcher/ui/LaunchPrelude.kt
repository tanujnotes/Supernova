package com.supernova.launcher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The two launch animations that begin on the launcher's side, before the
// app is started. Both draw
// on the root's overlay, over the drawer and every other layer, and call
// back when it's the app's turn. Rects are in the root's coordinates.
class LaunchPrelude(private val root: ViewGroup) {

    private var animator: ValueAnimator? = null
    // Set once the app has been asked for: from then on nothing may cancel
    // the launch, and what is on screen only waits to be cleared.
    private var launched = false
    private var circle: CircleDrawable? = null
    private var floating: BitmapDrawable? = null
    private var hiddenIcon: View? = null
    private var hiddenIconAlpha = 1f
    private val clearLater = Runnable { clear() }

    // A prelude is playing and hasn't finished with the screen: a second
    // launch waits it out rather than stacking another over it.
    val isBusy: Boolean get() = animator != null || circle != null

    // Circle: a circle of [color] grows from [centerX, centerY] over the
    // whole root, then [onFilled] starts the app, which fades in over it.
    // The circle stays until the app covers the launcher (clear, on stop).
    fun circle(centerX: Float, centerY: Float, color: Int, durationMs: Long, onFilled: () -> Unit) {
        clear()
        val drawable = CircleDrawable(centerX, centerY, color)
        drawable.setBounds(0, 0, root.width, root.height)
        root.overlay.add(drawable)
        circle = drawable
        launched = false
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { drawable.progress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    if (cancelled) {
                        clear()
                        return
                    }
                    launched = true
                    onFilled()
                    // An app that never hides the launcher (a translucent or
                    // floating one) mustn't leave it painted over.
                    root.postDelayed(clearLater, COVER_HOLD_MS)
                }
            })
            start()
        }
    }

    // Adaptive icon: [snapshot], a picture of [icon], lifts off from
    // [iconRect] toward the root's center while the icon itself hides, and
    // [onHandOff] starts the app from the lifted picture's bounds once it has
    // nearly faded (IconLift). [speed] scales the timeline, as the animation
    // speed setting scales the launcher's own animations.
    fun liftIcon(
        icon: View,
        iconRect: RectF,
        snapshot: Bitmap,
        cellHeight: Float,
        speed: Float,
        onHandOff: (Rect) -> Unit
    ) {
        clear()
        val lift = IconLift(
            iconRect.left, iconRect.top, iconRect.width(), iconRect.height(),
            root.width.toFloat(), root.height.toFloat(), cellHeight
        )
        val drawable = BitmapDrawable(root.resources, snapshot)
        root.overlay.add(drawable)
        floating = drawable
        hiddenIcon = icon
        hiddenIconAlpha = icon.alpha
        icon.alpha = 0f
        launched = false
        val bounds = Rect()
        fun place(ms: Float) {
            val scale = 1f + (lift.endScale - 1f) * EXAGGERATED_EASE.getInterpolation(lift.scaleProgress(ms))
            val centerX = iconRect.centerX() + lift.dx * AGGRESSIVE_EASE.getInterpolation(lift.xProgress(ms))
            val centerY = iconRect.centerY() + lift.dy * AGGRESSIVE_EASE.getInterpolation(lift.yProgress(ms))
            val halfWidth = iconRect.width() * scale / 2f
            val halfHeight = iconRect.height() * scale / 2f
            bounds.set(
                (centerX - halfWidth).roundToInt(), (centerY - halfHeight).roundToInt(),
                (centerX + halfWidth).roundToInt(), (centerY + halfHeight).roundToInt()
            )
            drawable.bounds = bounds
            drawable.alpha = (lift.alpha(ms) * 255).roundToInt()
        }
        place(0f)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (IconLift.TOTAL_MS * speed).toLong().coerceAtLeast(1L)
            interpolator = LinearInterpolator()
            addUpdateListener {
                val ms = it.animatedFraction * IconLift.TOTAL_MS
                place(ms)
                if (!launched && lift.alpha(ms) < IconLift.HAND_OFF_ALPHA) {
                    launched = true
                    onHandOff(Rect(bounds))
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    // The app has had the screen since the hand-off; the
                    // icon comes back under it.
                    clear()
                }
            })
            start()
        }
    }

    // Pressing home (or anything else that pauses the launcher) before the
    // app has been asked for calls the launch off.
    fun cancelPending() {
        if (!launched) animator?.cancel()
    }

    // Everything off the screen and the tapped icon back: once the app covers
    // the launcher, or when a launch failed.
    fun clear() {
        root.removeCallbacks(clearLater)
        animator?.let {
            animator = null
            it.removeAllListeners()
            it.cancel()
        }
        circle?.let { root.overlay.remove(it) }
        circle = null
        floating?.let { root.overlay.remove(it) }
        floating = null
        hiddenIcon?.alpha = hiddenIconAlpha
        hiddenIcon = null
        launched = false
    }

    // The circle, drawn over DragLayer's children: centered on the icon,
    // its radius at full progress reaching the root's farthest corner.
    private class CircleDrawable(
        private val centerX: Float,
        private val centerY: Float,
        color: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

        var progress = 0f
            set(value) {
                field = value
                invalidateSelf()
            }

        override fun draw(canvas: Canvas) {
            if (progress <= 0f) return
            val reach = hypot(
                max(bounds.width() - centerX, centerX),
                max(bounds.height() - centerY, centerY)
            )
            canvas.drawCircle(centerX, centerY, reach * progress, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private companion object {
        // How long the filled circle may outstay a launch that never stops
        // the launcher.
        const val COVER_HOLD_MS = 1000L
        // Launcher3's AGGRESSIVE_EASE and EXAGGERATED_EASE.
        val AGGRESSIVE_EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
        val EXAGGERATED_EASE = PathInterpolator(
            android.graphics.Path().apply {
                moveTo(0f, 0f)
                cubicTo(0.05f, 0f, 0.133333f, 0.08f, 0.166666f, 0.4f)
                cubicTo(0.225f, 0.94f, 0.5f, 1f, 1f, 1f)
            }
        )
    }
}

// The Adaptive icon timeline: Launcher3 QuickStep's app-open curves, run on
// the launcher's side as a launcher can't move the app's window. The
// icon heads for the screen's center, growing toward covering the screen's
// short side, and fades out fast; once it's nearly gone the app takes over,
// the system scaling it up from wherever the icon has got to. The fractions
// are before easing; times are in ms at normal speed.
class IconLift(
    iconLeft: Float,
    iconTop: Float,
    iconWidth: Float,
    iconHeight: Float,
    windowWidth: Float,
    windowHeight: Float,
    cellHeight: Float
) {
    val dx = windowWidth / 2f - (iconLeft + iconWidth / 2f)
    val dy = windowHeight / 2f - (iconTop + iconHeight / 2f)

    // An icon in the screen's lower half, or about level with its middle,
    // rises on the long curve; one higher up drops on shorter ones.
    val upward = iconTop > windowHeight / 2f || abs(dy) < cellHeight
    val xMs = if (upward) 250f else 360f
    val yMs = if (upward) 450f else 200f
    val fadeMs = if (upward) 50f else 40f
    val endScale = max(
        min(windowWidth, windowHeight) / iconWidth,
        min(windowWidth, windowHeight) / iconHeight
    )

    // When the app is started: the first moment the icon's alpha is under
    // HAND_OFF_ALPHA.
    val handOffMs = FADE_DELAY_MS + fadeMs * (1f - HAND_OFF_ALPHA)

    fun xProgress(ms: Float) = fraction(ms, 0f, xMs)
    fun yProgress(ms: Float) = fraction(ms, 0f, yMs)
    fun scaleProgress(ms: Float) = fraction(ms, 0f, TOTAL_MS)
    fun alpha(ms: Float) = 1f - fraction(ms, FADE_DELAY_MS, fadeMs)

    private fun fraction(ms: Float, delay: Float, duration: Float) =
        ((ms - delay) / duration).coerceIn(0f, 1f)

    companion object {
        const val TOTAL_MS = 450f
        const val FADE_DELAY_MS = 25f
        const val HAND_OFF_ALPHA = 0.13f
    }
}
