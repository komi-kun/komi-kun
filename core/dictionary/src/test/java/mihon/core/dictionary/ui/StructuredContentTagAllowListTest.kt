// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.ui

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A dictionary's structured glossary names the tags the renderer creates (`tag: "script"` is valid
 * JSON in a Yomitan term entry), and the popup is a JS-enabled WebView with native bridges
 * attached and no CSP. The renderer therefore has to decide what it may create from an explicit
 * allow-list instead of from a "looks like a tag name" regex.
 *
 * **This is a pin, not behavioural proof.** No JS engine runs in this module's JVM tests, so the
 * guard is asserted against `renderer.js` itself: the allow-lists must exist, must not admit an
 * executing/restyling tag, and every `document.createElement` site that a dictionary could feed
 * must sit behind the allow-list check.
 */
class StructuredContentTagAllowListTest {
    private val renderer = File("src/main/assets/dictionary/renderer.js").readText()

    @Test
    fun `the yomitan structured-content vocabulary survives the allow-list`() {
        setLiteral("HTML_STRUCTURED_TAGS") shouldContainAll listOf(
            "div", "span", "ul", "ol", "li", "details", "summary", "table", "tr", "th", "td",
            "img", "a", "b", "strong", "i", "em", "u", "sup", "sub", "ruby", "rt", "rp", "br",
            "hr", "code", "pre", "blockquote",
        )
        // The glyph/pitch SVG subsets the renderer draws itself must keep working.
        setLiteral("svgTags") shouldContainAll listOf(
            "svg", "g", "path", "rect", "text", "defs", "use", "lineargradient", "stop",
        )
    }

    @Test
    fun `no allow-list admits a tag that could execute or restyle the popup`() {
        val allowed = setLiteral("HTML_STRUCTURED_TAGS") + setLiteral("svgTags")

        allowed shouldNotContainAnyOf listOf(
            "script", "iframe", "object", "embed", "foreignobject", "form", "input",
            "button", "textarea", "select", "template", "link", "meta", "base", "frame",
            "frameset", "applet", "noscript",
        )
    }

    /**
     * `style` is the one admitted tag whose *content* is executable-looking (CSS can import and
     * load), so it may never be created from the dictionary's text as-is: the renderer's style path
     * must rebuild the sheet through the property allow-list, and degrade to neutral content when it
     * cannot (a dictionary stylesheet that imports or references anything external).
     */
    @Test
    fun `a style tag is admitted only through the css allow-list`() {
        setLiteral("HTML_STRUCTURED_TAGS") shouldContainAll listOf("style")

        val dispatcher = functionBody("function renderStructuredObjectNode(")
        dispatcher shouldContain "createStyleNode("

        val creator = functionBody("function createStyleNode(")
        creator shouldContain "sanitizeStyleSheet("
        // The created element carries the rebuilt sheet, never the dictionary's raw text.
        creator shouldContain "element.textContent = sheet"
        creator shouldContain "NEUTRAL_TAG_NAME"
    }

    /**
     * The property allow-list exists three times (Kotlin payload boundary, `renderer.js` and
     * `kanji-renderer.js`, the latter two loaded independently of the payload), so a property added
     * to one side only would silently be filtered on one surface and survive on another.
     */
    @Test
    fun `the css property allow-list matches both renderers`() {
        SAFE_STYLE_PROPERTIES shouldBe setLiteral("SAFE_STYLE_PROPERTIES")

        val kanjiRenderer = File("src/main/assets/dictionary/kanji-renderer.js").readText()
        setLiteralIn(kanjiRenderer, "SAFE_STYLE_PROPERTIES") shouldBe SAFE_STYLE_PROPERTIES
        // The families (`font-*`) are part of the same contract.
        kanjiRenderer shouldContain "SAFE_STYLE_PROPERTY_PREFIXES"
        renderer shouldContain "SAFE_STYLE_PROPERTY_PREFIXES"
        kanjiRenderer shouldContain "'font-'"
        renderer shouldContain "'font-'"
    }

    @Test
    fun `every element a glossary can create is gated by the allow-list`() {
        // The single factory that turns a structured-content tag into a DOM node.
        val factory = functionBody("function createElementForTag(")
        factory shouldContain "isValidTagName("
        factory shouldContain "NEUTRAL_TAG_NAME"

        // The node dispatcher must keep turning an unknown tag into neutral content instead of
        // creating it (so a `script` node never exists, and its children still render).
        functionBody("function renderStructuredObjectNode(").let { dispatcher ->
            dispatcher shouldContain "isValidTagName("
            dispatcher shouldContain "NEUTRAL_TAG_NAME"
        }

        // The decision comes from the two allow-lists, not from "looks like a tag name".
        val gate = functionBody("function isValidTagName(")
        gate shouldContain "HTML_STRUCTURED_TAGS.has(lower)"
        gate shouldContain "svgTags.has(lower)"
        renderer shouldNotContain "/^[a-z][a-z0-9:-]*$/i"
    }

    @Test
    fun `the kotlin payload allow-list matches the renderer's`() {
        // Two hand-maintained copies of the same vocabulary; equality keeps them from drifting.
        setLiteral("HTML_STRUCTURED_TAGS") shouldBe STRUCTURED_CONTENT_TAG_ALLOW_LIST
        setLiteral("svgTags") shouldBe STRUCTURED_CONTENT_SVG_TAG_ALLOW_LIST
    }

    /** Body of the `new Set([...])` declaration, names lower-cased. */
    private fun setLiteral(declaration: String): Set<String> = setLiteralIn(renderer, declaration)

    /** Same, for a specific asset (`kanji-renderer.js` declares its copies with `var`). */
    private fun setLiteralIn(source: String, declaration: String): Set<String> {
        val start = source.indexOf("$declaration = new Set([")
        check(start >= 0) { "$declaration is missing from the renderer" }
        val open = source.indexOf('[', start)
        val close = source.indexOf(']', open)
        check(open >= 0 && close > open) { "$declaration has no array literal" }
        val names = Regex("'([^']+)'").findAll(source.substring(open + 1, close))
            .map { it.groupValues[1].lowercase() }
            .toSet()
        check(names.isNotEmpty()) { "$declaration is empty" }
        return names
    }

    /** Source of `declaration` up to its balanced closing brace. */
    private fun functionBody(declaration: String): String {
        val start = renderer.indexOf(declaration)
        check(start >= 0) { "$declaration is missing from renderer.js" }
        var depth = 0
        for (index in start..renderer.lastIndex) {
            when (renderer[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return renderer.substring(start, index + 1)
                }
            }
        }
        error("unbalanced braces after $declaration")
    }
}
// KUN <--
