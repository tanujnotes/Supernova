package com.supernova.launcher.data.imports

// An intent as launchers write it into their layout tables
// ("#Intent;action=...;component=pkg/.Main;S.key=value;end"), read without
// android.content.Intent so the converters stay plain Kotlin.
internal class IntentText(
    // The whole text, with the data it may start with ("tel:123#Intent;...").
    private val raw: String,
    val action: String?,
    val categories: List<String>,
    val packageName: String?,
    // The target activity's package and full class name.
    val component: Pair<String, String>?,
    // Extras by name, their type prefix dropped.
    val extras: Map<String, String>
) {
    // The intent as Intent.parseUri reads it back under URI_INTENT_SCHEME:
    // "intent:" first, the data's scheme moved in among the fields.
    val uri: String
        get() {
            if (raw.startsWith(SCHEME)) return raw
            val data = raw.substringBefore(MARKER)
            val fields = raw.substring(data.length + MARKER.length)
            val colon = data.indexOf(':')
            return if (colon <= 0) {
                "$SCHEME$data$MARKER$fields"
            } else {
                "$SCHEME${data.substring(colon + 1)}${MARKER}scheme=${data.substring(0, colon)};$fields"
            }
        }

    val targetPackage: String? get() = component?.first ?: packageName

    companion object {
        private const val MARKER = "#Intent;"
        private const val SCHEME = "intent:"
        private const val END = "end"
        // "%" and its two digits.
        private const val ESCAPE = 3
        private const val HEX = 16
        private val EXTRA = Regex("^[SBbcdfilsZ]\\.(.+)$")

        fun parse(raw: String?): IntentText? {
            val text = raw?.trim().orEmpty()
            val start = text.indexOf(MARKER)
            if (start < 0) return null
            var action: String? = null
            var packageName: String? = null
            var component: Pair<String, String>? = null
            val categories = ArrayList<String>()
            val extras = LinkedHashMap<String, String>()
            val fields = text.substring(start + MARKER.length).split(';').takeWhile { it != END }
            fields.filter { it.indexOf('=') > 0 }.forEach { field ->
                val key = field.substringBefore('=')
                val value = unescape(field.substringAfter('='))
                when (key) {
                    "action" -> action = value
                    "category" -> categories += value
                    "package" -> packageName = value
                    "component" -> component = component(value)
                    else -> EXTRA.find(key)?.let { extras[unescape(it.groupValues[1])] = value }
                }
            }
            return IntentText(text, action, categories, packageName, component, extras)
        }

        // "pkg/.Main" and "pkg/pkg.Main" alike.
        fun component(flat: String?): Pair<String, String>? {
            val pkg = flat?.substringBefore('/', "").orEmpty()
            val cls = flat?.substringAfter('/', "").orEmpty()
            return if (pkg.isEmpty() || cls.isEmpty()) null else pkg to if (cls.startsWith(".")) pkg + cls else cls
        }

        // Percent escapes, as Uri.encode writes them.
        private fun unescape(value: String): String {
            if ('%' !in value) return value
            val out = java.io.ByteArrayOutputStream(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                val escape = if (c == '%') value.substring(i + 1, minOf(i + ESCAPE, value.length)) else ""
                val byte = escape.takeIf { it.length == ESCAPE - 1 }?.toIntOrNull(HEX)
                if (byte != null) {
                    out.write(byte)
                    i += ESCAPE
                } else {
                    out.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
