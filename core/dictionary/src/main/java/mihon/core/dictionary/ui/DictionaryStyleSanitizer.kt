// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The one allow-list for dictionary-supplied CSS.
//
// A dictionary is third-party markup shown in a JS-enabled WebView that carries native bridges, so
// every `style`/`<style>` it ships is filtered here (and, mirrored, in the two renderer assets).
// Static, well-behaved dictionaries do use `style` for glyph colouring and font tweaks, so it is
// allowed — but only these properties, and never a value that can load or run anything:
//
//  * only [SAFE_STYLE_PROPERTIES] and `font-*` survive; layout, positioning, animation, `behavior`
//    and every other property is dropped;
//  * `url(`, `@import`, `expression(`, `javascript:`, `vbscript:` and any absolute/external
//    reference (a scheme, a protocol-relative `//`, an entity-encoded value) reject the declaration;
//  * a stylesheet additionally may not contain `@` at all (no `@import`/`@media`/`@font-face`) and
//    only plain `selector { declarations }` rules are rebuilt.
//
// The output is rebuilt from parsed declarations, never copied, so a rejected property or value is
// simply absent. Mirrored by `SAFE_STYLE_PROPERTIES` in `kanji-renderer.js` and `renderer.js`;
// `StructuredContentTagAllowListTest` fails when the three drift apart.
// KUN <--
package mihon.core.dictionary.ui

/** CSS properties a dictionary may set inline or in a `<style>` block. */
internal val SAFE_STYLE_PROPERTIES = setOf("color", "fill", "stroke", "stroke-width", "opacity")

/** Whole property families a dictionary may set (the type properties Yomitan dictionaries use). */
internal val SAFE_STYLE_PROPERTY_PREFIXES = listOf("font-")

/**
 * Substrings that make a value unsafe: they either load something (`url(`, an absolute URL) or run
 * script (`expression(`, `javascript:`). `@` is handled by [sanitizeStyleSheet] per sheet.
 */
private val FORBIDDEN_STYLE_TOKENS = listOf(
    "url(",
    "@import",
    "expression(",
    "javascript:",
    "vbscript:",
    "behavior:",
    "-moz-binding",
    "http:",
    "https:",
    "ftp:",
    "file:",
    "//",
)

private val STYLE_RULE = Regex("""([^{}]+)\{([^{}]*)\}""")

/** True when [name] is one of [SAFE_STYLE_PROPERTIES] or a [SAFE_STYLE_PROPERTY_PREFIXES] family. */
internal fun isSafeStyleProperty(name: String): Boolean {
    val key = name.trim().lowercase()
    if (key.isEmpty()) return false
    return key in SAFE_STYLE_PROPERTIES || SAFE_STYLE_PROPERTY_PREFIXES.any { key.startsWith(it) && key.length > it.length }
}

/** True when [value] references nothing external and cannot execute. */
internal fun isSafeStyleValue(value: String): Boolean {
    if (value.isBlank()) return false
    // Entity smuggling would slip past the token checks below (`&#106;avascript:`), exactly like in
    // the SVG attribute filter.
    if (value.contains("&#")) return false
    val compact = value.filterNot { it.isWhitespace() }.lowercase()
    return FORBIDDEN_STYLE_TOKENS.none { compact.contains(it) }
}

/**
 * The allow-listed declarations of an inline `style` attribute (`"color: red; fill: #000"`), or null
 * when nothing safe is left. Never returns a partial declaration.
 */
internal fun sanitizeInlineStyle(css: String): String? {
    val declarations = css.split(';').mapNotNull(::sanitizeDeclaration)
    return declarations.takeIf { it.isNotEmpty() }?.joinToString("; ")
}

/**
 * The allow-listed rules of a `<style>` block, rebuilt rule by rule, or null when the sheet cannot be
 * expressed with plain rules and allow-listed declarations (an at-rule, stray text, a comment).
 */
internal fun sanitizeStyleSheet(css: String): String? {
    if (css.isBlank()) return null
    // No at-rules at all: `@import`, `@font-face` and `@media` are all outside what a glyph needs,
    // and `@` in a selector/value is never part of the rebuilable subset.
    if (css.contains('@')) return null
    if (STYLE_RULE.replace(css, "").trim().isNotEmpty()) return null
    val rules = STYLE_RULE.findAll(css).mapNotNull { match ->
        val selector = match.groupValues[1].trim()
        // A selector is kept as text; `<`/`>`/`{`/`}` would break out of the block, and `//` would
        // start a comment that also escapes it.
        if (selector.isEmpty() || selector.any { it in "<>" } || selector.contains("//")) return@mapNotNull null
        val declarations = match.groupValues[2].split(';').mapNotNull(::sanitizeDeclaration)
        if (declarations.isEmpty()) null else "$selector { ${declarations.joinToString("; ")} }"
    }.toList()
    return rules.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

/** `"color: red"` when both sides are allowed, null otherwise. */
private fun sanitizeDeclaration(declaration: String): String? {
    val colon = declaration.indexOf(':')
    if (colon <= 0) return null
    val property = declaration.substring(0, colon).trim().lowercase()
    val value = declaration.substring(colon + 1).trim()
    if (!isSafeStyleProperty(property)) return null
    if (!isSafeStyleValue(value)) return null
    return "$property: $value"
}
