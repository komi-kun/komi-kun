// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.backup

import android.util.JsonReader
import android.util.JsonToken
import java.io.Closeable

/**
 * The minimal pull-parser surface [DexieBackupConverter] consumes.
 *
 * Production wraps [android.util.JsonReader] via [AndroidPullJsonReader]: a streaming pull parser
 * with O(row) memory (never the whole 2.3 GB file) and no new dependency. Unit tests back the same
 * interface with a JVM source built from a small synthetic fixture, because `android.util.JsonReader`
 * is not on the plain-JVM unit-test classpath; the converter's token handling is byte-for-byte the
 * same in both. The real Android reader is exercised end-to-end by the on-device import.
 */
interface PullJsonReader : Closeable {
    fun peek(): JsonEvent
    fun beginArray()
    fun endArray()
    fun beginObject()
    fun endObject()
    fun hasNext(): Boolean
    fun nextName(): String
    fun nextString(): String
    fun nextBoolean(): Boolean
    fun nextNull()
    fun skipValue()
}

/** Mirror of [JsonToken] so tests that cannot load the Android class can still drive the converter. */
enum class JsonEvent {
    BEGIN_ARRAY,
    END_ARRAY,
    BEGIN_OBJECT,
    END_OBJECT,
    NAME,
    STRING,
    NUMBER,
    BOOLEAN,
    NULL,
    END_DOCUMENT,
}

/** Production [PullJsonReader] over the platform pull parser — the only Android-specific bit. */
class AndroidPullJsonReader(private val reader: JsonReader) : PullJsonReader {
    override fun peek(): JsonEvent = reader.peek().toEvent()

    override fun beginArray() = reader.beginArray()
    override fun endArray() = reader.endArray()
    override fun beginObject() = reader.beginObject()
    override fun endObject() = reader.endObject()
    override fun hasNext(): Boolean = reader.hasNext()
    override fun nextName(): String = reader.nextName()
    override fun nextString(): String = reader.nextString()
    override fun nextBoolean(): Boolean = reader.nextBoolean()
    override fun nextNull() = reader.nextNull()
    override fun skipValue() = reader.skipValue()
    override fun close() = reader.close()
}

private fun JsonToken.toEvent(): JsonEvent = when (this) {
    JsonToken.BEGIN_ARRAY -> JsonEvent.BEGIN_ARRAY
    JsonToken.END_ARRAY -> JsonEvent.END_ARRAY
    JsonToken.BEGIN_OBJECT -> JsonEvent.BEGIN_OBJECT
    JsonToken.END_OBJECT -> JsonEvent.END_OBJECT
    JsonToken.NAME -> JsonEvent.NAME
    JsonToken.STRING -> JsonEvent.STRING
    JsonToken.NUMBER -> JsonEvent.NUMBER
    JsonToken.BOOLEAN -> JsonEvent.BOOLEAN
    JsonToken.NULL -> JsonEvent.NULL
    JsonToken.END_DOCUMENT -> JsonEvent.END_DOCUMENT
}
// KUN <--
