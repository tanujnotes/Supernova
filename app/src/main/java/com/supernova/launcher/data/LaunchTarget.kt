package com.supernova.launcher.data

// An app or a shortcut to launch, stored as "pkg/cls@serial" or
// "pkg|shortcutId@serial": gesture payloads, card targets, shortcut pills.
object LaunchTarget {

    // The target without its profile, and the profile's serial. The serial
    // is the last '@' part, when that is a number: a shortcut id can hold
    // '@' itself - chat shortcuts do ("...@s.whatsapp.net") - and splitting
    // at the first one cut the id short and read no profile at all.
    fun split(target: String): Pair<String, Long> {
        val (body, serial) = parse(target)
        return body to (serial ?: 0L)
    }

    // As split, with no serial when the target carries none - older
    // per-icon swipes were stored without one.
    fun parse(target: String): Pair<String, Long?> {
        val at = target.lastIndexOf('@')
        val serial = if (at >= 0) target.substring(at + 1).toLongOrNull() else null
        return if (serial != null) target.substring(0, at) to serial else target to null
    }
}
