// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.backup

/**
 * Tiny, allocation-light JSON encoder for the bank/index files we regenerate.
 *
 * Values are the plain Kotlin shapes produced by [DexieBackupConverter.readValue]: String, Long,
 * Double, Boolean, null, List and Map. Keeping this in-module avoids pulling a serializer into the
 * converter's hot path (one entry per term, 2.6 M rows in the real backup).
 */
internal object MiniJson {

    fun encode(value: Any?): String = StringBuilder(64).also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(value)
            is Long, is Int, is Short, is Byte -> sb.append(value.toString())
            is Double, is Float -> {
                val d = (value as Number).toDouble()
                // Guard against NaN/Infinity, which are not valid JSON.
                sb.append(if (d.isFinite()) d.toString() else "null")
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k?.toString() ?: "null")
                    sb.append(':')
                    write(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') {
                    sb.append("\\u")
                    sb.append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }
}

internal fun Map<String, Any?>.str(key: String, default: String = ""): String =
    (this[key] as? String) ?: default

internal fun Map<String, Any?>.num(key: String, default: Long = 0L): Long = when (val v = this[key]) {
    is Long -> v
    is Int -> v.toLong()
    is Double -> v.toLong()
    is String -> v.toLongOrNull() ?: default
    else -> default
}

internal fun Map<String, Any?>.value(key: String): Any? = this[key]
// KUN <--
