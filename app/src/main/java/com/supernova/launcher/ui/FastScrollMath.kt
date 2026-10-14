package com.supernova.launcher.ui

// The fast scrollbar's arithmetic, apart from the view: the list's letter
// sections, and where the thumb sits for the list.
internal object FastScrollMath {

    // A letter the name-sorted list reaches, from the adapter position of
    // its first item.
    data class Section(val label: String, val position: Int)

    // `initials` is the list's name-sorted run as (position, first character
    // of the lower-cased label), in list order. Each letter starts a section
    // where it first appears; digits and symbols share "#".
    fun sections(initials: List<Pair<Int, Char>>): List<Section> {
        val sections = mutableListOf<Section>()
        for ((position, initial) in initials) {
            val label = if (initial.isLetter()) initial.uppercaseChar().toString() else "#"
            if (sections.lastOrNull()?.label != label) sections += Section(label, position)
        }
        return sections
    }

    // The letter for the list at `position`: the section the item is in.
    // Above the first letter (the frequent row, folders shown first) it is
    // the first letter; a list not sorted by name has none.
    fun label(position: Int, sections: List<Section>): String? =
        (sections.lastOrNull { it.position <= position } ?: sections.firstOrNull())?.label

    // The thumb's top on a track `span` long (its length less the thumb's),
    // for a list scrolled `offset` of the `scrollable` it can scroll; null
    // when there is nothing to scroll.
    fun thumbOffset(offset: Int, scrollable: Int, span: Float): Float? {
        if (scrollable <= 0) return null
        return (offset.toFloat() / scrollable).coerceIn(0f, 1f) * span.coerceAtLeast(0f)
    }
}
