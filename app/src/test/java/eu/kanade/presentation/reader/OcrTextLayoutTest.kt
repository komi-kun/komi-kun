// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package eu.kanade.presentation.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Geometry of the OCR overlay's tategaki (vertical Japanese) layout: upright glyphs stacked
 * top-to-bottom in right-to-left columns, sized from the block's printed ink extents, floored for
 * legibility.
 *
 * What these tests cannot judge (eyes on the device): how the laid-out size reads against the
 * printed text underneath, whether the plate gives enough contrast over artwork, and whether rotated
 * Latin looks right. They pin the arithmetic, not the aesthetics.
 */
class OcrTextLayoutTest {

    /** Ink-to-advance ratio measured for the system CJK font on a device (printed glyphs: ~0.84). */
    private val systemFontInkRatio = 0.89f

    private fun metric(text: String, size: Float = 20f) = GlyphMetric(text, size, size)

    private fun columns(vararg columnTexts: List<String>, size: Float = 20f): List<List<GlyphMetric>> =
        columnTexts.map { column -> column.map { metric(it, size) } }

    private fun layout(
        columns: List<List<GlyphMetric>>,
        rectLeft: Float = 0f,
        rectTop: Float = 0f,
        rectRight: Float = 100f,
        rectBottom: Float = 100f,
        minScale: Float = 0.1f,
        glyphScale: Float = 1f,
    ) = layoutVerticalText(
        columns = columns,
        rectLeft = rectLeft,
        rectTop = rectTop,
        rectRight = rectRight,
        rectBottom = rectBottom,
        minScale = minScale,
        glyphScale = glyphScale,
    )

    @Test
    fun `a column is stacked one printed cell per glyph, cell derived from the box height`() {
        val placed = layout(columns(listOf("あ", "い", "う")), rectBottom = 300f)

        assertEquals(listOf("あ", "い", "う"), placed.map { it.text })
        // The printed cell is 300 / (3 glyphs + 0.7 cells of detector padding) = 81.08 -- not 300/3,
        // which is what filling the box did and made every overlaid glyph too big.
        assertEquals(81.08f, placed[1].top - placed[0].top, 0.05f)
        assertEquals(81.08f, placed[2].top - placed[1].top, 0.05f)
        // The glyph's advance is drawn at 95% of that cell, so its *ink* (~0.89 of the advance) lands
        // on the printed glyph's ~0.84 em ink.
        assertEquals(81.08f * GLYPH_INK_FIT / 20f, placed[0].scale, 0.001f)
        // Upright, not rotated sideways.
        assertTrue(placed.none { it.rotated })
        // All glyphs share the column centre.
        val centers = placed.map { it.left + 20f * it.scale / 2f }
        assertEquals(centers[0], centers[1], 0.01f)
        assertEquals(centers[1], centers[2], 0.01f)
    }

    @Test
    fun `a glyph is drawn inside its cell so its ink matches the printed ink`() {
        val placed = layout(columns(listOf("あ"))).single()

        // cell = min(100 / 1.7, 100 * 1.15) = 58.8; the glyph advance is 0.95 * 58.8 = 55.9.
        assertEquals(55.9f, 20f * placed.scale, 0.1f)
    }

    @Test
    fun `a narrow block caps the cell at the column pitch so columns cannot collide`() {
        val placed = layout(columns(listOf("あ")), rectRight = 40f, rectBottom = 400f).single()

        // The height alone would allow 400 / 1.7 = 235, but one column is 40 wide: 40 * 1.15 = 46.
        assertEquals(46f, 20f * (placed.scale / GLYPH_INK_FIT), 0.1f)
    }

    @Test
    fun `the calibrated run matches a real block's printed ink extents`() {
        // A block measured on a real page: 4 columns (longest 8 glyphs) in a 111 x 187 detector box.
        // The printed lettering there is a 21.4px cell whose glyph ink is 18px wide, and the columns
        // sit 26.5px apart.
        val placed = layout(
            columns(List(8) { "あ" }, List(7) { "い" }, List(7) { "う" }, List(5) { "え" }),
            rectRight = 111f,
            rectBottom = 187f,
        )

        // Drawn glyph advance: 0.95 * (187 / 8.7) = 20.4, so its ink (~0.89 of the advance) is 18.1
        // against the printed glyph's measured 18px ink.
        assertEquals(18.1f, systemFontInkRatio * 20f * placed.first().scale, 0.4f)
        // Column pitch: 111 / 4 = 27.75 (printed: 26.5).
        val rightmost = placed.first { it.text == "あ" }
        val leftmost = placed.first { it.text == "え" }
        assertEquals(27.75f, (rightmost.left - leftmost.left) / 3f, 0.2f)
        // Nothing is pushed out of the box by the calibration.
        assertTrue(placed.all { it.top >= 0f && it.top < 187f })
    }

