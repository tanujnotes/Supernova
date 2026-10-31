package com.supernova.launcher.data

import java.text.CollationKey
import java.text.Collator
import java.util.Locale

// Names in the device language's alphabetical order, as a person reads them:
// Ä files with A in English or German, and after Z in Swedish, whose alphabet
// ends that way. Case aside, as before. Comparing the characters' codes put
// every name starting with an accented letter after Z.
object LabelOrder {

    // A Collator is no use shared across threads, so each sort takes its own.
    fun collator(locale: Locale = Locale.getDefault()): Collator =
        Collator.getInstance(locale).apply { strength = Collator.SECONDARY }

    fun <T> by(locale: Locale = Locale.getDefault(), label: (T) -> CharSequence): Comparator<T> {
        val collator = collator(locale)
        return Comparator { a, b -> collator.compare(label(a).toString(), label(b).toString()) }
    }

    // Each name keyed once, rather than once per comparison: the drawer's
    // lists are sorted on the main thread. Equal names keep their order.
    fun <T> sorted(
        items: List<T>,
        descending: Boolean = false,
        locale: Locale = Locale.getDefault(),
        label: (T) -> CharSequence
    ): List<T> {
        val collator = collator(locale)
        val keyed = items.map { collator.getCollationKey(label(it).toString()) to it }
        val byKey = compareBy<Pair<CollationKey, T>> { it.first }
        return keyed.sortedWith(if (descending) byKey.reversed() else byKey).map { it.second }
    }
}
