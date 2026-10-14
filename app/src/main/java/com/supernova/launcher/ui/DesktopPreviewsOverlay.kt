package com.supernova.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.supernova.launcher.R
import com.supernova.launcher.data.LauncherRepository

// Full-screen desktop-previews overlay (the DESKTOP_PREVIEWS action): page
// thumbnails in a horizontal row over a dim scrim, with jump / set-default /
// remove-empty / reorder / add-page controls. Thumbnails are live renders of
// the attached page views captured ONCE per (re)build at 1/4 scale; pages the
// pager has recycled (beyond its offscreen window) draw as a numbered
// placeholder instead - capturing them would mean re-parenting live widget
// views, which the workspace owns.
class DesktopPreviewsOverlay(
    private val context: Context,
    private val container: ViewGroup,
    private val host: Host
) {

    interface Host {
        fun pageCount(): Int
        fun currentPage(): Int
        fun defaultPage(): Int
        fun isPageEmpty(page: Int): Boolean
        // The live view rendering this page, when the pager still has one.
        fun pageViewFor(page: Int): View?
        fun accentColor(): Int
        fun jumpToPage(page: Int)
        fun addPage()
        fun removePage(page: Int)
        fun movePage(from: Int, to: Int)
        fun setDefaultPage(page: Int)
    }

    private val density = context.resources.displayMetrics.density

    private var overlay: FrameLayout? = null
    private var thumbRow: LinearLayout? = null
    private val bitmaps = mutableListOf<Bitmap>()

    val isShowing: Boolean get() = overlay != null

    fun show() {
        if (isShowing) return
        val scrim = FrameLayout(context).apply {
            setBackgroundColor(SCRIM_COLOR)
            isClickable = true
            setOnClickListener { dismiss() }
        }
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            // Taps on the strip's empty space fall through to the scrim.
            isClickable = false
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(24), 0, context.dp(24), 0)
        }
        scroll.addView(
            row,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        scrim.addView(
            scroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        container.addView(
            scrim,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        scrim.elevation = context.dp(24).toFloat()
        overlay = scrim
        thumbRow = row
        rebuild()
    }

    fun dismiss() {
        val scrim = overlay ?: return
        overlay = null
        thumbRow = null
        container.removeView(scrim)
        recycleBitmaps()
    }

    // Repository ops round-trip through DataStore; the caller re-invokes this
    // once the new model lands (or after a short beat).
    fun rebuild() {
        val row = thumbRow ?: return
        row.removeAllViews()
        recycleBitmaps()

        val pages = host.pageCount()
        val current = host.currentPage()
        val default = host.defaultPage()
        val accent = host.accentColor()

        for (page in 0 until pages) {
            row.addView(buildThumbColumn(page, pages, current, default, accent))
        }
        row.addView(buildAddColumn(pages))
    }

    private fun buildThumbColumn(
        page: Int,
        pages: Int,
        current: Int,
        default: Int,
        accent: Int
    ): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = context.dp(12) }
        }

        val metrics = context.resources.displayMetrics
        val thumbWidth = metrics.widthPixels / 4
        val thumbHeight = metrics.heightPixels / 4

        val thumb = FrameLayout(context).apply {
            background = thumbBackground(highlighted = page == current, accent = accent)
            layoutParams = LinearLayout.LayoutParams(thumbWidth, thumbHeight)
            contentDescription = context.getString(R.string.page_desc, page + 1, pages)
            setOnClickListener {
                host.jumpToPage(page)
                dismiss()
            }
        }
        val bitmap = capturePage(page, thumbWidth, thumbHeight)
        if (bitmap != null) {
            bitmaps.add(bitmap)
            thumb.addView(
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(bitmap)
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        } else {
            // Recycled page (outside the pager's offscreen window): a live
            // render isn't available, so number the placeholder instead.
            thumb.addView(
                TextView(context).apply {
                    text = (page + 1).toString()
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        column.addView(thumb)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(6) }
        }
        if (page > 0) {
            actions.addView(glyphButton("◀", R.string.move_page_left) {
                host.movePage(page, page - 1)
            })
        }
        actions.addView(
            glyphButton(
                if (page == default) "★" else "☆",
                R.string.set_default_page
            ) { host.setDefaultPage(page) }.apply {
                if (page == default) setTextColor(accent)
            }
        )
        if (pages > 1 && host.isPageEmpty(page)) {
            actions.addView(glyphButton("✕", R.string.remove_page) {
                host.removePage(page)
            })
        }
        if (page < pages - 1) {
            actions.addView(glyphButton("▶", R.string.move_page_right) {
                host.movePage(page, page + 1)
            })
        }
        column.addView(actions)
        return column
    }

    private fun buildAddColumn(pages: Int): View {
        val metrics = context.resources.displayMetrics
        val thumbWidth = metrics.widthPixels / 4
        val thumbHeight = metrics.heightPixels / 4
        return TextView(context).apply {
            text = "+"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            background = thumbBackground(highlighted = false, accent = 0)
            contentDescription = context.getString(R.string.add_page)
            layoutParams = LinearLayout.LayoutParams(thumbWidth, thumbHeight)
            visibility = if (pages < LauncherRepository.MAX_PAGES) View.VISIBLE else View.GONE
            setOnClickListener { host.addPage() }
        }
    }

    // ~1/4-scale live render of the page view. Runs once per (re)build.
    private fun capturePage(page: Int, thumbWidth: Int, thumbHeight: Int): Bitmap? {
        val view = host.pageViewFor(page) ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        val scale = minOf(
            thumbWidth / view.width.toFloat(),
            thumbHeight / view.height.toFloat()
        )
        val bitmap = Bitmap.createBitmap(
            (view.width * scale).toInt().coerceAtLeast(1),
            (view.height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        runCatching { view.draw(canvas) }
        return bitmap
    }

    private fun thumbBackground(highlighted: Boolean, accent: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(THUMB_BG)
            cornerRadius = context.dp(12).toFloat()
            if (highlighted) setStroke(context.dp(2), accent) else setStroke(context.dp(1), THUMB_STROKE)
        }

    private fun glyphButton(glyph: String, descriptionRes: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = glyph
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            contentDescription = context.getString(descriptionRes)
            setPadding(context.dp(10), context.dp(6), context.dp(10), context.dp(6))
            setOnClickListener { onClick() }
        }

    private fun recycleBitmaps() {
        bitmaps.forEach { it.recycle() }
        bitmaps.clear()
    }


    private companion object {
        // 70%-black dim, matching the immersive folder scrim.
        const val SCRIM_COLOR = -0x4D000000
        const val THUMB_BG = 0x33000000
        const val THUMB_STROKE = 0x66FFFFFF
    }
}
