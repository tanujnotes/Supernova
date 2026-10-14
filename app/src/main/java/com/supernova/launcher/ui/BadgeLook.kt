package com.supernova.launcher.ui

import android.app.Notification
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import androidx.core.graphics.createBitmap
import com.supernova.launcher.data.BadgeLimits
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.notification.BadgeFace

// The one place a badge's look is read out of prefs, for every surface that
// draws one (home, dock, drawer, folders, the settings preview).
class BadgeLook(private val prefs: LauncherPrefs, private val density: Float) {

    // iconColor is the app icon's dominant color: what Dots badges are
    // colored with, and what a Dynamic badge without a picture falls back to.
    fun wrap(icon: Drawable, count: Int, iconColor: Int? = null, face: Bitmap? = null): Drawable {
        val style = BadgeStyle.effective(prefs)
        val spec = BadgeSpec(
            style = style,
            color = colorFor(style, prefs, iconColor),
            position = BadgePosition.fromName(prefs.badgePosition),
            scale = prefs.badgeScale,
            textColor = LabelStyler.parseColor(prefs.badgeTextColor, BadgeDrawable.DEFAULT_TEXT_COLOR),
            cornerFraction = prefs.badgeCornerRadius / BadgeLimits.CORNER_ROUND.toFloat(),
            outline = prefs.badgeOutline
        )
        return BadgeDrawable(icon, count, spec, density, face)
    }

    companion object {
        private const val SAMPLE_COLOR = 0xFFD81B60.toInt()

        // A stand-in notification picture: a letter on a colored circle, for
        // the settings preview and the test notification.
        fun samplePicture(letter: String, px: Int, color: Int = SAMPLE_COLOR): Bitmap {
            val bitmap = createBitmap(px, px)
            MonogramDrawable(letter, color, Color.WHITE).apply {
                setBounds(0, 0, px, px)
                draw(Canvas(bitmap))
            }
            return bitmap
        }

        // A set badge color wins for Dots and Numeric; unset, Dots take the
        // icon's color as on Android 8+ and Numeric stays red.
        fun colorFor(style: BadgeStyle, prefs: LauncherPrefs, iconColor: Int?): Int {
            val chosen = prefs.badgeColor.takeIf { it.isNotBlank() }
                ?.let { LabelStyler.parseColor(it, BadgeDrawable.DEFAULT_BADGE_COLOR) }
            return when (style) {
                BadgeStyle.NUMERIC -> chosen ?: BadgeDrawable.DEFAULT_BADGE_COLOR
                BadgeStyle.DOTS -> chosen ?: iconColor ?: BadgeDrawable.DEFAULT_BADGE_COLOR
                BadgeStyle.DYNAMIC -> iconColor ?: chosen ?: BadgeDrawable.DEFAULT_BADGE_COLOR
            }
        }
    }
}

// Turns a BadgeFace into the square picture a Dynamic badge draws, once per
// notification: loading an Icon can decode a bitmap, read a content uri or
// ask for a shortcut over binder, none of which belongs in every bind.
class BadgeFaces(private val context: Context) {

    // Keyed by notification + post time (and the fallback color, which is
    // the app icon's and may not be known on the first bind), so an updated
    // notification renders afresh and an unchanged one never does. A string
    // key: the face itself would keep old notifications' pictures alive.
    private val cache = LruCache<String, Bitmap>(CACHE_ENTRIES)
    // Faces with nothing to show, so every bind doesn't try them again.
    private val failed = LruCache<String, Boolean>(CACHE_ENTRIES)
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    fun bitmapFor(face: BadgeFace, fallbackColor: Int): Bitmap? {
        val key = "${face.id}@${face.postTime}#$fallbackColor"
        val cached = cache.get(key)
        if (cached != null || failed.get(key) == true) return cached
        val bitmap = runCatching { render(face, fallbackColor) }.getOrNull()
        if (bitmap == null) failed.put(key, true) else cache.put(key, bitmap)
        return bitmap
    }

    private fun render(face: BadgeFace, fallbackColor: Int): Bitmap? {
        // Each source on its own: a picture behind a uri the launcher may not
        // read (a contact photo) throws, and the next one still stands in.
        val picture = runCatching { face.picture?.loadDrawable(context) }.getOrNull() ?: shortcutIcon(face)
        // No picture: the notification's own small icon, white on its color
        // (the shade's look), or on the app icon's color when it set none.
        val glyph = if (picture == null) {
            runCatching { face.smallIcon?.loadDrawable(context)?.mutate() }.getOrNull()
        } else {
            null
        }
        if (picture == null && glyph == null) return null
        val bitmap = createBitmap(SIZE_PX, SIZE_PX)
        val canvas = Canvas(bitmap)
        if (picture != null) {
            drawCentreCropped(picture, canvas)
        } else if (glyph != null) {
            val tint = face.color.takeIf { it != Notification.COLOR_DEFAULT && Color.alpha(it) != 0 }
            canvas.drawColor(tint ?: fallbackColor)
            glyph.setTint(Color.WHITE)
            val inset = (SIZE_PX * GLYPH_INSET).toInt()
            glyph.setBounds(inset, inset, SIZE_PX - inset, SIZE_PX - inset)
            glyph.draw(canvas)
        }
        return bitmap
    }

    private fun drawCentreCropped(picture: Drawable, canvas: Canvas) {
        val width = picture.intrinsicWidth
        val height = picture.intrinsicHeight
        if (width <= 0 || height <= 0) {
            picture.setBounds(0, 0, SIZE_PX, SIZE_PX)
        } else {
            val scale = SIZE_PX / minOf(width, height).toFloat()
            val w = (width * scale).toInt()
            val h = (height * scale).toInt()
            val left = (SIZE_PX - w) / 2
            val top = (SIZE_PX - h) / 2
            picture.setBounds(left, top, left + w, top + h)
        }
        picture.draw(canvas)
    }

    // A conversation notification's person shortcut, which carries the
    // contact's picture when the notification doesn't. Only the default
    // launcher may read shortcuts.
    private fun shortcutIcon(face: BadgeFace): Drawable? {
        val id = face.shortcutId
        val apps = launcherApps?.takeIf {
            id != null && runCatching { it.hasShortcutHostPermission() }.getOrDefault(false)
        }
        val shortcut = if (apps != null && id != null) findShortcut(apps, face, id) else null
        val dpi = context.resources.displayMetrics.densityDpi
        return shortcut?.let { runCatching { apps?.getShortcutIconDrawable(it, dpi) }.getOrNull() }
    }

    private fun findShortcut(apps: LauncherApps, face: BadgeFace, id: String): ShortcutInfo? {
        var flags = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
            LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            flags = flags or LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED
        }
        val query = LauncherApps.ShortcutQuery()
            .setPackage(face.packageName)
            .setShortcutIds(listOf(id))
            .setQueryFlags(flags)
        return runCatching { apps.getShortcuts(query, face.user) }.getOrNull()?.firstOrNull()
    }

    private companion object {
        const val SIZE_PX = 96
        const val CACHE_ENTRIES = 48
        const val GLYPH_INSET = 0.22f
    }
}
