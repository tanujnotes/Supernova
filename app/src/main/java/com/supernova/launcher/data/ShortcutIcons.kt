package com.supernova.launcher.data

import android.graphics.Bitmap
import android.graphics.drawable.Drawable

// A shortcut's icon as its Edit left it, drawn as an app's icon with the same
// edit would be: a picked photo or pack icon, else the shortcut's own picture
// in the picked shape and style. Original is the picture unshaped and without
// glass; Themed is its themed layer (monochrome, else silhouette) in the
// themed colours. The catalog hands over its renderer and the steps it shares
// with app icons, under its render lock.
internal class ShortcutIcons(
    private val renderer: IconRenderer,
    private val customShape: CustomShapeSpec?,
    private val globalShape: IconShape,
    private val packDrawable: (pack: String, name: String) -> Drawable?,
    private val pickedPicture: (Bitmap, IconShape) -> Drawable,
    private val themedLayer: (Drawable) -> Drawable
) {

    // `own` is the shortcut's icon (for Themed, the app's own copy when it
    // could be had); `ownPhoto` its stored picture when that is one that
    // should fill the shape (a contact's photo).
    fun draw(edit: AppOverride, own: Drawable?, ownPhoto: Bitmap?): Drawable? {
        val original = edit.iconVariant == ORIGINAL
        val shape = if (original) IconShape.SYSTEM else edit.iconShape?.let(IconShape::fromName) ?: globalShape
        val glass = if (original) IconGlass.OFF else renderer.glass
        if (hasPickedIcon(edit)) return picked(edit, shape, glass)
        return own?.let { ownIcon(it, ownPhoto, edit, shape, glass) }
    }

    // A photo or pack icon picked in the Edit, as one picked for an app.
    private fun picked(edit: AppOverride, shape: IconShape, glass: IconGlass): Drawable? {
        val photo = edit.iconBase64?.let(GalleryIconCodec::decode)
        val pack = edit.iconPackPackage
        val icon = if (photo == null && pack != null) edit.iconDrawableName?.let { packDrawable(pack, it) } else null
        return when {
            photo != null -> pickedPicture(photo, shape)
            icon == null -> null
            AppIconSource.isAppIcon(pack) -> renderer.renderOwnIcon(icon, shape, customShape, glass)
            else -> renderer.render(icon, null, shape, customShape, IconGlass.OFF)
        }
    }

    private fun ownIcon(own: Drawable, ownPhoto: Bitmap?, edit: AppOverride, shape: IconShape, glass: IconGlass) =
        when (edit.iconVariant) {
            ORIGINAL -> renderer.render(own, null, shape, customShape, IconGlass.OFF)
            THEMED -> renderer.render(themedLayer(own), null, shape, customShape, glass)
            else -> ownPhoto?.let { renderer.renderPicture(it, shape, customShape) }
                ?: renderer.render(own, null, shape, customShape, glass)
        }

    companion object {
        private const val ORIGINAL = "ORIGINAL"
        private const val THEMED = "THEMED"

        fun hasPickedIcon(edit: AppOverride): Boolean = edit.iconBase64 != null || edit.iconDrawableName != null

        // An edit that only hides the label draws the shortcut as before.
        fun changesIcon(edit: AppOverride): Boolean =
            hasPickedIcon(edit) || edit.iconShape != null || edit.iconVariant != null
    }
}
