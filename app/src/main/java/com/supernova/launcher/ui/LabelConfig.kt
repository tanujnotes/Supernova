package com.supernova.launcher.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Which surface a label is being drawn on. DESKTOP is the base the global
// label prefs describe; the other contexts can override it per field.
enum class LabelContext { DESKTOP, DOCK, DRAWER, FOLDER }

// One context's overrides. Every null field inherits the desktop value, so an
// all-null override renders exactly like before this pref existed.
@Serializable
data class LabelOverride(
    // Label size in sp (9..18); null inherits labelSize.
    val size: Int? = null,
    // LabelFont name; null inherits labelFont.
    val font: String? = null,
    val shadow: Boolean? = null,
    val singleLine: Boolean? = null
) {
    fun isEmpty(): Boolean = size == null && font == null && shadow == null && singleLine == null
}

// The whole per-context label document behind the labelConfig pref. ""
// decodes to all-inherit.
@Serializable
data class LabelConfig(
    val dock: LabelOverride = LabelOverride(),
    val drawer: LabelOverride = LabelOverride(),
    val folder: LabelOverride = LabelOverride()
) {

    fun of(context: LabelContext): LabelOverride = when (context) {
        LabelContext.DESKTOP -> EMPTY_OVERRIDE
        LabelContext.DOCK -> dock
        LabelContext.DRAWER -> drawer
        LabelContext.FOLDER -> folder
    }

    fun with(context: LabelContext, override: LabelOverride): LabelConfig = when (context) {
        LabelContext.DESKTOP -> this
        LabelContext.DOCK -> copy(dock = override)
        LabelContext.DRAWER -> copy(drawer = override)
        LabelContext.FOLDER -> copy(folder = override)
    }

    fun encode(): String =
        if (dock.isEmpty() && drawer.isEmpty() && folder.isEmpty()) {
            ""
        } else {
            json.encodeToString(serializer(), this)
        }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        val EMPTY = LabelConfig()
        private val EMPTY_OVERRIDE = LabelOverride()

        // Labels decode on every bind, so memoize on the raw string. Binds
        // happen on the main thread only; a stale race would just re-decode.
        private var lastRaw: String? = null
        private var lastDecoded: LabelConfig = EMPTY

        fun decode(raw: String): LabelConfig {
            if (raw.isBlank()) return EMPTY
            if (raw == lastRaw) return lastDecoded
            val decoded = runCatching { json.decodeFromString(serializer(), raw) }.getOrDefault(EMPTY)
            lastRaw = raw
            lastDecoded = decoded
            return decoded
        }
    }
}
