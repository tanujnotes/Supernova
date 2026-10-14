package com.supernova.launcher.notification

// Which notifications count toward an app's badge, and how much each adds.
// Launcher3's rules:
// - a notification the app (or the user, per channel) set not to badge
//   doesn't count;
// - a group summary doesn't count while its group has children, badged or
//   not: they are the messages, and counting both showed a chat's two
//   messages as 3 (a summary posted alone, with no children, still counts);
// - neither does anything ongoing or not clearable (music, downloads,
//   navigation), nor a notification with no title and no text;
// - one notification counts as its own number when the app set one (a
//   "5 new messages" summary line is 5), and as 1 otherwise.
object BadgeCounting {

    // The parts of a StatusBarNotification (and its Ranking) the rules read,
    // so they can be tested without the framework.
    data class Candidate(
        val appKey: String,
        val number: Int,
        val isGroupSummary: Boolean,
        val canShowBadge: Boolean,
        val clearable: Boolean,
        val ongoing: Boolean,
        val hasContent: Boolean,
        // StatusBarNotification.groupKey: which summary a child belongs to.
        val groupKey: String = ""
    )

    // The candidates that count, in their order.
    fun badged(candidates: List<Candidate>): List<Candidate> {
        val groupsWithChildren = candidates.filterNot { it.isGroupSummary }.mapTo(HashSet()) { it.groupKey }
        return candidates.filter { eligible(it) && (!it.isGroupSummary || it.groupKey !in groupsWithChildren) }
    }

    fun counts(candidates: List<Candidate>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (candidate in badged(candidates)) {
            counts[candidate.appKey] = (counts[candidate.appKey] ?: 0) + candidate.number.coerceAtLeast(1)
        }
        return counts
    }

    // Everything but the summary rule, which needs the whole group.
    private fun eligible(candidate: Candidate): Boolean =
        candidate.canShowBadge &&
            candidate.clearable &&
            !candidate.ongoing &&
            candidate.hasContent
}
