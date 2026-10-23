package com.supernova.launcher.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// One notification line for the item popup menu: title/text plus the tap
// intent. Bounded to PREVIEWS_PER_APP entries per app key.
data class NotificationPreview(
    val title: String,
    val text: String,
    val contentIntent: PendingIntent?,
    val postTime: Long
)

// What a Dynamic badge shows for an app: its newest badged notification's
// sender (a chat's MessagingStyle person), else its large icon (a contact
// photo, album art), else its conversation shortcut, else its small icon on
// the notification's color. Two faces are equal when they are the same
// notification at the same post time, so a refresh that re-reads unchanged
// notifications redraws nothing.
data class BadgeFace(
    val id: String,
    val postTime: Long,
    val packageName: String,
    val user: UserHandle,
    val picture: Icon?,
    val shortcutId: String?,
    val smallIcon: Icon?,
    val color: Int
) {
    override fun equals(other: Any?): Boolean =
        other is BadgeFace && other.id == id && other.postTime == postTime

    override fun hashCode(): Int = id.hashCode() * 31 + postTime.hashCode()
}

// Every app's badge count, and what its Dynamic badge shows, keyed
// "$userHash/$packageName".
data class BadgeState(
    val counts: Map<String, Int> = emptyMap(),
    val faces: Map<String, BadgeFace> = emptyMap()
)

// Notification dots are entirely the launcher's job on Android: the platform
// exposes nothing unless we host a listener ourselves.
class SupernovaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        refresh()
    }

    override fun onListenerDisconnected() {
        connected = false
        handler.removeCallbacks(refreshRunnable)
        state.value = BadgeState()
        previews = emptyMap()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = scheduleRefresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = scheduleRefresh()

    // Whether a notification may badge lives in its ranking, and the system
    // "App icon badges" switch or a channel's badge toggle changes only that:
    // no post or removal follows, so without this the counts kept the old
    // verdict until the next notification arrived.
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = scheduleRefresh()

    // Each refresh reads every active notification back over binder, so a
    // burst of posts (a group and its children, a sync) is read once, a
    // moment after it settles, instead of once per notification.
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = Runnable { refresh() }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, REFRESH_DELAY_MS)
    }

    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val ranking = runCatching { currentRanking }.getOrNull()
        val scratch = NotificationListenerService.Ranking()
        val candidates = active.map { candidateOf(it, ranking, scratch) }
        val counted = BadgeCounting.badged(candidates).toHashSet()
        val badged = active.filterIndexed { index, _ -> candidates[index] in counted }
        // Counts and faces land together, so one redraw sees both.
        state.value = BadgeState(
            counts = BadgeCounting.counts(candidates),
            faces = badged
                .groupBy(::appKey)
                .mapValues { (_, group) -> faceOf(group.maxBy { it.postTime }) }
        )
        // Latest title/text per app for the popup menu's preview lines; kept
        // small (2 per app) and rebuilt wholesale so removals never leak.
        // The same notifications as the badge: the preview shows the content
        // of an app "with a badge".
        previews = badged
            .groupBy(::appKey)
            .mapValues { (_, group) ->
                group.sortedByDescending { it.postTime }
                    .mapNotNull(::previewOf)
                    .take(PREVIEWS_PER_APP)
            }
            .filterValues { it.isNotEmpty() }
    }

    private fun appKey(sbn: StatusBarNotification): String = "${sbn.user.hashCode()}/${sbn.packageName}"

    private fun candidateOf(
        sbn: StatusBarNotification,
        ranking: NotificationListenerService.RankingMap?,
        scratch: NotificationListenerService.Ranking
    ): BadgeCounting.Candidate {
        val notification = sbn.notification
        val extras = notification.extras
        val canShowBadge = ranking == null || !ranking.getRanking(sbn.key, scratch) || scratch.canShowBadge()
        return BadgeCounting.Candidate(
            appKey = appKey(sbn),
            number = notification.number,
            isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            canShowBadge = canShowBadge,
            clearable = sbn.isClearable,
            ongoing = sbn.isOngoing,
            hasContent = !extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() ||
                !extras.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank(),
            groupKey = sbn.groupKey.orEmpty()
        )
    }

    private fun faceOf(sbn: StatusBarNotification): BadgeFace {
        val notification = sbn.notification
        // The last message's sender: in a group chat that's who wrote, where
        // the large icon is often the group's picture.
        val sender = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
                ?.messages?.lastOrNull()?.person?.icon?.toIcon(this)
        }.getOrNull()
        return BadgeFace(
            id = sbn.key,
            postTime = sbn.postTime,
            packageName = sbn.packageName,
            user = sbn.user,
            picture = sender ?: notification.getLargeIcon(),
            shortcutId = notification.shortcutId,
            smallIcon = notification.smallIcon,
            color = notification.color
        )
    }

    private fun previewOf(sbn: StatusBarNotification): NotificationPreview? {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        return NotificationPreview(
            title = title,
            text = text,
            contentIntent = sbn.notification.contentIntent,
            postTime = sbn.postTime
        )
    }

    companion object {
        private const val PREVIEWS_PER_APP = 2
        private const val REFRESH_DELAY_MS = 100L

        @Volatile
        private var connected = false

        private val state = MutableStateFlow(BadgeState())
        val badgeState: StateFlow<BadgeState> = state

        @Volatile
        private var previews: Map<String, List<NotificationPreview>> = emptyMap()

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            val expected = ComponentName(context, SupernovaNotificationListener::class.java)
            return flat.split(':').any {
                ComponentName.unflattenFromString(it)?.packageName == expected.packageName
            }
        }

        fun countFor(packageName: String, userHash: Int): Int =
            state.value.counts["$userHash/$packageName"] ?: 0

        fun faceFor(packageName: String, userHash: Int): BadgeFace? =
            state.value.faces["$userHash/$packageName"]

        fun previewsFor(packageName: String, userHash: Int): List<NotificationPreview> =
            previews["$userHash/$packageName"].orEmpty()
    }
}
