package com.supernova.launcher.search

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import kotlin.math.min

// Contact results for the async tier, via the contacts provider's own filter
// matching (name, nickname, organization). Runs only with READ_CONTACTS
// granted; the enable pref is enforced by the orchestrator's flag map.
// Photo thumbnails are decoded here, off the main thread, into circular
// drawables; contacts without one get a letter tile.
class ContactSearchProvider(
    private val context: Context,
    // Contact card view (D4): when on, each match also resolves its first
    // phone number so the card can offer call/message actions.
    private val withPhoneNumbers: () -> Boolean = { false }
) : SearchProvider {

    override val id: String = ID

    override fun search(query: String): List<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        val uri = Uri.withAppendedPath(
            ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(trimmed)
        )
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI
        )
        val results = mutableListOf<SearchResult>()
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext() && results.size < MAX_RESULTS) {
                    val name = cursor.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                    val lookupKey = cursor.getString(1) ?: continue
                    val contactId = cursor.getLong(0)
                    results.add(
                        SearchResult.Contact(
                            contactId = contactId,
                            lookupKey = lookupKey,
                            name = name,
                            photo = circularPhoto(cursor.getString(3))
                                ?: LetterTileDrawable(name.first()),
                            phoneNumber = if (withPhoneNumbers()) firstPhoneNumber(contactId) else null
                        )
                    )
                }
            }
        }
        return results
    }

    private fun firstPhoneNumber(contactId: Long): String? = runCatching {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

    private fun circularPhoto(photoUri: String?): Drawable? {
        photoUri ?: return null
        val bitmap = runCatching {
            context.contentResolver.openInputStream(Uri.parse(photoUri))?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull() ?: return null
        return RoundedBitmapDrawableFactory.create(context.resources, bitmap).apply {
            isCircular = true
        }
    }

    companion object {
        const val ID = "contacts"
        private const val MAX_RESULTS = 8
    }
}

// Fallback contact avatar: a tinted circle with the contact's initial. The
// hue is derived from the letter so a given contact keeps a stable color.
class LetterTileDrawable(letter: Char) : Drawable() {

    private val letterText = letter.uppercaseChar().toString()
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TILE_COLORS[letter.uppercaseChar().code % TILE_COLORS.size]
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val textBounds = Rect()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val radius = min(bounds.width(), bounds.height()) / 2f
        canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), radius, circlePaint)
        textPaint.textSize = radius
        textPaint.getTextBounds(letterText, 0, letterText.length, textBounds)
        canvas.drawText(
            letterText,
            bounds.exactCenterX(),
            bounds.exactCenterY() + textBounds.height() / 2f,
            textPaint
        )
    }

    override fun setAlpha(alpha: Int) {
        circlePaint.alpha = alpha
        textPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        circlePaint.colorFilter = colorFilter
        textPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        val TILE_COLORS = intArrayOf(
            0xFF5C6BC0.toInt(), 0xFF26A69A.toInt(), 0xFFEF5350.toInt(),
            0xFFAB47BC.toInt(), 0xFFFF7043.toInt(), 0xFF66BB6A.toInt(),
            0xFF29B6F6.toInt(), 0xFFFFA726.toInt()
        )
    }
}
