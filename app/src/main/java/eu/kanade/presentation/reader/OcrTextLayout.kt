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
 * right-to-left). Cells are square-ish and sized from the column width (a vertical box is tall and
 * narrow), then uniformly shrunk until the longest column fits inside the box height — so a single
 * tall column is scaled by the box width and a long column by the box height. [minScale] is the
 * legibility floor; when the floor wins the column overflows the box and the caller's clip trims it.
 *
 * The cell side is the glyph's horizontal **advance** ([GlyphMetric.width]) — ~1 em for full-width
 * CJK — and *not* its measured line-box height. A Compose glyph layout is as tall as the font's full
 * line height (ascent + descent, ~1.2–1.5 em depending on the font); using that as the cell made
 * every glyph under-fill its column, which the eye reads as the text being shrunk and centred in the
 * block rather than sitting on the printed glyphs.
 *
 * Columns are **top-anchored**: every column starts at [rectTop], so a run shorter than its box
 * stays put at the top edge instead of floating to the vertical middle, and each column sits in its
 * own share of the block width, right-to-left. Each glyph is centred across its column and
 * vertically centred inside its own cell, so multi-glyph strings and punctuation (。、), which
 * occupy a full cell, stack naturally.
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
): List<PlacedGlyph> {
    val rectW = rectRight - rectLeft
    val rectH = rectBottom - rectTop
    if (rectW <= 0f || rectH <= 0f) return emptyList()

    val visible = columns.filter { it.isNotEmpty() }
    if (visible.isEmpty()) return emptyList()

    // The cell is the glyph's horizontal advance at the reference size (full-width CJK advance is
    // square, ~1 em). Using the measured line-box height instead would over-state the cell by the
    // font's leading and shrink every glyph. Fall back to the height only if all advances are zero.
    val refCell = visible.maxOf { column -> column.maxOf { it.width } }
        .takeIf { it > 0f }
        ?: visible.maxOf { column -> column.maxOf { it.height } }
    if (refCell <= 0f) return emptyList()

    val columnCount = visible.size
    val longestColumn = visible.maxOf { it.size }
    val columnWidth = rectW / columnCount
    val fitted = min(columnWidth / refCell, rectH / (refCell * longestColumn))
    val scale = max(fitted, minScale)
    val cell = refCell * scale

    val placed = ArrayList<PlacedGlyph>(visible.sumOf { it.size })
    visible.forEachIndexed { columnIndex, column ->
        // Column 0 is the rightmost: step left by a column width each time.
        val columnCenterX = rectRight - columnWidth * (columnIndex + 0.5f)
        // Top-anchored: each column starts at the block's top edge and grows downward, so short
        // columns stay aligned with the first one instead of being centred in the box height.
        column.forEachIndexed { glyphIndex, glyph ->
            val width = glyph.width * scale
            val height = glyph.height * scale
            placed += PlacedGlyph(
                text = glyph.text,
                left = columnCenterX - width / 2f,
                top = rectTop + cell * glyphIndex + (cell - height) / 2f,
                scale = scale,
                rotated = isRotatedInVertical(glyph.text),
            )
        }
    }
    return placed
}

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
 */
internal fun isRotatedInVertical(text: String): Boolean =
    text.isNotEmpty() && text.all { it in '!'..'~' }
