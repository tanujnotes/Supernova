package com.supernova.launcher.data.imports

import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

// The small formats a launcher's backup archive is made of, each read into
// plain values: a table row, a preferences file, a protocol buffer.

// One row of a layout table, by column name whatever its case.
internal class Row(values: Map<String, Any?>) {
    private val values = values.mapKeys { it.key.lowercase() }

    fun has(column: String): Boolean = column.lowercase() in values

    fun text(column: String): String? = values[column.lowercase()]?.let { it as? String ?: (it as? Number)?.toString() }

    fun long(column: String): Long? = when (val value = values[column.lowercase()]) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong()
        else -> null
    }

    fun int(column: String): Int? = long(column)?.toInt()

    fun float(column: String): Float? = when (val value = values[column.lowercase()]) {
        is Number -> value.toFloat()
        is String -> value.toFloatOrNull()
        else -> null
    }

    fun blob(column: String): ByteArray? = values[column.lowercase()] as? ByteArray
}

// A shared preferences file as name -> value text; a set's entries are
// joined by newlines. Empty for anything that is not such a file.
internal object PrefsXml {

    // Such a file never declares a document type; one that does is not a
    // preferences file, and its entities are not worth expanding.
    private val DECLARATIONS = listOf("<!DOCTYPE", "<!ENTITY")

    fun parse(xml: String): Map<String, String> {
        val root = document(xml)?.documentElement?.takeIf { it.tagName == "map" }
        val out = LinkedHashMap<String, String>()
        root?.let(::elements).orEmpty().filter { it.getAttribute("name").isNotEmpty() }.forEach { entry ->
            out[entry.getAttribute("name")] = when (entry.tagName) {
                "string" -> entry.textContent.orEmpty()
                "set" -> elements(entry).joinToString("\n") { it.textContent.orEmpty() }
                else -> entry.getAttribute("value")
            }
        }
        return out
    }

    private fun document(xml: String): Document? {
        val declares = DECLARATIONS.any { xml.contains(it, ignoreCase = true) }
        return if (declares) {
            null
        } else {
            runCatching {
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
            }.getOrNull()
        }
    }

    private fun elements(parent: Element): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }
}

// The fields of a protocol buffer message, enough to pick a number or a
// nested message out of one whose layout is known.
internal class ProtoFields private constructor(private val fields: List<Field>) {

    private class Field(val number: Int, val varint: Long? = null, val bytes: ByteArray? = null)

    fun int(number: Int): Int? = fields.lastOrNull { it.number == number }?.varint?.toInt()

    fun text(number: Int): String? = fields.lastOrNull { it.number == number }?.bytes?.toString(Charsets.UTF_8)

    fun message(number: Int): ProtoFields? = fields.lastOrNull { it.number == number }?.bytes?.let(::parse)

    fun messages(number: Int): List<ProtoFields> =
        fields.filter { it.number == number }.mapNotNull { field -> field.bytes?.let(::parse) }

    private class Reader(private val bytes: ByteArray) {
        private var at = 0
        var broken = false
            private set

        val left: Int get() = bytes.size - at

        // The next field, null once the bytes stop being a message.
        fun field(): Field? {
            val tag = varint()
            val number = tag?.let { (it ushr WIRE_BITS).toInt() } ?: 0
            val field = when (tag?.let { (it and WIRE_MASK).toInt() }) {
                VARINT -> varint()?.let { Field(number, varint = it) }
                BYTES -> chunk()?.let { Field(number, bytes = it) }
                FIXED64 -> Field(number).takeIf { skip(Long.SIZE_BYTES) }
                FIXED32 -> Field(number).takeIf { skip(Int.SIZE_BYTES) }
                else -> null
            }?.takeIf { number > 0 }
            if (field == null) broken = true
            return field
        }

        private fun varint(): Long? {
            var value = 0L
            var shift = 0
            var done = false
            while (!done && at < bytes.size && shift <= MAX_SHIFT) {
                val b = bytes[at++].toInt()
                value = value or ((b and LOW_BITS).toLong() shl shift)
                done = b and MORE == 0
                shift += GROUP
            }
            return value.takeIf { done }
        }

        private fun chunk(): ByteArray? {
            val length = varint()?.takeIf { it >= 0 && it <= left }?.toInt()
            return length?.let { bytes.copyOfRange(at, at + it).also { _ -> at += it } }
        }

        private fun skip(count: Int): Boolean {
            at += count
            return at <= bytes.size
        }
    }

    companion object {
        private const val VARINT = 0
        private const val FIXED64 = 1
        private const val BYTES = 2
        private const val FIXED32 = 5
        private const val WIRE_BITS = 3
        private const val WIRE_MASK = 7L
        private const val MAX_SHIFT = 63
        // A varint's groups of seven bits, the eighth saying more follow.
        private const val GROUP = 7
        private const val LOW_BITS = 0x7f
        private const val MORE = 0x80

        // Null for bytes that are not a message.
        fun parse(bytes: ByteArray): ProtoFields? {
            val reader = Reader(bytes)
            val fields = generateSequence { if (reader.left > 0) reader.field() else null }.toList()
            return ProtoFields(fields).takeIf { !reader.broken }
        }
    }
}
