package com.supernova.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import leakcanary.LeakCanary
import shark.AndroidReferenceMatchers
import shark.ReferenceMatcher

// Tells the leak detector which retained objects are the system's doing.
//
// A bound service hands the system a binder, and the framework's binder for
// a notification listener and for an accessibility service each point back
// at the service. The system's process lets go of that binder only when its
// own garbage collector gets to it, so for a while after the service is
// switched off its destroyed instance is still reachable here, through a
// reference this process holds on the system's behalf and nothing in the
// app. What stays is the bare service, under 3 kB. The detector reported
// each one as an app leak.
//
// Filed as library leaks instead, and only on that path: a destroyed service
// the app itself still points at is reached through the app's own reference
// first, and still reported as the app's.
//
// A content provider because that is what runs before anything else in a
// debug build without the main source set knowing the detector exists.
class LeakWatchSetup : ContentProvider() {

    override fun onCreate(): Boolean {
        LeakCanary.config = LeakCanary.config.copy(
            referenceMatchers = LeakCanary.config.referenceMatchers + systemHeldServices
        )
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        private const val HELD_BY_SYSTEM =
            "The system's process holds this binder until its own garbage collector releases it; " +
                "the destroyed service it points back at goes with it. Nothing in the app holds it."

        val systemHeldServices: List<ReferenceMatcher> = listOf(
            AndroidReferenceMatchers.nativeGlobalVariableLeak(
                "android.service.notification.NotificationListenerService\$NotificationListenerWrapper",
                HELD_BY_SYSTEM
            ),
            AndroidReferenceMatchers.nativeGlobalVariableLeak(
                "android.accessibilityservice.AccessibilityService\$IAccessibilityServiceClientWrapper",
                HELD_BY_SYSTEM
            )
        )
    }
}
