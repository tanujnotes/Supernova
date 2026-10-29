package com.supernova.launcher.ui

import android.icu.text.AlphabeticIndex
import android.icu.util.ULocale
import java.util.Locale

// The fast scrollbar's letter for a name, from the device language's
// alphabet, as names are sorted (data/LabelOrder): Ärzte files under A in
// English or German, and Ä keeps a letter of its own after Z in Swedish. A
// name the alphabet has no letter for keeps its own first letter, and digits
// and symbols share "#" (FastScrollMath.plainLabel).
internal object LabelSections {

    // Built once per language: building it walks the whole alphabet.
    @Volatile
    private var index: Pair<Locale, AlphabeticIndex.ImmutableIndex<Any>>? = null

    private fun index(): AlphabeticIndex.ImmutableIndex<Any> {
        val locale = Locale.getDefault()
        index?.takeIf { it.first == locale }?.let { return it.second }
        val built = AlphabeticIndex<Any>(ULocale.forLocale(locale))
            .addLabels(Locale.ENGLISH)
            .buildImmutableIndex()
        index = locale to built
        return built
    }

    fun of(name: String): String {
        val trimmed = name.trim()
        val bucket = runCatching { index().let { it.getBucket(it.getBucketIndex(trimmed)) } }.getOrNull()
        return if (bucket != null && bucket.labelType == AlphabeticIndex.Bucket.LabelType.NORMAL &&
            bucket.label.isNotBlank()
        ) {
            bucket.label
        } else {
            FastScrollMath.plainLabel(trimmed)
        }
    }
}
