// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Pure layout for the OCR text overlay. Kept free of Compose/Android types (no `TextMeasurer`, no
// `DrawScope`) so the positioning math is unit-testable on the JVM: the Compose layer in
// `OcrOverlay.kt` measures glyphs, calls these functions and draws the returned placements.
//
// The overlay previously drew a vertical (tategaki) block by laying its string out horizontally and
// rotating the whole run 90°, which leaves every glyph on its side ("縦書き rendered as 横書き, just
// rotated"). Real tategaki stacks **upright** glyphs top-to-bottom down a column; [layoutVerticalText]
// does exactly that, one glyph per cell, columns ordered right-to-left.
// KUN <--
package eu.kanade.presentation.reader

import kotlin.math.max
import kotlin.math.min

/** A glyph measured at the overlay's reference font size. [text] is one glyph (see [splitGlyphs]). */
internal data class GlyphMetric(
    val text: String,
    val width: Float,
    val height: Float,
)

/**
 * One glyph placed on screen: an unrotated box whose top-left is ([left], [top]) and whose size is
 * the reference measurement multiplied by [scale]. [rotated] glyphs (the line-like marks in
 * [isRotatedInVertical]) are drawn turned 90° clockwise about their own centre.
 */
internal data class PlacedGlyph(
    val text: String,
    val left: Float,
    val top: Float,
    val scale: Float,
    val rotated: Boolean,
)

/**
 * Lays out a tategaki (vertical Japanese) run as one upright glyph per cell.
 *
 * [columns] holds the glyphs of each column; the first column is drawn **rightmost** (Japanese reads
 * right-to-left). Columns are spread across the block width (so the printed column pitch is kept)
 * and each column's glyphs are stacked top-to-bottom one printed cell apart.
 *
 * **Sizing against the real artwork.** The block rect comes from the OCR detector and hugs the
 * printed text (plus a little of the detector's padding), so it is *not* a font box: filling it
 * makes the overlay text read as oversized. Measured on real manga pages, an N-glyph printed column
 * spans (N-1) cells plus one glyph's ink, and the detector's box adds [VERTICAL_BOX_PAD_CELLS] of
 * padding, so the printed cell is `rectHeight / (N + VERTICAL_BOX_PAD_CELLS)` — not `rectHeight / N`
 * (the cell the overlay used to draw at, ~25% too big).
 *
 * The glyph is then drawn at [GLYPH_INK_FIT] of that cell rather than filling it: a printed CJK
 * glyph's ink is ~0.84 em, while the system font's ink is ~0.89 of its advance, so drawing at the
 * cell size makes every glyph ~20% wider than the printed one. Keeping the cell as the *pitch* and
 * shrinking the glyph inside it makes the drawn ink match the printed ink and keeps the run's
 * extent right.
 *
 * Columns are **top-anchored**: a run shorter than its box stays at the top edge (the printed ink
 * starts a fraction of a cell below the detector's box edge, so [topInset] centres the ink in the
 * box but is capped at [MAX_TOP_INSET_CELLS] — a run in an over-large box must not float to the
 * vertical middle, which was a real bug). Each glyph is centred across its column and vertically
 * centred in its own cell. [minScale] is the legibility floor, and [glyphScale] is the user's
 * overlay text size preference; when the floor wins the column overflows the box and the caller's
 * clip trims it.
 *
 * What can only be judged visually: the exact glyph size against the printed text under it, whether
 * the plate alpha reads well over artwork, and whether the few marks that still rotate look right —
 * the tests pin the geometry, not the aesthetics.
 */
