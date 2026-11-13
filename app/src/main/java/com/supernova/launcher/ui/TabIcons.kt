package com.supernova.launcher.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import com.supernova.launcher.R
import com.supernova.launcher.data.SmartCategory
import com.supernova.launcher.ui.settings.EnumLabels
import java.text.BreakIterator
import java.util.Locale

// The drawer tabs' icons, for the Icon and Label and icon tab contents. All,
// Work and Private have their own glyphs; a group tab named like a smart
// folder category gets that category's, any other its first letter in a
// ring. The tab bar tints these; a picked icon (pack, app icon or photo)
// shows in its own colours.
object TabIcons {

    @DrawableRes
    val ALL = R.drawable.tab_glyph_all

    @DrawableRes
    val WORK = R.drawable.tab_glyph_work

    @DrawableRes
    val PRIVATE = R.drawable.tab_glyph_private

    fun glyph(context: Context, @DrawableRes res: Int): Drawable =
        checkNotNull(AppCompatResources.getDrawable(context, res)).mutate()

    // A group tab with no picked icon.
    fun groupGlyph(context: Context, name: String): Drawable {
        val category = categoryFor(name) { context.getString(EnumLabels.of(it)) }
        return category?.let { glyph(context, glyphOf(it)) }
            ?: LetterGlyph(initialOf(name), context.resources.displayMetrics.density)
    }

    // The category a tab's name is the label (or the name) of, so a smart
    // folder turned into a tab, or a tab named like one, gets its glyph.
    fun categoryFor(name: String, labelOf: (SmartCategory) -> String): SmartCategory? {
        val trimmed = name.trim()
        return SmartCategory.entries.firstOrNull {
            labelOf(it).equals(trimmed, ignoreCase = true) || it.name.equals(trimmed, ignoreCase = true)
        }
    }

    // The name's first character as the reader sees it (a whole emoji or
    // accented letter), upper case; "?" for a blank name.
    fun initialOf(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "?"
        val breaks = BreakIterator.getCharacterInstance().apply { setText(trimmed) }
        val end = breaks.following(0).takeIf { it != BreakIterator.DONE } ?: trimmed.length
        return trimmed.substring(0, end).uppercase(Locale.getDefault())
    }

    @DrawableRes
    fun glyphOf(category: SmartCategory): Int = when (category) {
        SmartCategory.GAMES -> R.drawable.tab_glyph_games
        SmartCategory.ENTERTAINMENT -> R.drawable.tab_glyph_entertainment
        SmartCategory.IMAGE -> R.drawable.tab_glyph_image
        SmartCategory.SOCIAL -> R.drawable.tab_glyph_social
        SmartCategory.NEWS -> R.drawable.tab_glyph_news
        SmartCategory.MAPS -> R.drawable.tab_glyph_maps
        SmartCategory.PRODUCTIVITY -> R.drawable.tab_glyph_productivity
        SmartCategory.FINANCE -> R.drawable.tab_glyph_finance
    }

    // A picked icon: the tab bar tints every tab icon, which would turn a
    // pack icon or a photo into a flat shape, so this one ignores tints.
    class Untinted(icon: Drawable) : DrawableWrapper(icon) {
        override fun setTintList(tint: ColorStateList?) = Unit

        override fun setTintMode(tintMode: android.graphics.PorterDuff.Mode?) = Unit

        override fun setTint(tintColor: Int) = Unit
    }

    // A letter in a ring, 24dp like the glyphs, drawn in the tab bar's tint
    // for the state it is in (selected or not).
    class LetterGlyph(private val letter: String, density: Float) : Drawable() {
        private val size = (GLYPH_DP * density).toInt()
        private val stroke = RING_STROKE_DP * density
        private val inset = RING_INSET_DP * density
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = LETTER_DP * density
        }
        private val textBounds = Rect()
        private var tint: ColorStateList? = null
        private var color = android.graphics.Color.WHITE

        override fun draw(canvas: Canvas) {
            val box = bounds
            val radius = minOf(box.width(), box.height()) / 2f - stroke / 2f - inset
            ringPaint.color = color
            textPaint.color = color
            ringPaint.alpha = drawAlpha * android.graphics.Color.alpha(color) / FULL
            textPaint.alpha = ringPaint.alpha
            canvas.drawCircle(box.exactCenterX(), box.exactCenterY(), radius, ringPaint)
            textPaint.getTextBounds(letter, 0, letter.length, textBounds)
            canvas.drawText(letter, box.exactCenterX(), box.exactCenterY() - textBounds.exactCenterY(), textPaint)
        }

        private var drawAlpha = FULL

        override fun setAlpha(alpha: Int) {
            drawAlpha = alpha
            invalidateSelf()
        }

        override fun getAlpha(): Int = drawAlpha

        override fun setColorFilter(colorFilter: ColorFilter?) {
            ringPaint.colorFilter = colorFilter
            textPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = size

        override fun getIntrinsicHeight(): Int = size

        override fun setTintList(tint: ColorStateList?) {
            this.tint = tint
            onStateChange(state)
        }

        override fun isStateful(): Boolean = tint?.isStateful == true

        override fun onStateChange(state: IntArray): Boolean {
            val next = tint?.getColorForState(state, tint?.defaultColor ?: color) ?: android.graphics.Color.WHITE
            if (next == color) return false
            color = next
            invalidateSelf()
            return true
        }
    }

    private const val GLYPH_DP = 24f
    private const val LETTER_DP = 11f
    private const val RING_STROKE_DP = 1.8f
    // The ring sits a little inside the box, like the glyphs' own margins.
    private const val RING_INSET_DP = 1.5f
    private const val FULL = 255
}
