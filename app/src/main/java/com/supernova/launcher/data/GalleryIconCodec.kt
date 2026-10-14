package com.supernova.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

// Turns a user-picked gallery image into the base64 PNG stored on an
// AppOverride, and back into a drawable for the icon pipeline. Stored inline
// so backups carry custom icons without touching external storage.
object GalleryIconCodec {

    const val ICON_SIZE = 192

    // Encoded-string budget; oversized images are rescaled until they fit.
    private const val MAX_ENCODED_CHARS = 200 * 1024
    private const val MIN_SIZE = 48

    fun encode(context: Context, uri: Uri): String? {
        val source = decodeSampled(context, uri) ?: return null
        try {
            var size = ICON_SIZE
            while (size >= MIN_SIZE) {
                val encoded = encodeAt(source, size)
                if (encoded.length <= MAX_ENCODED_CHARS) return encoded
                size /= 2
            }
            return null
        } finally {
            source.recycle()
        }
    }

    // Snapshots a drawable (an activity or legacy-shortcut icon) to the same
    // inline base64 PNG an AppOverride uses, so home items keep their icon
    // across restarts and backups without touching external storage.
    fun encode(drawable: android.graphics.drawable.Drawable): String? {
        val source = drawableToBitmap(drawable) ?: return null
        return try {
            var size = ICON_SIZE
            while (size >= MIN_SIZE) {
                val encoded = encodeAt(source, size)
                if (encoded.length <= MAX_ENCODED_CHARS) return encoded
                size /= 2
            }
            null
        } finally {
            source.recycle()
        }
    }

    fun encode(bitmap: Bitmap): String? {
        var size = ICON_SIZE
        while (size >= MIN_SIZE) {
            val encoded = encodeAt(bitmap, size)
            if (encoded.length <= MAX_ENCODED_CHARS) return encoded
            size /= 2
        }
        return null
    }

    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): Bitmap? {
        (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let { bmp ->
            return bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false)
        }
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: ICON_SIZE
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: ICON_SIZE
        return runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            bmp
        }.getOrNull()
    }

    fun decode(base64: String): Bitmap? = runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun encodeAt(source: Bitmap, size: Int): String {
        val cropped = centerCrop(source, size)
        val stream = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.PNG, 100, stream)
        cropped.recycle()
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun centerCrop(source: Bitmap, size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val srcSide = minOf(source.width, source.height)
        val left = (source.width - srcSide) / 2
        val top = (source.height - srcSide) / 2
        canvas.drawBitmap(
            source,
            Rect(left, top, left + srcSide, top + srcSide),
            Rect(0, 0, size, size),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return output
    }

    // ImageDecoder turns the picture the way the photo's orientation tag
    // says; BitmapFactory ignored the tag, so a portrait phone photo became
    // a sideways icon (and stayed so in backups). Sampled while decoding, so
    // a camera-sized image never materializes at full resolution just to
    // become a 192px icon, and in software memory, which the crop's canvas
    // needs to read it.
    private fun decodeSampled(context: Context, uri: Uri): Bitmap? = runCatching {
        val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
        android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val shortSide = minOf(info.size.width, info.size.height)
            var sample = 1
            while (shortSide / (sample * 2) >= ICON_SIZE) sample *= 2
            decoder.setTargetSampleSize(sample)
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()
}
