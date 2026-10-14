package com.supernova.launcher.search

// Label matching shared by the drawer filter, the search window and card
// search. It is forgiving in three ways beyond a plain prefix, and the
// bolding shows each: a query may start at any
// word ("cal" finds Google Calendar), may run across word starts ("pla"
// finds Pro Launcher as P + La, "ym" YouTube Music), and may be one typo
// off ("pla" finds Olauncher). Tiers rank the results; ranges say which
// letters to embolden.
object AppMatcher {

    // Lower tier = better. Ranges are char indices into the label.
    class Match(val tier: Int, val ranges: List<IntRange>)

    const val TIER_PREFIX = 0
    const val TIER_WORD_PREFIX = 1
    const val TIER_SUBSTRING = 2
    // A run across word starts, or one typo: equal footing.
    const val TIER_LOOSE = 3

    fun matches(label: String, query: String): Boolean = match(label, query) != null

    fun match(label: String, query: String): Match? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val l = label.lowercase()
        if (l.startsWith(q)) return Match(TIER_PREFIX, listOf(0 until q.length))
        val starts = wordStarts(l)
        for (s in starts) {
            if (s > 0 && l.startsWith(q, s)) return Match(TIER_WORD_PREFIX, listOf(s until s + q.length))
        }
        val at = l.indexOf(q)
        if (at >= 0) return Match(TIER_SUBSTRING, listOf(at until at + q.length))
        runsFrom(l, starts, 0, q, 0)?.let { return Match(TIER_LOOSE, it) }
        if (q.length >= TYPO_MIN_CHARS) oneTypo(l, starts, q)?.let { return Match(TIER_LOOSE, listOf(it)) }
        return null
    }

    private fun isSeparator(c: Char): Boolean = c == ' ' || c == '-' || c == '_' || c == '.' || c == '&' || c == '+' || c == '/'

    private fun wordStarts(l: String): List<Int> {
        val starts = ArrayList<Int>(4)
        for (i in l.indices) {
            if (!isSeparator(l[i]) && (i == 0 || isSeparator(l[i - 1]))) starts.add(i)
        }
        return starts
    }

    // Each query letter is spent either continuing the current run or at the
    // start of a later word. Runs are tried longest first and backtrack, so
    // "pla" still finds "Plx Launcher" as P + La.
    private fun runsFrom(l: String, starts: List<Int>, from: Int, q: String, qi: Int): List<IntRange>? {
        if (qi == q.length) return emptyList()
        for (k in from until starts.size) {
            val s = starts[k]
            if (l[s] != q[qi]) continue
            var max = 0
            while (s + max < l.length && qi + max < q.length &&
                l[s + max] == q[qi + max] && (max == 0 || !isSeparator(l[s + max]))
            ) {
                max++
            }
            for (len in max downTo 1) {
                val rest = runsFrom(l, starts, k + 1, q, qi + len) ?: continue
                return listOf(s until s + len) + rest
            }
        }
        return null
    }

    // One edit against a word-start prefix: a wrong letter, two letters
    // swapped, one letter too many, or one letter missing.
    private fun oneTypo(l: String, starts: List<Int>, q: String): IntRange? {
        for (s in starts) {
            val n = q.length
            if (l.length - s >= n) {
                var mismatches = 0
                for (i in 0 until n) if (l[s + i] != q[i]) mismatches++
                if (mismatches == 1) return s until s + n
                for (i in 0 until n - 1) {
                    if (q[i] == l[s + i + 1] && q[i + 1] == l[s + i] &&
                        l.regionMatches(s, q, 0, i) && l.regionMatches(s + i + 2, q, i + 2, n - i - 2)
                    ) {
                        return s until s + n
                    }
                }
            }
            // Query has a letter too many - only once what is left is still
            // three letters, or "pla" would find every "La..." word.
            if (n > TYPO_MIN_CHARS) {
                for (i in 0 until n) {
                    if (l.regionMatches(s, q, 0, i) && l.regionMatches(s + i, q, i + 1, n - i - 1)) {
                        return s until s + n - 1
                    }
                }
            }
            // Label has a letter the query skipped.
            if (l.length - s >= n + 1) {
                for (i in 1 until n) {
                    if (l.regionMatches(s, q, 0, i) && l.regionMatches(s + i + 1, q, i, n - i)) {
                        return s until s + n + 1
                    }
                }
            }
        }
        return null
    }

    private const val TYPO_MIN_CHARS = 3
}
