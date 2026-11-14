package com.supernova.launcher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Picture
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import androidx.core.view.isEmpty

// Where each dock page sits. Page 0, the dock a one-page dock has and the one
// Home brings back, is drawn in the middle; the pages added after it go right,
// then left, then right again. Stored ranks stay page * dockCount + slot, so
// only the order on screen depends on this.
object DockPages {
    // The page's place on screen, 0 = leftmost.
    fun position(page: Int, pages: Int): Int {
        val middle = (pages - 1) / 2
        return when {
            page <= 0 -> middle
            page % 2 == 1 -> middle + (page + 1) / 2
            else -> middle - page / 2
        }
    }

    // The page drawn at a place on screen.
    fun pageAt(position: Int, pages: Int): Int =
        (0 until pages).firstOrNull { position(it, pages) == position } ?: 0

    // The page a flick lands on. toRight: the page to the right of this one
    // comes in (the finger moved left). wrap: infinite scroll.
    fun step(page: Int, pages: Int, toRight: Boolean, wrap: Boolean): Int {
        if (pages <= 1) return 0
        val next = position(page, pages) + if (toRight) 1 else -1
        val landed = if (wrap) next.mod(pages) else next.coerceIn(0, pages - 1)
        return pageAt(landed, pages)
    }

    // Which side `to` comes in from when the dock turns from `from`: 1 from
    // the right, -1 from the left, 0 when it is the same page.
    fun direction(from: Int, to: Int, pages: Int): Int =
        position(to, pages).compareTo(position(from, pages))
}

// The dock's page turn: the page that was showing slides out one way while the
// new page's slots slide in from the other. The old page is recorded as a
// Picture before the slots are rebuilt (its icon views are reused by the new
// page) and drawn in the bar's overlay; both stay inside the bar's padding.
class DockSlide(private val bar: ViewGroup) {
    private var running: ValueAnimator? = null

    // What the bar's slots draw now, or null when there is nothing to move.
    fun capture(): Picture? {
        if (!bar.isLaidOut || bar.width == 0 || bar.isEmpty()) return null
        val picture = Picture()
        val canvas = picture.beginRecording(bar.width, bar.height)
        for (i in 0 until bar.childCount) {
            val child = bar.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            canvas.withTranslation(child.left + child.translationX, child.top + child.translationY) {
                child.draw(this)
            }
        }
        picture.endRecording()
        return picture
    }

    // direction 1: the new page comes in from the right; -1 from the left.
    fun play(old: Picture, direction: Int, durationMs: Long) {
        cancel()
        if (direction == 0 || durationMs <= 0L) return
        val travel = (bar.width - bar.paddingLeft - bar.paddingRight).toFloat()
        val leaving = LeavingPage(old, bar)
        leaving.setBounds(0, 0, bar.width, bar.height)
        bar.overlay.add(leaving)
        fun place(progress: Float) {
            leaving.offset = -direction * travel * progress
            leaving.invalidateSelf()
            val incoming = direction * travel * (1f - progress)
            for (i in 0 until bar.childCount) bar.getChildAt(i).translationX = incoming
        }
        place(0f)
        running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = SCROLL
            addUpdateListener { place(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bar.overlay.remove(leaving)
                    for (i in 0 until bar.childCount) bar.getChildAt(i).translationX = 0f
                    if (running === animation) running = null
                }
            })
            start()
        }
    }

    // Lands a turn still in flight, so the slots it moves are back in place.
    fun cancel() {
        running?.end()
        running = null
    }

    private class LeavingPage(private val picture: Picture, private val bar: View) : Drawable() {
        var offset = 0f

        override fun draw(canvas: Canvas) {
            canvas.withClip(bar.paddingLeft, 0, bar.width - bar.paddingRight, bar.height) {
                translate(offset, 0f)
                drawPicture(picture)
            }
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private companion object {
        // Launcher3's page scroll curve (quintic ease-out), the one its paged
        // views settle with.
        val SCROLL = Interpolator { t ->
            val u = t - 1f
            u * u * u * u * u + 1f
        }
    }
}
