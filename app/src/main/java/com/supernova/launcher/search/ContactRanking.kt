package com.supernova.launcher.search

import com.supernova.launcher.data.LabelOrder
import java.text.Normalizer
import java.util.Locale

// Orders the contacts provider's filter matches before the results are
// capped. The provider hands its matches back in no useful order (on most
// phones the order the contacts were added), so keeping its first rows could
// drop the very name typed: "Ale" lost to older "Alexander"s. The whole name
// typed ranks first, then the app matcher's tiers; a contact the provider
// matched on something other than its name (a nickname, a company) comes
// last. Starred contacts lead within a rank, then names in alphabetical
// order. A contact the provider repeats is kept once.
object ContactRanking {

    class Candidate(
        val contactId: Long,
        val lookupKey: String,
        val name: String,
        val photoUri: String? = null,
        val starred: Boolean = false
    )

    const val RANK_EXACT = 0
    // AppMatcher's tiers follow, shifted past the exact rank.
    const val RANK_OTHER = AppMatcher.TIER_TYPO + 2

    fun top(
        candidates: List<Candidate>,
        query: String,
        limit: Int,
        locale: Locale = Locale.getDefault()
    ): List<Candidate> {
        val folded = fold(query.trim())
        if (folded.isEmpty()) return emptyList()
        return candidates
            .distinctBy { it.contactId }
            .map { it to rank(it.name, folded) }
            .sortedWith(
                compareBy<Pair<Candidate, Int>> { it.second }
                    .thenByDescending { it.first.starred }
                    .then(LabelOrder.by(locale) { it.first.name })
            )
            .take(limit)
            .map { it.first }
    }

    // query: already folded and trimmed.
    fun rank(name: String, query: String): Int {
        val folded = fold(name.trim())
        return if (folded.equals(query, ignoreCase = true)) {
            RANK_EXACT
        } else {
            AppMatcher.match(folded, query)?.let { it.tier + 1 } ?: RANK_OTHER
        }
    }

    // Accents aside, the way the provider matched them: "ale" finds "Álex"
    // there, so it ranks with the other "Ale…" names here.
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
}
