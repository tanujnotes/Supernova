package com.supernova.launcher.ui.settings.preview

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.Transformation
import android.widget.FrameLayout
import com.supernova.launcher.R
import com.supernova.launcher.ui.LaunchAnimation
import kotlin.math.sin

// The App animation picker's preview (#260): a mini home page whose icon
// is tapped and opens a mini app in the chosen style, which then closes, on
// a loop. The styles the launcher hands the system as window animations of
// its own (Slide up, Blink, Slide left) play from the same anim resources;
// the ones the system builds from the icon (Reveal, Zoom) and the
// launcher's own (Circle, Adaptive icon) are drawn to their curves
// (LaunchStage). It shows what each style asks for: a phone's own system
// may play its own open animation instead (One UI does for most).
// Built in code with its icons, never from a layout.
@android.annotation.SuppressLint("ViewConstructor")
class LaunchPreviewView(
    context: Context,
    icons: List<Drawable>,
    columns: Int,
    // The tapped app's colour: Circle's fill and the mini app's bar.
    appColor: Int
) : FrameLayout(context) {

    private val home = MotionPreviewParts.page(context, icons, columns)
    // The icon that is tapped: the second one, clear of the box's corner.
    private val target: View? = ((home.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? ViewGroup)
        ?.let { row -> row.getChildAt(minOf(1, row.childCount - 1)) }
    private val stage = LaunchStage(this, home, target, appColor)

    private var style = LaunchAnimation.ADAPTIVE_ICON
    private var enter: Animation? = null
    private var exit: Animation? = null
    // Time into an anim-resource open, or -1 when none is playing.
    private var openElapsed = -1L
    private val clock = PreviewClock(this, ::onFrame)

    init {
        setStaticTransformationsEnabled(true)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        stage.reset()
    }

    fun show(style: LaunchAnimation) {
        this.style = style
        val (enterRes, exitRes) = when (style) {
            LaunchAnimation.SLIDE_UP -> R.anim.launch_slide_up_enter to R.anim.launch_slide_up_exit
            LaunchAnimation.BLINK -> R.anim.launch_blink_enter to R.anim.launch_blink_exit
            LaunchAnimation.SLIDE_LEFT -> R.anim.launch_slide_left_enter to R.anim.launch_slide_left_exit
            else -> 0 to 0
        }
        enter = enterRes.takeIf { it != 0 }?.let { AnimationUtils.loadAnimation(context, it) }
        exit = exitRes.takeIf { it != 0 }?.let { AnimationUtils.loadAnimation(context, it) }
        sizeAnimations()
        replay()
    }

    fun replay() {
        openElapsed = -1
        stage.reset()
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sizeAnimations()
    }

    private fun sizeAnimations() {
        if (width == 0) return
        enter?.apply { initialize(width, height, width, height); startTime = 0 }
        exit?.apply { initialize(width, height, width, height); startTime = 0 }
    }

    // The window anims play on this view's own clock, not the animation
    // framework's, so the system's animation scale can't skip them.
    override fun getChildStaticTransformation(child: View, t: Transformation): Boolean {
        val animation = when {
            openElapsed < 0 -> null
            child === stage.window -> enter
            child === home -> exit
            else -> null
        }
        animation?.let {
            t.clear()
            it.getTransformation(openElapsed, t)
        }
        return animation != null
    }

    private fun targetRect(): Rect? = target?.takeIf { it.width > 0 }?.let { view ->
        // The icon's own square, centred in its wider cell.
        val side = minOf(view.width, view.height)
        val left = (view.width - side) / 2
        val top = (view.height - side) / 2
        Rect(left, top, left + side, top + side).also { offsetDescendantRectToMyCoords(view, it) }
    }

    private fun onFrame(elapsed: Long) {
        val icon = targetRect() ?: return
        val t = elapsed % LOOP_MS
        openElapsed = -1
        stage.reset()
        when {
            t < IDLE_MS -> Unit
            t < OPEN_AT -> {
                // The tap: the icon dips and comes back.
                stage.reset(1f - PRESS_DEPTH * sin(Math.PI * (t - IDLE_MS) / PRESS_MS).toFloat())
            }
            t < CLOSE_AT -> open(t - OPEN_AT, icon)
            t < CLOSE_AT + CLOSE_MS -> {
                // Back home: the app fades off a home page at rest.
                stage.open(style, OPEN_MS, icon)
                stage.closing(1f - (t - CLOSE_AT) / CLOSE_MS.toFloat())
            }
            else -> Unit
        }
        invalidate()
    }

    private fun open(o: Long, icon: Rect) {
        if (enter != null) {
            stage.window.visibility = VISIBLE
            openElapsed = o
        } else {
            stage.open(style, o, icon)
        }
    }

    private companion object {
        const val IDLE_MS = 700L
        const val PRESS_MS = 160L
        const val OPEN_AT = IDLE_MS + PRESS_MS
        // Long enough for the slowest style, Blink (300ms out, then 240ms in).
        const val OPEN_MS = 600L
        const val HOLD_MS = 900L
        const val CLOSE_AT = OPEN_AT + OPEN_MS + HOLD_MS
        const val CLOSE_MS = 240L
        const val LOOP_MS = CLOSE_AT + CLOSE_MS + 300L
        const val PRESS_DEPTH = 0.1f
    }
}
