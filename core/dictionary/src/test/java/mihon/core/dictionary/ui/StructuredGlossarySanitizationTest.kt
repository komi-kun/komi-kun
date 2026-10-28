// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.ui

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.core.dictionary.model.GlossaryEntry
import org.junit.jupiter.api.Test

/**
 * A Yomitan term's glossary is structured content whose `tag` values come straight from the
 * dictionary file, and the popup is a JS-enabled WebView with native bridges and no CSP. The
 * renderer refuses off-list tags, but the payload is assembled here, so the same allow-list is
 * applied to the JSON on the way out: an executing (`script`, `style`, `iframe`, `object`, `embed`,
 * `foreignObject`) or unknown tag becomes `span` with its children kept.
 */
class StructuredGlossarySanitizationTest {

    private fun glossaryJson(raw: String): String = buildGlossaryPayload(
        GlossaryEntry(dictName = "dict", glossary = raw, definitionTags = "", termTags = ""),
    ).jsonObject["glossary"]!!.jsonPrimitive.content

    @Test
    fun `an executing tag is neutralised and keeps its children`() {
        val raw = """[{"tag":"script","content":"alert('x')"},{"tag":"div","content":"ok"}]"""

        val sanitized = glossaryJson(raw)

        sanitized shouldNotContain """"tag":"script""""
        sanitized shouldContain """"tag":"span""""
        // The text stays as inert content of the neutral element.
        sanitized shouldContain "alert('x')"
        Json.parseToJsonElement(sanitized).jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content shouldBe
            "alert('x')"
        Json.parseToJsonElement(sanitized).jsonArray[1].jsonObject["tag"]!!.jsonPrimitive.content shouldBe "div"
    }

    @Test
    fun `style, iframe and an unknown tag are all neutralised`() {
        val raw = """{"type":"structured-content","content":[""" +
            """{"tag":"style","content":"rect{background:red}"},""" +
            """{"tag":"iframe","content":"https://evil.invalid"},""" +
            """{"tag":"totally-unknown","content":"text"}]}"""

        val sanitized = glossaryJson(raw)

        for (forbidden in listOf("style", "iframe", "totally-unknown")) {
            sanitized shouldNotContain """"tag":"$forbidden""""
        }
        sanitized shouldContain "rect{background:red}"
        sanitized shouldContain "https://evil.invalid"
        sanitized shouldContain """"content":"text""""
    }

    @Test
    fun `the structured-content vocabulary survives untouched`() {
        val raw = """[{"tag":"div","content":[{"tag":"ruby","content":["漢",{"tag":"rt","content":"かん"}]}]},""" +
            """{"tag":"a","href":"https://jisho.org/x","content":"link"},""" +
            """{"tag":"img","path":"x.png"}]"""

        glossaryJson(raw) shouldBe Json.parseToJsonElement(raw).toString()
    }

    @Test
    fun `a plain-text glossary is passed through byte for byte`() {
        val plain = "  a meaning with <b>markup</b> and {'braces': 1} that is not a document"

        glossaryJson(plain) shouldBe plain
    }

    @Test
    fun `json-shaped text that does not parse is passed through`() {
        val broken = """[{"tag":"script","content":"""

        glossaryJson(broken) shouldBe broken
    }
}
// KUN <--