internal fun layoutVerticalText(
    columns: List<List<GlyphMetric>>,
    rectLeft: Float,
    rectTop: Float,
    rectRight: Float,
    rectBottom: Float,
    minScale: Float,
    glyphScale: Float = 1f,
): List<PlacedGlyph> {
    val rectW = rectRight - rectLeft
    val rectH = rectBottom - rectTop
    if (rectW <= 0f || rectH <= 0f) return emptyList()

    val visible = columns.filter { it.isNotEmpty() }
    if (visible.isEmpty()) return emptyList()

    // Full-width CJK metrics are square, so the advance at the reference size is the reference em.
    // Fall back to the line-box height only if every advance is zero.
    val refCell = visible.maxOf { column -> column.maxOf { it.width } }
        .takeIf { it > 0f }
        ?: visible.maxOf { column -> column.maxOf { it.height } }
    if (refCell <= 0f) return emptyList()

    val columnCount = visible.size
    val longestColumn = visible.maxOf { it.size }
    val columnWidth = rectW / columnCount

    // The printed cell: the ink extent of the longest column (N glyph cells less the leading/trailing
    // half-cell of ink) plus the detector's box padding. A narrow block caps it so neighbouring
    // columns cannot collide.
    val fittedCell = min(
        rectH / (longestColumn + VERTICAL_BOX_PAD_CELLS),
        columnWidth * COLUMN_PITCH_HEAD_ROOM,
    )
    var cell = fittedCell
    var scale = cell * GLYPH_INK_FIT * glyphScale / refCell
    if (scale < minScale) {
        // Legibility floor: draw bigger than the block and let the caller's clip trim the overflow,
        // with the cell following the glyph so the stack does not collapse into itself.
        scale = minScale
        cell = refCell * scale / (GLYPH_INK_FIT * glyphScale)
    }

    val inkExtent = cell * (longestColumn - 1) + refCell * scale
    val topInset = ((rectH - inkExtent) / 2f).coerceIn(0f, cell * MAX_TOP_INSET_CELLS)

    val placed = ArrayList<PlacedGlyph>(visible.sumOf { it.size })
    visible.forEachIndexed { columnIndex, column ->
        // Column 0 is the rightmost: step left by a column width each time.
        val columnCenterX = rectRight - columnWidth * (columnIndex + 0.5f)
        column.forEachIndexed { glyphIndex, glyph ->
            val width = glyph.width * scale
            val height = glyph.height * scale
            placed += PlacedGlyph(
                text = glyph.text,
                left = columnCenterX - width / 2f,
                top = rectTop + topInset + cell * glyphIndex + (cell - height) / 2f,
                scale = scale,
                rotated = isRotatedInVertical(glyph.text),
            )
        }
    }
    return placed
}

/**
 * Extra box height (in printed cells) the OCR detector adds around a vertical line's ink: half a
 * cell of ink inset at each end. Calibrated on real pages (an N-glyph column's box measures
 * ~(N + 0.7) printed cells tall); a user-tunable overlay text size covers the rest of the spread.
 */
internal const val VERTICAL_BOX_PAD_CELLS = 0.7f

/**
 * Fraction of a printed cell the drawn glyph's advance occupies.
 *
 * A printed CJK glyph's ink is ~0.84 of its cell, while the system font's ink is only ~0.89 of its
 * advance, so drawing the glyph's *advance* at 0.95 of the printed cell is what makes the overlaid
 * ink match the printed ink instead of overhanging (the overlay used to fill the cell, ~20% too big).
 * Both ratios were measured off real pages rather than assumed.
 */
internal const val GLYPH_INK_FIT = 0.95f

/**
 * How much larger than one printed column pitch the cell may get. Tategaki leading normally puts the
 * pitch slightly above the cell, so a little headroom keeps the ink from touching the next column.
 */
internal const val COLUMN_PITCH_HEAD_ROOM = 1.15f

/** Cap on the top inset, so a run in an over-large box stays at the top instead of floating. */
internal const val MAX_TOP_INSET_CELLS = 0.5f

/**
 * Fit scale for the **horizontal** path, unchanged from the overlay's original behaviour: shrink to
 * fit the box, like a vertical run's rotated counterpart, then raise to [minScale] so the text is
 * never drawn below the legibility floor (the caller's clip trims any overflow).
 */
internal fun horizontalTextScale(
    rectWidth: Float,
    rectHeight: Float,
    textWidth: Float,
    textHeight: Float,
    minScale: Float,
): Float {
    if (rectWidth <= 0f || rectHeight <= 0f || textWidth <= 0f || textHeight <= 0f) return minScale
    return max(min(rectWidth / textWidth, rectHeight / textHeight), minScale)
}

/**
 * True when a block flagged vertical is really horizontal: a single line whose on-screen box is much
 * wider than tall. Multi-line vertical blocks are exempt — several columns legitimately make a block
 * wider than tall — so only an obviously-mislabelled single-line block is kept horizontal.
 */
internal fun isClearlyHorizontal(
    vertical: Boolean,
    lineCount: Int,
    rectWidth: Float,
    rectHeight: Float,
): Boolean = vertical && lineCount <= 1 && rectHeight > 0f &&
    rectWidth > rectHeight * HORIZONTAL_ASPECT_RATIO

/** Width/height ratio above which a single-line vertical block is treated as clearly horizontal. */
internal const val HORIZONTAL_ASPECT_RATIO = 1.5f

/**
 * Splits [text] into drawable glyphs: one Unicode code point each (so surrogate pairs such as emoji
 * stay whole), with combining marks kept attached to the base glyph they modify.
 */
