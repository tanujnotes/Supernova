package com.supernova.launcher.ui

import com.supernova.launcher.runLogged
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
    val singleLine: Boolean? = null,
    // Customize labels switched off: the values stay for the next time it
    // goes on, but the labels inherit the desktop's meanwhile (#254).
    val off: Boolean = false
) {
    // No values kept, whether or not it is switched on.
    fun isEmpty(): Boolean = size == null && font == null && shadow == null && singleLine == null

    // Whether it changes how labels draw.
    fun active(): Boolean = !off && !isEmpty()
}

// The whole per-context label document behind the labelConfig pref. ""
// decodes to all-inherit.
@Serializable
data class LabelConfig(
    val dock: LabelOverride = LabelOverride(),
    val drawer: LabelOverride = LabelOverride(),
    val folder: LabelOverride = LabelOverride()
) {

    // The override labels draw with: an empty one while switched off.
    fun of(context: LabelContext): LabelOverride = stored(context).takeIf { it.active() } ?: EMPTY_OVERRIDE

    // The override as kept, values and off flag both: for the settings rows.
    fun stored(context: LabelContext): LabelOverride = when (context) {
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
            val decoded = runLogged("read label settings") {
                json.decodeFromString(serializer(), raw)
            }.getOrDefault(EMPTY)
            lastRaw = raw
            lastDecoded = decoded
            return decoded
        }
    }
}
