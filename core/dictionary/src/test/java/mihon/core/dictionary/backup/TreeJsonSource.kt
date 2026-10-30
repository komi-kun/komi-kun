// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Test-only [PullJsonReader] backed by a pre-flattened [JsonElement] tree.
 *
 * `android.util.JsonReader` is not on the plain-JVM unit-test classpath, so this drives the identical
 * token surface [DexieBackupConverter] consumes. The production reader ([AndroidPullJsonReader]) shares
 * the converter's token handling; it is exercised end-to-end by the on-device import, not here.
 * Semantics mirror `android.util.JsonReader` (begin/end consume their token, `nextString` accepts a
 * NUMBER, `skipValue` skips a whole subtree).
 */
internal class TreeJsonSource(root: JsonElement) : PullJsonReader {
    private sealed interface Token {
        data object BeginObject : Token
        data object EndObject : Token
        data object BeginArray : Token
        data object EndArray : Token
        data class Name(val value: String) : Token
        data class Literal(val event: JsonEvent, val value: String) : Token
    }

    private val tokens = ArrayList<Token>()
    private var pos = 0

    init {
        flatten(root)
    }

    private fun flatten(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                tokens += Token.BeginObject
                for ((k, v) in element) {
                    tokens += Token.Name(k)
                    flatten(v)
                }
                tokens += Token.EndObject
            }
            is JsonArray -> {
                tokens += Token.BeginArray
                element.forEach(::flatten)
                tokens += Token.EndArray
            }
            is JsonNull -> tokens += Token.Literal(JsonEvent.NULL, "")
            is JsonPrimitive -> when {
                element.isString -> tokens += Token.Literal(JsonEvent.STRING, element.content)
                element.content.toBooleanStrictOrNull() != null ->
                    tokens += Token.Literal(JsonEvent.BOOLEAN, element.content)
                else -> tokens += Token.Literal(JsonEvent.NUMBER, element.content)
            }
        }
    }

    private fun current(): Token? = tokens.getOrNull(pos)

    override fun peek(): JsonEvent = when (val token = current()) {
        null -> JsonEvent.END_DOCUMENT
        Token.BeginObject -> JsonEvent.BEGIN_OBJECT
        Token.EndObject -> JsonEvent.END_OBJECT
        Token.BeginArray -> JsonEvent.BEGIN_ARRAY
        Token.EndArray -> JsonEvent.END_ARRAY
        is Token.Name -> JsonEvent.NAME
        is Token.Literal -> token.event
    }

    override fun beginArray() {
        check(current() == Token.BeginArray) { "expected BEGIN_ARRAY but was ${peek()}" }
        pos++
    }

    override fun endArray() {
        check(current() == Token.EndArray) { "expected END_ARRAY but was ${peek()}" }
        pos++
    }

    override fun beginObject() {
        check(current() == Token.BeginObject) { "expected BEGIN_OBJECT but was ${peek()}" }
        pos++
    }

    override fun endObject() {
        check(current() == Token.EndObject) { "expected END_OBJECT but was ${peek()}" }
        pos++
    }

    override fun hasNext(): Boolean = when (current()) {
        null, Token.EndObject, Token.EndArray -> false
        else -> true
    }

    override fun nextName(): String {
        val token = current()
        check(token is Token.Name) { "expected NAME but was ${peek()}" }
        pos++
        return token.value
    }

    override fun nextString(): String {
        val token = current()
        check(token is Token.Literal && (token.event == JsonEvent.STRING || token.event == JsonEvent.NUMBER)) {
            "expected STRING/NUMBER but was ${peek()}"
        }
        pos++
        return token.value
    }

    override fun nextBoolean(): Boolean {
        val token = current()
        check(token is Token.Literal && token.event == JsonEvent.BOOLEAN) { "expected BOOLEAN but was ${peek()}" }
        pos++
        return token.value.toBoolean()
    }

    override fun nextNull() {
        val token = current()
        check(token is Token.Literal && token.event == JsonEvent.NULL) { "expected NULL but was ${peek()}" }
        pos++
    }

    override fun skipValue() {
        when (current()) {
            Token.BeginObject -> {
                var depth = 0
                do {
                    when (current()) {
                        Token.BeginObject -> depth++
                        Token.EndObject -> depth--
                        else -> Unit
                    }
                    pos++
                } while (depth > 0)
            }
            Token.BeginArray -> {
                var depth = 0
                do {
                    when (current()) {
                        Token.BeginArray -> depth++
                        Token.EndArray -> depth--
                        else -> Unit
                    }
                    pos++
                } while (depth > 0)
            }
            else -> pos++
        }
    }

    override fun close() = Unit
}
// KUN <--