internal fun splitGlyphs(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val out = ArrayList<String>(text.length)
    var i = 0
    while (i < text.length) {
        val codePoint = text.codePointAt(i)
        val charCount = Character.charCount(codePoint)
        val glyph = text.substring(i, i + charCount)
        if (out.isNotEmpty() && isCombiningMark(codePoint)) {
            out[out.size - 1] += glyph
        } else {
            out += glyph
        }
        i += charCount
    }
    return out
}

private fun isCombiningMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(),
    -> true
    else -> false
}

/**
 * True when a glyph should be set sideways (rotated 90° clockwise) inside its vertical cell.
 *
 * **Orientation policy (hybrid).** A tategaki cell holds an *upright* glyph. Two groups are the
 * exception, and both are exactly the marks whose tategaki shape *is* the horizontal glyph turned a
 * quarter turn:
 *
 * 1. [VERTICAL_ROTATED_MARKS] — the stroke family (hyphens, dashes, `~`, `_`, `=`, `|`), which the
 *    font's `vert` feature has no substitution for at all.
 * 2. [FEATURE_ONLY_VERTICAL_MARKS] — `ー`, `〜`, `～`, `｜`, `゠`, whose `vert` substitution exists in
 *    Noto Sans CJK JP but points at glyphs with **no** Unicode code point, so there is no FE fallback
 *    for them either.
 *
 * Every rotated glyph is measured with the **plain** style and drawn by rotation (never shaped by
 * `vert`), so no font can hand back a substituted shape that is then turned sideways; that also keeps
 * them correct on a device whose CJK fallback lacks the feature.
 *
 * Everything else stays upright:
 *
 * - kana and kanji are already upright in tategaki;
 * - CJK punctuation (`、。「」【】…！？` etc.) gets a real, centred tategaki glyph — from the font's
 *   `vert` feature, or from [verticalGlyphForm]'s FE presentation form when the font lacks it — so
 *   rotating the horizontal glyph would draw the wrong shape off-centre;
 * - **Latin letters and digits, ASCII or full-width, are set upright one per cell.** This is a
 *   deliberate convention, not an oversight: the OCR engine does not report the full-width/half-width
 *   distinction or per-glyph orientation (a printed full-width `ＲＰＧ` comes back as ASCII `RPG`, and
 *   real artwork sets it upright), so the overlay always sets it upright. A genuinely sideways
 *   half-width Latin run on the page therefore reads upright afterwards; reproducing it exactly would
 *   need recognition-side orientation metadata.
 *
 * Only the glyph's base code point decides; combining marks attached to it ride along.
 */
internal fun isRotatedInVertical(text: String): Boolean {
    if (text.isEmpty()) return false
    val codePoint = text.codePointAt(0)
    return codePoint in VERTICAL_ROTATED_MARKS || codePoint in FEATURE_ONLY_VERTICAL_MARKS
}

/**
 * The stroke-family marks whose tategaki form is the horizontal glyph rotated 90° clockwise and
 * which have no `vert` substitution at all (verified against Noto Sans CJK JP's GSUB `vert` table):
 * the general/ideographic hyphens and dash, the wavy dash, and the ASCII
 * hyphen/underscore/equals/tilde/bar strokes. (The wave dashes `〜`/`～` *are* substituted, so they
 * are in [FEATURE_ONLY_VERTICAL_MARKS] instead.)
 */
private val VERTICAL_ROTATED_MARKS: Set<Int> = setOf(
    '\u2010'.code, // ‐ hyphen
    '\u2014'.code, // — em dash
    '\uFF0D'.code, // － full-width hyphen-minus
    '\u3030'.code, // 〰 wavy dash
    '-'.code, '_'.code, '='.code, '~'.code, '|'.code, // ASCII strokes
)

/**
 * The marks whose *only* tategaki form comes from the font's `vert` feature: `ー`, `〜`, `～`, `｜`,
 * `゠` (the font substitutes glyphs that carry no Unicode code point, so [verticalGlyphForm] has
 * nothing to map them to). Every one of them is a line-like mark whose printed vertical form is the
 * horizontal glyph turned a quarter turn.
 *
 * Depending on the feature alone would leave them horizontal on a device whose CJK fallback lacks
 * `vert` — and a font that substituted one of them would draw a substituted shape turned 90° — so the
 * overlay measures **every rotated glyph** (this set and [VERTICAL_ROTATED_MARKS]) with the plain
 * style and draws it rotated.
 *
 * Full-width `．` (`U+FF0E`) is deliberately excluded even though its `vert` form also has no code
 * point: that form is a *translation* of the dot within the cell, not a quarter turn, so rotating the
 * horizontal glyph would be wrong.
 */
