package com.supernova.launcher.ui

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.max

enum class ScrollEffect {
    NONE,
    SIMPLE,
    ZOOM,
    ACCORDION,
    FLIP,
    DEPTH,
    CUBE,
    CARD_STACK,
    TABLET,
    REVOLVING_DOOR,
    FLIP_VERTICAL,
    IN_AND_OUT,
    GLASS,
    WIPE,
    ZOOM_FADE,
    THROW;

    companion object {
        fun fromName(name: String): ScrollEffect =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NONE
    }
}

object PageTransformers {

    fun forEffect(effect: ScrollEffect): ViewPager2.PageTransformer? {
        val transform: (View, Float) -> Unit = when (effect) {
            ScrollEffect.NONE -> return null
            ScrollEffect.SIMPLE -> ::simple
            ScrollEffect.ZOOM -> ::zoom
            ScrollEffect.ACCORDION -> ::accordion
            ScrollEffect.FLIP -> ::flip
            ScrollEffect.DEPTH -> ::depth
            ScrollEffect.CUBE -> ::cube
            ScrollEffect.CARD_STACK -> ::cardStack
            ScrollEffect.TABLET -> ::tablet
            ScrollEffect.REVOLVING_DOOR -> ::revolvingDoor
            ScrollEffect.FLIP_VERTICAL -> ::flipVertical
            ScrollEffect.IN_AND_OUT -> ::inAndOut
            ScrollEffect.GLASS -> ::glass
            ScrollEffect.WIPE -> ::wipe
            ScrollEffect.ZOOM_FADE -> ::zoomFade
            ScrollEffect.THROW -> ::throwAway
        }
        return ViewPager2.PageTransformer { page, position ->
            // A page a whole page or more away is out of view in a plain
            // slide, and no effect may bring it back. Home pages are see-
            // through, so one held or turned into view shows: at rest Card
            // stack kept the previous page under the one in view, and mid-
            // swipe Cube swung the page two away past edge-on, mirrored, into
            // the screen. Each effect fades or turns its pages out by then.
            //
            // And put back where a plain slide has it: at rest a neighbour is
            // last called here at exactly one page away, so it kept the
            // transform of the swipe's final frame. Under Glass that is the
            // next page held 98% over the one in view, a level above it -
            // invisible, yet taking every touch: a long-press there opened
            // edit mode, or the menu of an icon on the hidden page, whose
            // Remove took that unseen icon while the one in view stayed.
            if (abs(position) >= 1f) {
                resetTransforms(page)
                page.alpha = 0f
            } else {
                page.alpha = 1f
                transform(page, position)
            }
        }
    }

    // Transformers only restore what they themselves mutate; call this on the
    // live pages when the effect pref changes so nothing stale survives the
    // switch (a FLIP rotationX would otherwise stick under CUBE).
    fun resetTransforms(page: View) {
        page.translationX = 0f
        page.translationY = 0f
        page.translationZ = 0f
        page.scaleX = 1f
        page.scaleY = 1f
        page.rotation = 0f
        page.rotationX = 0f
        page.rotationY = 0f
        page.alpha = 1f
        // Back to the automatic centre, which follows the page's size. Set
        // by hand, a page bound before its first layout (width 0) kept its
        // pivot at the corner, and the scaling effects shrank pages into
        // their top-left.
        page.resetPivot()
        page.clipBounds = null
    }

    private fun simple(page: View, position: Float) {
        // The outgoing page lags the finger a little and the incoming one
        // leads, most at mid-swipe; both sit exactly a page apart at the
        // ends. A lag proportional to the position alone left each
        // neighbour 15% into the screen at rest, its edge column showing.
        page.translationX = -position * (1 - abs(position)) * page.width * SIMPLE_PARALLAX * 2
        page.alpha = 1f
    }

    private fun zoom(page: View, position: Float) {
        val scale = max(MIN_SCALE, 1 - abs(position))
        page.scaleX = scale
        page.scaleY = scale
        page.alpha = max(MIN_ALPHA, 1 - abs(position))
    }

    private fun accordion(page: View, position: Float) {
        page.pivotX = if (position < 0) page.width.toFloat() else 0f
        page.scaleX = 1 - abs(position)
        page.alpha = max(MIN_ALPHA, 1 - abs(position))
    }

    private fun flip(page: View, position: Float) {
        page.cameraDistance = FLIP_CAMERA_DISTANCE * page.resources.displayMetrics.density
        page.pivotX = page.width / 2f
        page.pivotY = page.height / 2f
        page.rotationY = 90f * position
        page.alpha = if (abs(position) > 0.5f) 0f else 1f
    }

    private fun depth(page: View, position: Float) {
        if (position <= 0f) {
            // Outgoing page slides out normally; the incoming one rises from
            // behind it.
            page.translationX = 0f
            page.translationZ = 0f
            page.scaleX = 1f
            page.scaleY = 1f
            page.alpha = 1f
        } else {
            page.translationX = -page.width * position
            // Under the outgoing page, which the drawing order alone would
            // put it over.
            page.translationZ = -1f
            val scale = MIN_SCALE + (1 - MIN_SCALE) * (1 - abs(position))
            page.scaleX = scale
            page.scaleY = scale
            // Gone by the time it is fully behind. Held at MIN_ALPHA, the
            // next page sat over the one in view at rest, its icons a
            // faded, shrunken ghost on top.
            page.alpha = (1 - position).coerceIn(0f, 1f)
        }
    }