    @Test
    fun `a short run stays at the top of an over-large box, not in the middle`() {
        val placed = layout(columns(listOf("あ")), rectBottom = 600f).single()

        // The cell is capped by the 100px column (115); the inset is capped at half a cell, so the
        // run sits in the first cell instead of floating to the middle of the 600px box.
        assertTrue(placed.top < 115f, "run must stay in the first cell, was ${placed.top}")
        assertEquals(60.4f, placed.top, 0.1f)
    }

    @Test
    fun `columns are ordered right to left and share the block width`() {
        val placed = layout(columns(listOf("あ"), listOf("い")), rectBottom = 200f)

        val first = placed.first { it.text == "あ" }
        val second = placed.first { it.text == "い" }
        assertTrue(first.left > second.left, "the first column must be the rightmost")
        // Column centres are one column pitch (rectWidth / columns) apart, each centred in its slot.
        assertEquals(75f, first.left + 20f * first.scale / 2f, 0.001f)
        assertEquals(25f, second.left + 20f * second.scale / 2f, 0.001f)
    }

    @Test
    fun `every column is top-anchored even when shorter than the longest`() {
        val placed = layout(
            columns(listOf("あ", "い", "う"), listOf("か")),
            rectTop = 10f,
            rectRight = 200f,
            rectBottom = 610f,
        )

        // The longest column (3) defines the cell; the 1-glyph column must still start at the same
        // top edge as the rest of the run.
        val first = placed.first { it.text == "あ" }
        val short = placed.first { it.text == "か" }
        assertEquals(first.top, short.top, 0.001f)
        assertTrue(short.top < placed.first { it.text == "い" }.top)
        assertTrue(first.top < 10f + 115f, "the run must not float down the box")
    }

    @Test
    fun `the reference cell is the glyph advance, so a taller line box does not shrink the glyph`() {
        // Advance 20 (its em), line-box height 30 (font leading): the cell must still come from the
        // box height and the glyph count, and the glyph must be drawn at GLYPH_INK_FIT of it.
        val placed = layout(listOf(listOf(GlyphMetric("あ", width = 20f, height = 30f))), rectBottom = 100f)

        // Had the 30px line box been the reference, the glyph would be drawn at 58.8 * 0.95 / 30.
        assertEquals(55.9f, 20f * placed.single().scale, 0.1f)
        assertEquals(22.1f, placed.single().left, 0.1f)
    }

    @Test
    fun `the legibility floor wins and overflows the box for the clip to trim`() {
        val placed = layout(columns(listOf("あ")), rectRight = 5f, rectBottom = 200f, minScale = 0.45f)

        // The fitted glyph would be 5.75 * 0.95 / 20 = 0.27; the floor lifts it to 0.45, so the glyph
        // is wider than the box.
        assertEquals(0.45f, placed.single().scale, 0.001f)
        assertTrue(placed.single().left < 0f, "floored glyph overflows and relies on the caller's clip")
    }

    @Test
    fun `the legibility floor overflows a short box downward from the top`() {
        val placed = layout(columns(List(10) { "あ" }), rectBottom = 20f, minScale = 0.45f)

        val cell = 20f * (placed.first().scale / GLYPH_INK_FIT)
        // Top-anchored: the first cell sits at the box top and the overflow runs below it.
        assertTrue(placed.first().top < cell, "the run starts at the top of the box")
        assertTrue(placed.last().top + cell > 20f, "the column overflows below the box")
    }

    @Test
    fun `the overlay text size preference scales the glyph, keeping the printed column pitch`() {
        val base = layout(columns(listOf("あ", "い")), rectBottom = 200f)
        val bigger = layout(columns(listOf("あ", "い")), rectBottom = 200f, glyphScale = 1.5f)

        // The cell (the printed pitch) is unchanged; only the drawn glyph grows.
        assertEquals(base[1].top - base[0].top, bigger[1].top - bigger[0].top, 0.01f)
        assertEquals(1.5f, bigger[0].scale / base[0].scale, 0.001f)
    }

    @Test
    fun `punctuation occupies its own cell`() {
        val placed = layout(columns(listOf("あ", "。", "い")), rectBottom = 300f)

        assertEquals(listOf("あ", "。", "い"), placed.map { it.text })
        assertEquals(placed[0].left, placed[1].left, 0.001f)
        assertEquals(placed[1].left, placed[2].left, 0.001f)
        assertEquals(81.08f, placed[1].top - placed[0].top, 0.05f)
    }

