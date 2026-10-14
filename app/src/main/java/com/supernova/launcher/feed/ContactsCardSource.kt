package com.supernova.launcher.feed

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.ContactsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Starred-contacts card: a row of circular photo/letter tiles. READ_CONTACTS
// is already in the manifest (Phase 3 contact search) but stays runtime-gated
// here with the same in-card grant state the calendar card uses. One query on
// show; no observer - the starred set changes rarely and re-queries on every
// feed open anyway.
class ContactsCardSource(
    private val context: Context,
    private val scope: CoroutineScope
) : CardSource<ContactsCardSource.ContactsState> {

    sealed class ContactsState {
        object NeedsPermission : ContactsState()
        data class Contacts(val contacts: List<Starred>) : ContactsState()
    }

    data class Starred(
        val contactId: Long,
        val lookupKey: String,
        val displayName: String,
        // Thumbnail decoded and held at tile size; null renders a letter tile.
        val photo: Bitmap?
    )

    override val state = MutableStateFlow<ContactsState?>(null)

    private var shown = false
    private var queryJob: Job? = null

    private fun granted(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    override fun onShown() {
        if (shown) return
        shown = true
        refresh()
    }

    override fun onHidden() {
        if (!shown) return
        shown = false
        queryJob?.cancel()
        queryJob = null
        state.value = null
    }

    fun refresh() {
        if (!shown) return
        if (!granted()) {
            state.value = ContactsState.NeedsPermission
            return
        }
        queryJob?.cancel()
        queryJob = scope.launch {
            val starred = withContext(Dispatchers.IO) { queryStarred() }
            if (shown) state.value = ContactsState.Contacts(starred)
        }
    }

    private fun queryStarred(): List<Starred> = runCatching {
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            "${ContactsContract.Contacts.STARRED} = 1",
            null,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )?.use { cursor ->
            val contacts = ArrayList<Starred>()
            while (cursor.moveToNext() && contacts.size < MAX_CONTACTS) {
                val id = cursor.getLong(0)
                contacts.add(
                    Starred(
                        contactId = id,
                        lookupKey = cursor.getString(1).orEmpty(),
                        displayName = cursor.getString(2).orEmpty(),
                        photo = loadThumbnail(id)
                    )
                )
            }
            contacts
        }
    }.getOrNull().orEmpty()

    private fun loadThumbnail(contactId: Long): Bitmap? = runCatching {
        val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, contactUri)
            ?.use(BitmapFactory::decodeStream)
    }.getOrNull()

    private companion object {
        const val MAX_CONTACTS = 8
    }
}