    private fun cube(page: View, position: Float) {
        page.pivotX = if (position < 0) page.width.toFloat() else 0f
        page.pivotY = page.height / 2f
        page.rotationY = 90f * position
    }

    private fun cardStack(page: View, position: Float) {
        if (position <= 0f) {
            // Outgoing holds still and fades while the incoming card covers
            // it. A home page is see-through, so it must be gone once
            // covered, not merely dimmed.
            page.translationX = -position * page.width
            page.translationZ = 0f
            page.alpha = (1f + position).coerceIn(0f, 1f)
            val scale = 1f + position * CARD_SHRINK
            page.scaleX = scale
            page.scaleY = scale
        } else {
            page.translationX = 0f
            page.translationZ = 1f
            page.alpha = 1f
            page.scaleX = 1f
            page.scaleY = 1f
        }
    }

    private fun tablet(page: View, position: Float) {
        page.cameraDistance = FLIP_CAMERA_DISTANCE * page.resources.displayMetrics.density
        page.pivotX = page.width / 2f
        page.pivotY = page.height / 2f
        page.rotationY = -TABLET_ANGLE * position
        page.alpha = 1f
    }

    private fun revolvingDoor(page: View, position: Float) {
        // Cube's seam-edge pivot, but pages swing through the viewer's plane.
        page.cameraDistance = FLIP_CAMERA_DISTANCE * page.resources.displayMetrics.density
        page.pivotX = if (position < 0) page.width.toFloat() else 0f
        page.pivotY = page.height / 2f
        page.rotationY = -90f * position
    }

    private fun flipVertical(page: View, position: Float) {
        page.cameraDistance = FLIP_CAMERA_DISTANCE * page.resources.displayMetrics.density
        // Cancel the slide so the flip happens in place around the x axis.
        page.translationX = -position * page.width
        page.pivotX = page.width / 2f
        page.pivotY = page.height / 2f
        page.rotationX = -90f * position
        page.alpha = if (abs(position) > 0.5f) 0f else 1f
    }

    private fun inAndOut(page: View, position: Float) {
        val f = abs(position).coerceAtMost(1f)
        // 0 at rest and at the neighbour slot, deepest mid-transition.
        val dip = 1f - abs(1f - 2f * f)
        val scale = 1f - IN_OUT_DEPTH * dip
        page.scaleX = scale
        page.scaleY = scale
        page.alpha = 1f
    }

    private fun glass(page: View, position: Float) {
        // Static cross-fade; the incoming pane settles down from a scale-up.
        page.translationX = -position * page.width
        page.alpha = max(0f, 1f - abs(position))
        val scale = if (position > 0f) 1f + GLASS_SCALE * position else 1f
        page.scaleX = scale
        page.scaleY = scale
        page.translationZ = if (position > 0f) 1f else 0f
    }

    private fun wipe(page: View, position: Float) {
        if (position < 0f) {
            // Outgoing stays put at full strength, cut off where the incoming
            // page's edge - the wipe front - has reached. Home pages are
            // see-through, so covering it isn't enough: unclipped, both
            // pages' icons overlapped until it vanished at the end.
            page.translationX = -position * page.width
            page.translationZ = 0f
            page.alpha = 1f
            val visible = ((1f + position) * page.width).toInt().coerceAtLeast(0)
            page.clipBounds = android.graphics.Rect(0, 0, visible, page.height)
        } else {
            page.translationX = 0f
            page.translationZ = 1f
            page.alpha = 1f
            page.clipBounds = null
        }
    }

    private fun zoomFade(page: View, position: Float) {
        val f = abs(position).coerceAtMost(1f)
        val scale = 1f - ZOOM_FADE_DEPTH * f
        page.scaleX = scale
        page.scaleY = scale
        page.alpha = 1f - f
    }

    private fun throwAway(page: View, position: Float) {
        if (position <= 0f) {
            // Outgoing flies up and away with a tilt, above the incoming
            // page, which slides in flat underneath.
            page.translationX = -position * page.width * THROW_HOLD
            page.translationY = position * page.height * THROW_LIFT
            page.translationZ = 1f
            page.rotation = THROW_ROTATION * position
            page.alpha = max(0f, 1f + position)
        } else {
            page.translationX = 0f
            page.translationY = 0f
            page.translationZ = 0f
            page.rotation = 0f
            page.alpha = 1f
        }
    }

    private const val MIN_SCALE = 0.72f
    private const val MIN_ALPHA = 0.35f
    private const val FLIP_CAMERA_DISTANCE = 12000f
    private const val SIMPLE_PARALLAX = 0.15f
    private const val CARD_SHRINK = 0.06f
    private const val TABLET_ANGLE = 20f
    private const val IN_OUT_DEPTH = 0.3f
    private const val GLASS_SCALE = 0.08f
    private const val ZOOM_FADE_DEPTH = 0.25f
    private const val THROW_HOLD = 0.5f
    private const val THROW_LIFT = 0.7f
    private const val THROW_ROTATION = 20f
}
