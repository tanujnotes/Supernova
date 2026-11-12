package com.supernova.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextUtils
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// A label that runs out under a fade instead of an ellipsis: a name too long
// for its room shows as much of itself as fits, its end fading to nothing.
//
// The text lays out as on any TextView, wrapped to the width with no
// ellipsis, and while it fits in maxLines that is what draws. When it needs
// more, the lines above the last draw as laid out and the last carries the
// rest of the text on one line from its leading edge, unbroken, fading out at
// its trailing edge (the left one for right-to-left text). A one-line label
// is all last line: "Google Play Store" reads "Google Play St" fading away,
// not "Google Play" cut at the space.
//
// The fade takes the text itself away (DST_OUT in a layer), so its shadow
// fades with it and whatever is behind shows through: there is no color to
// match. Like the platform's fading edges it shortens when the text only
// just overflows, so a name a pixel too wide is not dimmed for two letters.
//
// Lines are capped with maxLines; singleLine would scroll the text sideways
// instead. An ellipsize from XML, a style or code is ignored. Compound
// drawables don't draw while the text fades; labels here have none.
class FadingTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    // The rest of the text from the last visible line on, laid out as one
    // line. TextView lays the text out anew for new text, size, typeface or
    // width, so its layout is the key.
    private var tail: Layout? = null
    private var tailSource: Layout? = null
    private var tailLine = -1

    private val matrix = Matrix()
    private var gradient: LinearGradient? = null
    private var fadePaint: Paint? = null

    override fun setEllipsize(where: TextUtils.TruncateAt?) {
        super.setEllipsize(null)
    }

    override fun onDraw(canvas: Canvas) {
        val layout = layout
        val room = width - compoundPaddingLeft - compoundPaddingRight
        // Fits in maxLines, or maxLines is -1: the height is capped some
        // other way.
        if (layout == null || maxLines !in 1 until layout.lineCount || room <= 0) {
            super.onDraw(canvas)
            return
        }
        val last = maxLines - 1
        val tail = tailOf(layout, last)
        val overflow = tail.getLineWidth(0) - room
        // More lines but a short rest: a line break in the text, nothing to
        // fade.
        if (overflow <= 0f) {
            super.onDraw(canvas)
            return
        }
        drawFaded(canvas, layout, last, tail, overflow)
    }

    // The text's room, and TextView's own clip around it: that room widened
    // by the shadow.
    private val textLeft get() = compoundPaddingLeft.toFloat()
    private val textRight get() = (width - compoundPaddingRight).toFloat()
    private val clipLeft get() = textLeft + min(0f, shadowDx - shadowRadius)
    private val clipRight get() = textRight + max(0f, shadowDx + shadowRadius)

    private fun drawFaded(canvas: Canvas, layout: Layout, last: Int, tail: Layout, overflow: Float) {
        // What TextView.onDraw sets before drawing its layout.
        paint.color = currentTextColor
        paint.drawableState = drawableState
        val clipTop = min(0f, shadowDy - shadowRadius)
        val clipBottom = height + max(0f, shadowDy + shadowRadius)
        // Where TextView puts the layout's top: past maxLines it centers the
        // visible lines through the extended padding, per the gravity.
        val originY = extendedPaddingTop.toFloat()
        canvas.withClip(clipLeft, clipTop, clipRight, clipBottom) {
            if (last > 0) {
                // The lines above the last, as laid out.
                withTranslation(textLeft, originY) {
                    val lastTop = layout.getLineTop(last).toFloat()
                    clipRect(clipLeft - textLeft, clipTop - originY, clipRight - textLeft, lastTop)
                    layout.draw(this)
                }
            }
            // On the last line's baseline, from the leading edge: a
            // right-to-left line starts at the right.
            val rtl = layout.getParagraphDirection(last) == Layout.DIR_RIGHT_TO_LEFT
            val x = if (rtl) textRight - tail.getLineRight(0) else textLeft - tail.getLineLeft(0)
            val y = originY + layout.getLineBaseline(last) - tail.getLineBaseline(0)
            val length = min(overflow, min(textSize * FADE_EMS, (textRight - textLeft) * FADE_MAX_SHARE))
            val layer = saveLayer(
                clipLeft, y + min(0f, shadowDy - shadowRadius),
                clipRight, y + tail.height + max(0f, shadowDy + shadowRadius), null
            )
            withTranslation(x, y) { tail.draw(this) }
            fadeOut(this, rtl, length)
            restoreToCount(layer)
        }
    }

    // Takes the text away over the last stretch of the room and everything
    // past it.
    private fun fadeOut(canvas: Canvas, rtl: Boolean, length: Float) {
        // Clear at 0, opaque at 1 along x; DST_OUT takes that much of the
        // text away, so 1 goes on the trailing edge and past it (CLAMP).
        val gradient = gradient
            ?: LinearGradient(0f, 0f, 1f, 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
                .also { gradient = it }
        val fade = fadePaint
            ?: Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
                .also { fadePaint = it }
        if (rtl) {
            matrix.setScale(-length, 1f)
            matrix.postTranslate(textLeft + length, 0f)
        } else {
            matrix.setScale(length, 1f)
            matrix.postTranslate(textRight - length, 0f)
        }
        gradient.setLocalMatrix(matrix)
        // Set again after each matrix, as View's own fading edges do: the
        // paint picks the shader's new state up here.
        fade.shader = gradient
        // The layer is no taller than the line, so the whole height will do.
        val top = -height.toFloat()
        val bottom = height * 2f
        if (rtl) {
            canvas.drawRect(clipLeft, top, textLeft + length, bottom, fade)
        } else {
            canvas.drawRect(textRight - length, top, clipRight, bottom, fade)
        }
    }

    private fun tailOf(layout: Layout, line: Int): Layout {
        tail?.let { if (tailSource === layout && tailLine == line) return it }
        val text = layout.text
        val start = layout.getLineStart(line)
        // A letter of slack so the line breaker never wraps it; the leading
        // edge is placed from the line's own bounds, not the layout's width.
        val width = ceil(Layout.getDesiredWidth(text, start, text.length, paint) + textSize).toInt()
        val direction = if (layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT) {
            TextDirectionHeuristics.RTL
        } else {
            TextDirectionHeuristics.LTR
        }
        return StaticLayout.Builder.obtain(text, start, text.length, paint, max(1, width))
            .setTextDirection(direction)
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            .setIncludePad(includeFontPadding)
            .setUseLineSpacingFromFallbacks(isFallbackLineSpacing)
            .setMaxLines(1)
            .build()
            .also {
                tail = it
                tailSource = layout
                tailLine = line
            }
    }

    private companion object {
        // The fade's length: about two and a half letters of the label's
        // own size, and never more than a quarter of its room.
        const val FADE_EMS = 1.2f
        const val FADE_MAX_SHARE = 0.25f
    }
}
