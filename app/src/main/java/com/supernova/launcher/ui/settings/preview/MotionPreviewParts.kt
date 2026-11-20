package com.supernova.launcher.ui.settings.preview

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.ui.LauncherViewModel

// What the motion previews (Page scroll effect, App animation) share: a
// frame clock of their own, the wallpaper's colours behind a mini home page,
// and sample icons.
//
// The clock reads the uptime clock on every frame rather than running an
// animator, so a preview plays at its real speed whatever the system's
// animation setting: an animator at scale 0 would only jump to its end.
internal class PreviewClock(private val host: View, private val onFrame: (Long) -> Unit) {

    private var running = false
    private var start = 0L

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            onFrame(SystemClock.uptimeMillis() - start)
            host.postOnAnimation(this)
        }
    }

    // Starts the loop again from its beginning.
    fun restart() {
        start = SystemClock.uptimeMillis()
        if (running) return
        running = true
        host.postOnAnimation(frame)
    }

    fun stop() {
        running = false
        host.removeCallbacks(frame)
    }
}

internal object MotionPreviewParts {

    const val ICON_DP = 40
    private const val PAGE_PAD_DP = 12
    private const val FALLBACK_TOP = 0xFF3B4A6B.toInt()
    private const val FALLBACK_BOTTOM = 0xFF1C2230.toInt()

    // The wallpaper as a gradient of its own main colours. Its picture would
    // need a storage permission; its colours don't.
    fun wallpaperBackground(context: Context, cornerPx: Float): Drawable {
        val colors = runCatching {
            WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        }.getOrNull()
        val top = colors?.primaryColor?.toArgb() ?: FALLBACK_TOP
        val bottom = colors?.secondaryColor?.toArgb()
            ?: colors?.primaryColor?.toArgb()?.let(::darker)
            ?: FALLBACK_BOTTOM
        val stops = intArrayOf(top or OPAQUE, bottom or OPAQUE)
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, stops).apply { cornerRadius = cornerPx }
    }

    private const val OPAQUE = 0xFF000000.toInt()
    private const val DARKEN = 0.6f

    private fun darker(color: Int): Int = Color.rgb(
        (Color.red(color) * DARKEN).toInt(), (Color.green(color) * DARKEN).toInt(), (Color.blue(color) * DARKEN).toInt()
    )

    // The first apps of the drawer with icons of their own copies: the
    // catalogue's drawables are shared, and a preview draws them at its own
    // size.
    fun sampleIcons(context: Context, viewModel: LauncherViewModel, count: Int): List<Pair<LaunchableApp, Drawable>> {
        val catalog = viewModel.appCatalog()
        return viewModel.visibleApps().asSequence()
            .mapNotNull { app ->
                catalog.iconFor(app)?.let { it.constantState?.newDrawable(context.resources)?.mutate() ?: it }
                    ?.let { app to it }
            }
            .take(count)
            .toList()
    }

    // A mini home page: `icons` in rows of `columns`, from the top. The
    // icons sit in the page's first child, a column of rows.
    fun page(context: Context, icons: List<Drawable>, columns: Int): FrameLayout {
        val density = context.resources.displayMetrics.density
        val size = (ICON_DP * density).toInt()
        val pad = (PAGE_PAD_DP * density).toInt()
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        icons.chunked(columns).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (index in 0 until columns) {
                val view = ImageView(context).apply {
                    chunk.getOrNull(index)?.let(::setImageDrawable)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                row.addView(view, LinearLayout.LayoutParams(0, size, 1f))
            }
            rows.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = pad }
            )
        }
        return FrameLayout(context).apply {
            setPadding(pad, pad * 2, pad, pad)
            addView(
                rows,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            )
        }
    }
}
