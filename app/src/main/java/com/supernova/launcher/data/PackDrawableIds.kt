package com.supernova.launcher.data

import android.content.res.Resources

// An icon pack's drawables, name to id, read in one pass over the ids. A
// pack names its icons, and another app's resources can only be asked for
// by name, but Resources.getIdentifier on a pack of thousands of drawables
// costs a fifth of a millisecond or more a name: checking an 8,000-icon
// list for Edit's picker took two seconds before it showed anything (#288).
// Asking an id for its name is a lookup, so the drawables are walked by id
// instead, from the type one known drawable is in.
object PackDrawableIds {

    // Entries past the last one tried before the walk stops: aapt packs a
    // type's entries densely, so the first miss is normally the end.
    private const val GAP = 32

    private const val TYPE_MASK = 0xFFFF0000.toInt()
    private const val ENTRY_LIMIT = 0x10000

    // Null when `probe` isn't one of the pack's drawables, or the walk
    // found nothing: callers then ask by name as before.
    @android.annotation.SuppressLint("DiscouragedApi")
    fun read(resources: Resources, packageName: String, probe: String?): Map<String, Int>? {
        val known = probe?.let { resources.getIdentifier(it, "drawable", packageName) } ?: 0
        if (known == 0) return null
        val base = known and TYPE_MASK
        val ids = HashMap<String, Int>()
        var misses = 0
        var entry = 0
        while (misses < GAP && entry < ENTRY_LIMIT) {
            val id = base or entry
            val name = runCatching { resources.getResourceEntryName(id) }.getOrNull()
            if (name == null) misses++ else {
                misses = 0
                ids[name] = id
            }
            entry++
        }
        return ids.takeIf { it.isNotEmpty() }
    }
}