internal val FEATURE_ONLY_VERTICAL_MARKS: Set<Int> = setOf(
    '\u30FC'.code, // ー katakana-hiragana prolonged sound mark
    '\u301C'.code, // 〜 wave dash
    '\uFF5E'.code, // ～ full-width tilde
    '\uFF5C'.code, // ｜ full-width vertical bar
    '\u30A0'.code, // ゠ katakana double hyphen
)

/**
 * The tategaki form of a single [glyph], for fonts (and fallbacks) whose shaping lacks the `vert`
 * feature: single-character Japanese/full-width punctuation mapped to its Unicode vertical
 * presentation form (U+FE10-U+FE19, U+FE30-U+FE4F).
 *
 * [VERTICAL_GLYPH_FORMS] is a subset of Noto Sans CJK JP's GSUB `vert` table — verified with
 * fontTools against the shipped font, not guessed: every pair is what `vert` substitutes, and the
 * target is a real Unicode code point so it survives a font that has the glyph but no feature.
 * `vert` also maps `ー`, `〜`, `～`, `｜`, `゠` and a few others to glyphs with no Unicode code point;
 * those have no FE form at all, and the ones the overlay cares about are handled by rotation instead
 * (see [FEATURE_ONLY_VERTICAL_MARKS]).
 *
 * The function is idempotent — an FE form is not a key, so it passes through — and it only maps the
 * base code point, appending any trailing combining marks (see [splitGlyphs]) unchanged.
 */
internal fun verticalGlyphForm(glyph: String): String {
    if (glyph.isEmpty()) return glyph
    val codePoint = glyph.codePointAt(0)
    val vertical = VERTICAL_GLYPH_FORMS[codePoint] ?: return glyph
    val baseChars = Character.charCount(codePoint)
    return if (baseChars >= glyph.length) vertical.toString() else vertical + glyph.substring(baseChars)
}

/**
 * The verified punctuation -> vertical-presentation-form table behind [verticalGlyphForm]: every
 * entry is a `vert` substitution in Noto Sans CJK JP 2.004 (fontTools read of its GSUB `vert`
 * lookups), restricted to targets that are real Unicode code points so they survive a font without
 * the feature. `U+3008`/`U+3009` map through the glyph they share with `U+2329`/`U+232A` in that
 * table.
 *
 * Internal rather than private so the test can pin the *whole* table entry by entry: a typo'd target
 * or a missing row is otherwise invisible.
 */
internal val VERTICAL_GLYPH_FORMS: Map<Int, Char> = mapOf(
    0x3001 to '\uFE11', // 、 ︑
    0x3002 to '\uFE12', // 。 ︒
    0x3008 to '\uFE3F', // 〈 ︿
    0x3009 to '\uFE40', // 〉 ﹀
    0x300A to '\uFE3D', // 《 ︽
    0x300B to '\uFE3E', // 》 ︾
    0x300C to '\uFE41', // 「 ﹁
    0x300D to '\uFE42', // 」 ﹂
    0x300E to '\uFE43', // 『 ﹃
    0x300F to '\uFE44', // 』 ﹄
    0x3010 to '\uFE3B', // 【 ︻
    0x3011 to '\uFE3C', // 】 ︼
    0x3014 to '\uFE39', // 〔 ︹
    0x3015 to '\uFE3A', // 〕 ︺
    0x3016 to '\uFE17', // 〖 ︗
    0x3017 to '\uFE18', // 〗 ︘
    0x2015 to '\uFE31', // ― ︱
    0x2025 to '\uFE30', // ‥ ︰
    0x2026 to '\uFE19', // … ︙
    0xFF01 to '\uFE15', // ！ ︕
    0xFF08 to '\uFE35', // （ ︵
    0xFF09 to '\uFE36', // ） ︶
    0xFF0C to '\uFE10', // ， ︐
    0xFF1A to '\uFE13', // ： ︓
    0xFF1B to '\uFE14', // ； ︔
    0xFF1F to '\uFE16', // ？ ︖
    0xFF3B to '\uFE47', // ［ ﹇
    0xFF3D to '\uFE48', // ］ ﹈
    0xFF3F to '\uFE33', // ＿ ︳
    0xFF5B to '\uFE37', // ｛ ︷
    0xFF5D to '\uFE38', // ｝ ︸
)
