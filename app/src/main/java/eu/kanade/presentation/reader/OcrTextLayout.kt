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
 * the reference measurement multiplied by [scale]. [rotated] glyphs (Latin/digits in tategaki) are
 * drawn turned 90° clockwise about their own centre.
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
 * the plate alpha reads well over artwork, and whether rotated Latin looks right — the tests pin the
 * geometry, not the aesthetics.
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
 * Kana, kanji and CJK punctuation (。、) stay **upright**; printable ASCII — Latin letters, digits and
 * ASCII punctuation — is rotated, the traditional way Latin runs appear inside tategaki. Full-width
 * digits (０-９) are CJK-width, so they stay upright. ASCII never forces the whole run back to a
 * rotated-horizontal line: only the individual Latin glyph turns.
 *
 * The CJK marks in [VERTICAL_ROTATED_MARKS] rotate too: their vertical form *is* the horizontal
 * glyph turned 90° clockwise. The printed page shows ー as a vertical stroke and 「 as a top-right
 * corner, so leaving them upright (the horizontal glyph) visibly mismatches the artwork.
 */
internal fun isRotatedInVertical(text: String): Boolean =
    text.isNotEmpty() && text.all { it in '!'..'~' || it in VERTICAL_ROTATED_MARKS }

/**
 * CJK punctuation and marks whose tategaki form is the horizontal glyph rotated 90° clockwise:
 * strokes and dashes (ー and the wave/hyphen family), brackets and corner quotes, and the ellipsis
 * (a horizontal … reads as a vertical ⋮). Their shapes are near-symmetric or confirmed against
 * printed pages, so the rotation direction is not in doubt.
 */
private val VERTICAL_ROTATED_MARKS: Set<Char> = setOf(
    '\u30FC', // ー katakana-hiragana prolonged sound mark
    '\u301C', '\uFF5E', '\u3030', // 〜 ～ 〰 wave dashes
    '\u2010', '\u2014', '\u2015', '\uFF0D', // ‐ — ― － dashes
    '\u2026', '\u2025', // … ‥ ellipsis
    '\u300C', '\u300D', '\u300E', '\u300F', // 「 」 『 』
    '\uFF08', '\uFF09', '\u3014', '\u3015', '\uFF3B', '\uFF3D', // （ ） 〔 〕 ［ ］
    '\u3010', '\u3011', '\u3008', '\u3009', '\u300A', '\u300B', // 【 】 〈 〉 《 》
    '\u3016', '\u3017', // 〖 〗
)
