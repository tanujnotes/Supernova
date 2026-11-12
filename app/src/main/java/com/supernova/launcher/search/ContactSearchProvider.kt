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
import com.supernova.launcher.runLogged
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
        if (trimmed.isEmpty() || !contactsGranted()) return emptyList()
        val withNumbers = withPhoneNumbers()
        // Ranked before the cap: the provider's own order says nothing about
        // how well a name matches. Photos and numbers only for the rows shown.
        return ContactRanking.top(matches(trimmed), trimmed, MAX_RESULTS).map { contact ->
            SearchResult.Contact(
                contactId = contact.contactId,
                lookupKey = contact.lookupKey,
                name = contact.name,
                photo = circularPhoto(contact.photoUri) ?: LetterTileDrawable(contact.name.first()),
                phoneNumber = if (withNumbers) firstPhoneNumber(contact.contactId) else null
            )
        }
    }

    private fun contactsGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    // Every contact the provider's filter matches (name, nickname,
    // organization), unranked.
    private fun matches(query: String): List<ContactRanking.Candidate> {
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query))
        val candidates = mutableListOf<ContactRanking.Candidate>()
        runLogged("search contacts") {
            context.contentResolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(COLUMN_NAME)
                    val lookupKey = cursor.getString(COLUMN_LOOKUP_KEY)
                    if (name.isNullOrBlank() || lookupKey == null) continue
                    candidates.add(
                        ContactRanking.Candidate(
                            contactId = cursor.getLong(COLUMN_ID),
                            lookupKey = lookupKey,
                            name = name,
                            photoUri = cursor.getString(COLUMN_PHOTO),
                            starred = cursor.getInt(COLUMN_STARRED) != 0
                        )
                    )
                }
            }
        }
        return candidates
    }

    private fun firstPhoneNumber(contactId: Long): String? = runLogged("read contact number") {
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

        private val PROJECTION = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            ContactsContract.Contacts.STARRED
        )
        private const val COLUMN_ID = 0
        private const val COLUMN_LOOKUP_KEY = 1
        private const val COLUMN_NAME = 2
        private const val COLUMN_PHOTO = 3
        private const val COLUMN_STARRED = 4
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
