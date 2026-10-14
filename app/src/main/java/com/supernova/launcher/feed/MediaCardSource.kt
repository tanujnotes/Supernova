package com.supernova.launcher.feed

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.supernova.launcher.notification.SupernovaNotificationListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Now-playing card. getActiveSessions works for us because the notification
// listener component is ours; the same enabled check the badges flow uses
// gates every call, and a SecurityException (access revoked mid-session)
// simply hides the card. Dormant while hidden: the sessions listener and the
// controller callback exist only between onShown and onHidden.
class MediaCardSource(private val context: Context) : CardSource<MediaCardSource.MediaState> {

    // Equal when nothing shown changed, so the state flow drops the repeat
    // and the feed isn't rebuilt for it (publish reuses the artwork copy).
    data class MediaState(
        val title: String,
        val artist: String,
        // Bounded copy (<= ART_MAX_PX on the long edge), never the session's
        // original bitmap.
        val artwork: Bitmap?,
        val isPlaying: Boolean,
        val controller: MediaController
    )

    override val state = MutableStateFlow<MediaState?>(null)

    private val handler = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var shown = false

    private val listenerComponent by lazy {
        ComponentName(context, SupernovaNotificationListener::class.java)
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
            adoptController(sessions.orEmpty().filterNotNull())
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(playbackState: PlaybackState?) = publish()

        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()

        override fun onSessionDestroyed() {
            // The manager will usually follow up with a sessions-changed call;
            // drop the dead controller immediately so no control can target it.
            detachController()
            publish()
        }
    }

    override fun onShown() {
        if (shown) return
        shown = true
        if (!SupernovaNotificationListener.isEnabled(context)) {
            state.value = null
            return
        }
        val sessionManager = context.getSystemService(MediaSessionManager::class.java) ?: return
        manager = sessionManager
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                sessionsListener, listenerComponent, handler
            )
            adoptController(sessionManager.getActiveSessions(listenerComponent))
        } catch (_: SecurityException) {
            // Access revoked since the isEnabled check; behave as if disabled.
            teardown()
        }
    }

    override fun onHidden() {
        if (!shown) return
        shown = false
        teardown()
    }

    private fun teardown() {
        manager?.let {
            runCatching { it.removeOnActiveSessionsChangedListener(sessionsListener) }
        }
        manager = null
        detachController()
        state.value = null
    }

    private fun adoptController(sessions: List<MediaController>) {
        if (!shown) return
        // Prefer the session that is actually playing; else the first one
        // with anything to show.
        val next = sessions.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: sessions.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            detachController()
            controller = next?.also { it.registerCallback(controllerCallback, handler) }
        }
        publish()
    }

    private fun detachController() {
        controller?.let { runCatching { it.unregisterCallback(controllerCallback) } }
        controller = null
    }

    private fun publish() {
        val active = controller
        if (!shown || active == null) {
            state.value = null
            return
        }
        val metadata = runCatching { active.metadata }.getOrNull()
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        // A session with no metadata at all renders as an empty card; skip it.
        if (title.isBlank() && artist.isBlank()) {
            state.value = null
            return
        }
        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        state.value = MediaState(
            title = title,
            artist = artist,
            artwork = art?.let(::artworkFor),
            isPlaying = active.playbackState?.state == PlaybackState.STATE_PLAYING,
            controller = active
        )
    }

    // Every callback brings the artwork as a new bitmap, even when the
    // picture is the same, and a new scaled copy would make each state
    // unequal to the last; players that report their state every second
    // would rebuild the feed as often. The same picture keeps its copy.
    private var artFingerprint: Long? = null
    private var artCopy: Bitmap? = null

    private fun artworkFor(source: Bitmap): Bitmap {
        val fingerprint = fingerprint(source)
        artCopy?.let { if (fingerprint == artFingerprint) return it }
        return bounded(source).also {
            artFingerprint = fingerprint
            artCopy = it
        }
    }

    // Size and a sparse sample of pixels: enough to tell album covers apart.
    private fun fingerprint(bitmap: Bitmap): Long {
        var hash = bitmap.width * 31L + bitmap.height
        for (i in 0 until 16) {
            val x = (bitmap.width - 1) * (i % 4) / 3
            val y = (bitmap.height - 1) * (i / 4) / 3
            hash = hash * 31 + runCatching { bitmap.getPixel(x, y) }.getOrDefault(0)
        }
        return hash
    }

    // Session artwork can be full-resolution album art; the card draws it at
    // 56dp, so cap the copy we hold.
    private fun bounded(source: Bitmap): Bitmap {
        val longEdge = maxOf(source.width, source.height)
        if (longEdge <= ART_MAX_PX) return source
        val scale = ART_MAX_PX.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private companion object {
        const val ART_MAX_PX = 256
    }
}