    @Test
    fun `empty columns are ignored`() {
        val placed = layout(columns(listOf("あ"), emptyList()), rectBottom = 200f)

        assertEquals(1, placed.size)
        // The lone non-empty column is centred in the block (empty columns contribute no width).
        assertEquals(50f, placed.single().left + 20f * placed.single().scale / 2f, 0.001f)
    }

    @Test
    fun `kana and kanji stay upright while ascii latin and digits rotate`() {
        val placed = layout(
            columns(listOf("A", "あ", "1", "０", "。")),
            rectRight = 100f,
            rectBottom = 500f,
        )

        assertTrue(placed[0].rotated, "A is rotated")
        assertFalse(placed[1].rotated, "あ stays upright")
        assertTrue(placed[2].rotated, "1 is rotated")
        assertFalse(placed[3].rotated, "the full-width digit ０ stays upright")
        assertFalse(placed[4].rotated, "。 stays upright")

        assertTrue(isRotatedInVertical("Hello"))
        assertTrue(isRotatedInVertical("42"))
        assertFalse(isRotatedInVertical("あ"))
        assertFalse(isRotatedInVertical("漢"))
        assertFalse(isRotatedInVertical("０"))
        assertFalse(isRotatedInVertical("。"))
        assertFalse(isRotatedInVertical(""))
    }

    @Test
    fun `cjk marks whose vertical form is a rotation are set sideways`() {
        // ー as a vertical stroke, 「」 as corners, … as a vertical ⋮ (all seen on printed pages).
        assertTrue(isRotatedInVertical("ー"))
        assertTrue(isRotatedInVertical("「"))
        assertTrue(isRotatedInVertical("」"))
        assertTrue(isRotatedInVertical("…"))
        assertTrue(isRotatedInVertical("（"))
        assertTrue(isRotatedInVertical("〜"))
        // The ideographic full stop keeps its own upright cell placement.
        assertFalse(isRotatedInVertical("。"))
        assertFalse(isRotatedInVertical("、"))
    }

    @Test
    fun `a clearly horizontal single-line block stays horizontal`() {
        assertTrue(isClearlyHorizontal(vertical = true, lineCount = 1, rectWidth = 300f, rectHeight = 50f))
        assertFalse(isClearlyHorizontal(vertical = true, lineCount = 1, rectWidth = 50f, rectHeight = 300f))
        // Several columns legitimately make a vertical block wider than tall.
        assertFalse(isClearlyHorizontal(vertical = true, lineCount = 3, rectWidth = 300f, rectHeight = 50f))
        // Already horizontal blocks are untouched.
        assertFalse(isClearlyHorizontal(vertical = false, lineCount = 1, rectWidth = 300f, rectHeight = 50f))
    }

    @Test
    fun `the horizontal fit is unchanged and floors`() {
        // Shrink to fit the smaller axis (this is the pre-existing behaviour).
        assertEquals(0.5f, horizontalTextScale(100f, 100f, 200f, 50f, 0.1f), 0.0001f)
        assertEquals(0.5f, horizontalTextScale(100f, 100f, 50f, 200f, 0.1f), 0.0001f)
        // Never below the floor.
        assertEquals(0.45f, horizontalTextScale(5f, 5f, 200f, 200f, 0.45f), 0.0001f)
        // Degenerate boxes fall back to the floor, matching the old early-return guard.
        assertEquals(0.3f, horizontalTextScale(0f, 0f, 200f, 200f, 0.3f), 0.0001f)
    }

    @Test
    fun `splitGlyphs keeps surrogate pairs and combining marks whole`() {
        assertEquals(listOf("漢", "字"), splitGlyphs("漢字"))
        assertEquals(listOf("😀", "字"), splitGlyphs("😀字"))
        // U+3099 combining voiced sound mark stays attached to its base.
        assertEquals(listOf("か\u3099"), splitGlyphs("か\u3099"))
        assertEquals(emptyList<String>(), splitGlyphs(""))
    }

    @Test
    fun `whitespace keeps its cell without breaking the stacking order`() {
        val placed = layout(columns(listOf("あ", " ", "い")), rectBottom = 300f)

        assertEquals(listOf("あ", " ", "い"), placed.map { it.text })
        assertTrue(placed[0].top < placed[1].top && placed[1].top < placed[2].top)
    }
}
// KUN <--
