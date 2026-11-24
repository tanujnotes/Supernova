package com.supernova.launcher.notification

// The badge report the Test notification badge writes to the crash log
// (#344): per app, how many notifications the listener saw and why the ones
// that don't badge were dropped. Package names and counts only, no content.
object BadgeReport {

    data class Seen(
        val packageName: String,
        val userId: Int,
        val candidate: BadgeCounting.Candidate,
        val counted: Boolean,
        // Whether the system's ranking knew the notification at all.
        val ranked: Boolean,
        // Do Not Disturb set to hide badges, and this one was caught by it.
        val dndHidesBadge: Boolean
    )

    private const val MAX_APPS = 30

    fun format(
        enabled: Boolean,
        connected: Boolean,
        systemBadging: Int,
        refreshedAgoMs: Long,
        seen: List<Seen>
    ): String =
        buildString {
            append("access ").append(if (enabled) "on" else "off")
            append(", listener ").append(if (connected) "connected" else "not connected")
            append(", system badging ").append(
                when (systemBadging) {
                    -1 -> "unset"
                    0 -> "off"
                    else -> "on"
                }
            )
            append(", last read ").append(if (refreshedAgoMs < 0) "never" else "${refreshedAgoMs / MS_PER_S}s ago")
            append(", ").append(seen.size).append(" notifications\n")
            seen.groupBy { "${it.userId}/${it.packageName}" }.entries.take(MAX_APPS).forEach { (key, list) ->
                append("  ").append(key).append(": ").append(list.size).append(" seen, ")
                append(list.count { it.counted }).append(" badged")
                val reasons = list.filterNot { it.counted }.flatMap(::reasons)
                if (reasons.isNotEmpty()) {
                    append(" (")
                    append(reasons.groupingBy { it }.eachCount().entries.joinToString { "${it.key} ${it.value}" })
                    append(")")
                }
                append('\n')
            }
        }

    // Every rule a dropped notification failed; a group summary with
    // children is the one left when none did.
    internal fun reasons(seen: Seen): List<String> {
        val c = seen.candidate
        val out = mutableListOf<String>()
        if (!c.canShowBadge) out += if (seen.dndHidesBadge) "dnd hides badge" else "system says no badge"
        if (!c.clearable) out += "not clearable"
        if (c.ongoing) out += "ongoing"
        if (!c.hasContent) out += "no title/text"
        if (out.isEmpty()) out += if (c.isGroupSummary) "group summary" else "other"
        if (!seen.ranked) out += "unranked"
        return out
    }

    private const val MS_PER_S = 1000
}
