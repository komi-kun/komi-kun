// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.ui

/**
 * Whitelist sanitizer for the SVG markup a kanji dictionary ships as a definition or stat value.
 *
 * The kanji renderer shows such a value as an inline `<svg>`, and the popup WebView is JS-enabled with
 * native bridges attached, so markup from a third-party dictionary must not be able to run anything:
 * `innerHTML` does not execute `<script>`, but it does fire inline handlers (`<svg onload=…>`) and
 * loads `<foreignObject>` content. Only the elements and attributes listed here survive; the markup is
 * rebuilt token by token, so a denied element takes its subtree with it and a denied attribute is
 * never copied. `assets/dictionary/kanji-renderer.js` repeats this filtering on its side because the
 * renderer is loaded independently of this payload.
 *
 * Normal glyphs (`<svg viewBox=…><g transform=…><path d=…/></g></svg>`) pass through unchanged,
 * apart from the root element: the renderer re-parses the output as XML (`image/svg+xml`), which
 * — unlike the HTML parser — requires the SVG namespace to be declared and rejects an undeclared
 * `xlink:` prefix. So the root always ends up carrying `xmlns="http://www.w3.org/2000/svg"`,
 * plus `xmlns:xlink` when the markup uses that prefix, and both declarations themselves are
 * allowed.
 *
 * An inline `style` attribute is allowed but **filtered**, property by property
 * (`sanitizeInlineStyle`): only colour/fill/stroke/opacity and `font-*` survive, and a value that
 * loads or runs something rejects its declaration. `<style>` blocks are not part of the SVG
 * subset — they are handled where structured glossary HTML is sanitised.
 */
private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"
private const val XLINK_NAMESPACE = "http://www.w3.org/1999/xlink"

private val ALLOWED_ELEMENTS = setOf(
    "svg",
    "g",
    "defs",
    "symbol",
    "use",
    "path",
    "rect",
    "circle",
    "ellipse",
    "line",
    "polyline",
    "polygon",
    "text",
    "tspan",
    "title",
    "desc",
    "clippath",
    "mask",
    "pattern",
    "lineargradient",
    "radialgradient",
    "stop",
)

private val ALLOWED_ATTRIBUTES = setOf(
    "xmlns",
    // Namespace declarations carry no script surface, and XML parsing of the output needs them.
    "xmlns:xlink",
    "xml:space",
    "version",
    "viewbox",
    "preserveaspectratio",
    "width",
    "height",
    "x",
    "y",
    "x1",
    "x2",
    "y1",
    "y2",
    "dx",
    "dy",
    "cx",
    "cy",
    "r",
    "rx",
    "ry",
    "d",
    "points",
    "transform",
    "rotate",
    "fill",
    "fill-opacity",
    "fill-rule",
    "stroke",
    "stroke-width",
    "stroke-linecap",
    "stroke-linejoin",
    "stroke-dasharray",
    "stroke-dashoffset",
    "stroke-opacity",
    "stroke-miterlimit",
    "opacity",
    "clip-path",
    "clip-rule",
    "mask",
    "marker-start",
    "marker-mid",
    "marker-end",
    "paint-order",
    "vector-effect",
    "display",
    "visibility",
    "stop-color",
    "stop-opacity",
    "offset",
    "gradientunits",
    "gradienttransform",
    "spreadmethod",
    "text-anchor",
    "dominant-baseline",
    "alignment-baseline",
    "baseline-shift",
    "font-family",
    "font-size",
    "font-weight",
    "font-style",
    "font-variant",
    "letter-spacing",
    "word-spacing",
    "id",
    "class",
    "href",
    "xlink:href",
    // KUN -->
    // Inline CSS, filtered property by property (`sanitizeInlineStyle`): static dictionaries colour
    // glyphs this way, and the allow-list keeps it from becoming a way to load or run anything.
    "style",
    // KUN <--
)

