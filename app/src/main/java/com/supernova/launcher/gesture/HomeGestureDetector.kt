package com.supernova.launcher.gesture

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

// Recognizes the home-screen gesture set (single/two-finger swipes, double
// tap, double-tap swipes, pinch, two-finger rotate) and reports which Gesture
// fired; binding to actions is the caller's job.
//
// Contract: this detector observes the dispatchTouchEvent stream and NEVER
// consumes events - drags, page swipes and icon taps keep working underneath.
class HomeGestureDetector(
    context: Context,
    private val onGesture: (Gesture) -> Boolean,
    // Whether the workspace pager can still scroll in a direction (+1 = to
    // the next page, -1 = to the previous). Horizontal edge swipes fire only
    // when it can't, so they never collide with a normal page swipe.
    private val canScroll: (direction: Int) -> Boolean = { true },
    // Whether a press at this screen point lands on an item of its own - an
    // icon, folder or widget - rather than on empty home screen.
    private val pressOnItem: (rawX: Float, rawY: Float) -> Boolean = { _, _ -> false }
) {

    // How far a finger travels before a press reads as a swipe.
    val minSwipeDistance = ViewConfiguration.get(context).scaledTouchSlop * SWIPE_SLOP_FACTOR

    private var twoFingerStartY = 0f
    private var twoFingerTracking = false
    private var pinchHandled = false

    // Single-finger swipe state. Tracking starts at a down THIS detector saw:
    // the activity's dispatch can hand us a lift whose down went elsewhere (a
    // modal item menu swallows the press; the drawer, search, edit-mode and
    // feed gates can open mid-gesture), and measuring that lift against a
    // stale press is how a swipe fires out of nowhere.
    private var swipeTracking = false
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    // Set by an icon that binds its own swipe in that direction; it runs its
    // action on the same lift, and both firing would be two actions from one
    // gesture. Per-direction, so an icon bound only upward leaves the home
    // swipe-down alone. Cleared on every down.
    private var suppressUp = false
    private var suppressDown = false
    // True between the second tap of a double-tap and its lift, which the
    // double-tap path owns.
    private var doubleTapActive = false
    // Where the last two presses landed, for a double tap's two taps.
    private var previousPressOnItem = false
    private var pressOnItemNow = false

    // The stream is the second press of a double tap, which DOUBLE_TAP and
    // DOUBLE_TAP_SWIPE_* own.
    val inDoubleTap: Boolean get() = doubleTapActive

    // Two-finger rotate state: pointer-to-pointer angle and distance at the
    // moment the second finger lands.
    private var rotateStartAngle = 0f
    private var rotateStartDistance = 0f
    private var rotateHandled = false

    // Second-tap-of-a-double-tap state, driven by onDoubleTapEvent.
    private var doubleTapStartX = 0f
    private var doubleTapStartY = 0f
    private var doubleTapSwiped = false

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        // DOUBLE_TAP dispatch is deferred to the second tap's UP so the same
        // touch can instead become DOUBLE_TAP_SWIPE_UP/DOWN; the events of
        // that second tap arrive via onDoubleTapEvent.
        //
        // Only on empty home screen. Two quick taps in a widget's list, or a
        // tap that opened an app followed by one more as it opened, ran the
        // double-tap action - locking the screen, say - besides.
        override fun onDoubleTap(e: MotionEvent): Boolean {
            doubleTapSwiped = false
            doubleTapActive = !previousPressOnItem && !pressOnItemNow
            return false
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (!doubleTapActive) return false
            when (e.actionMasked) {
                // onDoubleTap is handed the first tap's press; a swipe is
                // measured from the second's.
                MotionEvent.ACTION_DOWN -> {
                    doubleTapStartX = e.x
                    doubleTapStartY = e.y
                }
                MotionEvent.ACTION_MOVE -> if (!doubleTapSwiped) {
                    val dy = e.y - doubleTapStartY
                    val dx = abs(e.x - doubleTapStartX)
                    if (abs(dy) >= minSwipeDistance && abs(dy) > dx * DIRECTION_BIAS) {
                        doubleTapSwiped = true
                        onGesture(
                            if (dy < 0) Gesture.DOUBLE_TAP_SWIPE_UP else Gesture.DOUBLE_TAP_SWIPE_DOWN
                        )
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!doubleTapSwiped) onGesture(Gesture.DOUBLE_TAP)
                    doubleTapActive = false
                }
                MotionEvent.ACTION_CANCEL -> doubleTapActive = false
            }
            return false
        }

        // A press that stayed put long enough to be a long-press is not a
        // swipe, however far the finger travels afterwards. onFling used to
        // get this for free (GestureDetector suppresses the fling itself);
        // deciding at the lift means saying so by hand. The moving case - a
        // drag whose long-press timer fires mid-swipe - is cancelled at the
        // source instead, by the view that owns the press.
        override fun onLongPress(e: MotionEvent) {
            swipeTracking = false
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (pinchHandled || rotateHandled) return false
            val factor = detector.scaleFactor
            return when {
                factor < PINCH_IN_THRESHOLD -> {
                    pinchHandled = true
                    onGesture(Gesture.PINCH_IN)
                }
                factor > PINCH_OUT_THRESHOLD -> {
                    pinchHandled = true
                    onGesture(Gesture.PINCH_OUT)
                }
                else -> false
            }
        }
    })

    fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeTracking = true
                swipeDownX = event.x
                swipeDownY = event.y
                suppressUp = false
                suppressDown = false
                // Belt and braces: the double-tap's own lift clears this, but
                // a stream that ends without one must not arm the next.
                doubleTapActive = false
                // The same for the two-finger state: a pinch that opened the
                // desktop previews ends in a lift this detector never sees
                // (the previews take the stream), and left pinchHandled set -
                // the next pinch, and two-finger swipe, did nothing.
                pinchHandled = false
                rotateHandled = false
                twoFingerTracking = false
                previousPressOnItem = pressOnItemNow
                pressOnItemNow = pressOnItem(event.rawX, event.rawY)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger: whatever this becomes, it is not a
                // single-finger swipe.
                swipeTracking = false
                if (event.pointerCount == 2) {
                    twoFingerStartY = midpointY(event)
                    twoFingerTracking = true
                    rotateStartAngle = pointerAngle(event)
                    rotateStartDistance = pointerDistance(event)
                    rotateHandled = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFingerTracking && event.pointerCount == 2 && !rotateHandled && !pinchHandled) {
                    val delta = normalizedDegrees(pointerAngle(event) - rotateStartAngle)
                    val distance = pointerDistance(event)
                    val ratio = if (rotateStartDistance > 0f) distance / rotateStartDistance else 1f
                    // A real rotation keeps the pinch distance roughly stable,
                    // so it can't collide with PINCH_IN/OUT.
                    if (abs(delta) > ROTATE_THRESHOLD_DEGREES &&
                        ratio in ROTATE_MIN_DISTANCE_RATIO..ROTATE_MAX_DISTANCE_RATIO
                    ) {
                        rotateHandled = true
                        // Screen Y grows downward, so a growing atan2 angle is
                        // a clockwise rotation on screen.
                        onGesture(
                            if (delta > 0) Gesture.TWO_FINGER_ROTATE_CW else Gesture.TWO_FINGER_ROTATE_CCW
                        )
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                // The scale detector has already ended the pinch on this very
                // lift, so isInProgress alone can't tell a pinch from a swipe:
                // pinchHandled does.
                if (twoFingerTracking && event.pointerCount >= 2 &&
                    !scaleDetector.isInProgress && !rotateHandled && !pinchHandled
                ) {
                    val dy = midpointY(event) - twoFingerStartY
                    twoFingerTracking = false
                    if (abs(dy) >= minSwipeDistance) {
                        val gesture = if (dy < 0) Gesture.TWO_FINGER_SWIPE_UP else Gesture.TWO_FINGER_SWIPE_DOWN
                        if (onGesture(gesture)) return true
                    }
                }
                // The single-finger swipe is decided here, on the distance the
                // finger covered rather than on how fast it was still moving.
                if (event.actionMasked == MotionEvent.ACTION_UP) fireSwipeIfAny(event)
                if (event.actionMasked != MotionEvent.ACTION_POINTER_UP) {
                    swipeTracking = false
                    twoFingerTracking = false
                    pinchHandled = false
                    rotateHandled = false
                }
            }
        }

        // Single-finger gestures only; multitouch is handled above.
        return if (event.pointerCount == 1) tapDetector.onTouchEvent(event) else false
    }

    // An icon with its own swipe action in this direction claims the lift.
    // Called from the icon's touch listener as the press lands, which is after
    // this detector has seen the same down and cleared the flags.
    fun suppressSwipe(up: Boolean, down: Boolean) {
        if (up) suppressUp = true
        if (down) suppressDown = true
    }

    private fun fireSwipeIfAny(event: MotionEvent) {
        if (!swipeTracking || event.pointerCount != 1) return
        // The second tap of a double-tap lifts here too; that stream belongs
        // to DOUBLE_TAP / DOUBLE_TAP_SWIPE_*, which fire from the tap detector.
        if (doubleTapActive) return
        val gesture = HomeSwipeClassifier.classify(
            dx = event.x - swipeDownX,
            dy = event.y - swipeDownY,
            minDistance = minSwipeDistance.toFloat(),
            bias = DIRECTION_BIAS,
            canScroll = canScroll
        ) ?: return
        if (gesture == Gesture.SWIPE_UP && suppressUp) return
        if (gesture == Gesture.SWIPE_DOWN && suppressDown) return
        onGesture(gesture)
    }

    private fun midpointY(event: MotionEvent): Float =
        (event.getY(0) + event.getY(1)) / 2f

    private fun pointerAngle(event: MotionEvent): Float =
        Math.toDegrees(
            atan2(
                (event.getY(1) - event.getY(0)).toDouble(),
                (event.getX(1) - event.getX(0)).toDouble()
            )
        ).toFloat()

    private fun pointerDistance(event: MotionEvent): Float =
        hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))

    // Wraps an angle delta into -180..180 so crossing the atan2 seam doesn't
    // read as a full-circle spin.
    private fun normalizedDegrees(delta: Float): Float {
        var d = delta
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    companion object {
        private const val SWIPE_SLOP_FACTOR = 3
        private const val DIRECTION_BIAS = 1.2f
        private const val PINCH_IN_THRESHOLD = 0.82f
        private const val PINCH_OUT_THRESHOLD = 1.22f
        private const val ROTATE_THRESHOLD_DEGREES = 25f
        private const val ROTATE_MIN_DISTANCE_RATIO = 0.8f
        private const val ROTATE_MAX_DISTANCE_RATIO = 1.25f
    }
}