private val TAG = Regex("""<\s*(/?)\s*([A-Za-z][A-Za-z0-9:_.-]*)((?:[^>"']|"[^"]*"|'[^']*')*?)(/?)>""")
private val ATTRIBUTE = Regex("""([A-Za-z_:][A-Za-z0-9:_.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
private val LOCAL_URL = Regex("""url\(\s*#[-A-Za-z0-9_.:]+\s*\)""")
private val IDENTIFIER = Regex("""[-A-Za-z0-9_.:]+""")

/** True when the renderer would treat [text] as inline SVG (the same test `kanji-renderer.js` makes). */
internal fun isKanjiSvgMarkup(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.length > "<svg></svg>".length && trimmed.startsWith("<svg") && trimmed.endsWith("</svg>")
}

/** [text] unchanged unless it is inline SVG, in which case the whitelisted rebuild of it. */
internal fun sanitizeKanjiRichText(text: String): String =
    if (isKanjiSvgMarkup(text)) sanitizeKanjiSvgMarkup(text.trim()) else text

internal fun sanitizeKanjiSvgMarkup(markup: String): String {
    val out = StringBuilder(markup.length)
    val open = mutableListOf<String>()
    var skipElement: String? = null
    var skipDepth = 0
    var sawRoot = false
    var index = 0
    // End of the root `<svg>` start tag: the namespace declarations XML parsing needs are only known
    // once the whole document has been walked, so they are inserted there afterwards.
    var rootTagEnd = -1
    var rootDeclaresXmlns = false
    var declaresXlinkNamespace = false
    var usesXlinkPrefix = false

    for (match in TAG.findAll(markup)) {
        val textEnd = match.range.first
        if (skipElement == null) appendEscapedText(out, markup, index, textEnd)
        index = match.range.last + 1

        val closing = match.groupValues[1] == "/"
        val name = match.groupValues[2]
        val key = name.lowercase()
        val selfClosing = match.groupValues[4] == "/"

        if (skipElement != null) {
            if (!closing && !selfClosing && key == skipElement) skipDepth++
            if (closing && key == skipElement) {
                skipDepth--
                if (skipDepth == 0) skipElement = null
            }
            continue
        }

        if (closing) {
            // Close the nearest open element of this name, dropping anything left open inside it.
            val position = open.indexOfLast { it.lowercase() == key }
            if (position < 0) continue
            while (open.size > position) out.append("</").append(open.removeAt(open.lastIndex)).append('>')
            continue
        }

        if (key !in ALLOWED_ELEMENTS) {
            if (!selfClosing) {
                skipElement = key
                skipDepth = 1
            }
            continue
        }
        if (key == "svg") sawRoot = true
        val isRootSvg = key == "svg" && rootTagEnd < 0 && open.none { it.lowercase() == "svg" }

        // Original spelling is kept (HTML parsing normalises SVG names), so a legitimate glyph keeps
        // its exact markup.
        out.append('<').append(name)
        ATTRIBUTE.findAll(match.groupValues[3]).forEach { attribute ->
            val attributeName = attribute.groupValues[1]
            val attributeKey = attributeName.lowercase()
            val value = attribute.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty()
            if (attributeKey !in ALLOWED_ATTRIBUTES) return@forEach
            // KUN --> `style` is filtered declaration by declaration (`sanitizeInlineStyle`): the
            // whole-value rules below would drop the attribute as soon as one declaration is refused,
            // so a single `position:absolute` would take the glyph's colour with it.
            val keptStyle = if (attributeKey == "style") sanitizeInlineStyle(value) else null
            if (attributeKey == "style") {
                if (keptStyle == null) return@forEach
            } else if (!isSafeAttributeValue(attributeKey, value)) {
                return@forEach
            }
            // KUN <--
            if (attributeKey == "xmlns" && isRootSvg) rootDeclaresXmlns = true
            if (attributeKey == "xmlns:xlink") declaresXlinkNamespace = true
            if (attributeKey.startsWith("xlink:")) usesXlinkPrefix = true
            // The root is rendered as SVG whatever namespace the dictionary claims for it.
            val keptValue = when {
                // KUN --> Only the allow-listed declarations survive (computed above).
                attributeKey == "style" -> keptStyle ?: return@forEach
                // KUN <--
                isRootSvg && attributeKey == "xmlns" -> SVG_NAMESPACE
                else -> value
            }
            out.append(' ').append(attributeName).append("=\"").append(escapeAttribute(keptValue)).append('"')
        }
        if (selfClosing) {
            out.append("/>")
        } else {
            out.append('>')
            open.add(name)
        }
        if (isRootSvg) rootTagEnd = out.length - if (selfClosing) 2 else 1
    }

    if (skipElement == null) appendEscapedText(out, markup, index, markup.length)
    while (open.isNotEmpty()) out.append("</").append(open.removeAt(open.lastIndex)).append('>')

    if (rootTagEnd >= 0) {
        val declarations = buildString {
            if (!rootDeclaresXmlns) append(" xmlns=\"").append(SVG_NAMESPACE).append('"')
            if (usesXlinkPrefix && !declaresXlinkNamespace) {
                append(" xmlns:xlink=\"").append(XLINK_NAMESPACE).append('"')
            }
        }
        if (declarations.isNotEmpty()) out.insert(rootTagEnd, declarations)
    }

    val sanitized = out.toString()
    // No `<svg>` root (or nothing left): hand the renderer inert text instead of markup.
    return if (sawRoot && sanitized.isNotEmpty()) sanitized else escapeText(markup)
}

private fun isSafeAttributeValue(name: String, value: String): Boolean {
    val compact = value.filterNot { it.isWhitespace() }.lowercase()
    if (compact.contains("javascript:") || compact.contains("vbscript:")) return false
    if (compact.contains("data:text/html")) return false
    if (value.contains("&#")) return false
    when (name) {
        // Only document-local references, e.g. `<use href="#glyph">`.
        "href", "xlink:href" -> return value.trim().startsWith("#")
        "id" -> return IDENTIFIER.matches(value.trim())
    }
    // `url(...)` may only reference a paint server defined in this same document.
    if (value.contains("url(", ignoreCase = true)) return LOCAL_URL.matches(value.trim())
    return true
}

private fun escapeAttribute(value: String): String = buildString(value.length) {
    for (char in value) {
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(char)
        }
    }
}

private fun escapeText(text: String): String = buildString(text.length) {
    for (char in text) {
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(char)
        }
    }
}

private fun appendEscapedText(out: StringBuilder, source: String, start: Int, end: Int) {
    if (start >= end) return
    out.append(escapeText(source.substring(start, end)))
}
