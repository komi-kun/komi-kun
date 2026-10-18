// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package eu.kanade.presentation.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Geometry of the OCR overlay's tategaki (vertical Japanese) layout: upright glyphs stacked
 * top-to-bottom in right-to-left columns, sized from the box, floored for legibility.
 *
 * What these tests cannot judge (eyes on the device): how the laid-out size reads against the
 * printed text underneath, whether the translucent plate gives enough contrast over artwork, and
 * whether rotated Latin looks right. They pin the arithmetic, not the aesthetics.
 */
class OcrTextLayoutTest {

    private fun metric(text: String, size: Float = 20f) = GlyphMetric(text, size, size)

    private fun columns(vararg columnTexts: List<String>, size: Float = 20f): List<List<GlyphMetric>> =
        columnTexts.map { column -> column.map { metric(it, size) } }

    @Test
    fun `a single column stacks upright glyphs top to bottom`() {
        val placed = layoutVerticalText(
            columns(listOf("あ", "い", "う")),
            rectLeft = 0f,
            rectTop = 0f,
            rectRight = 100f,
            rectBottom = 300f,
            minScale = 0.1f,
        )

        assertEquals(listOf("あ", "い", "う"), placed.map { it.text })
        assertTrue(placed[0].top < placed[1].top, "second glyph must sit below the first")
        assertTrue(placed[1].top < placed[2].top, "third glyph must sit below the second")
        // Upright, not rotated sideways.
        assertTrue(placed.none { it.rotated })
        // All glyphs share the column centre.
        val centers = placed.map { it.left + 20f * it.scale / 2f }
        assertEquals(centers[0], centers[1], 0.01f)
        assertEquals(centers[1], centers[2], 0.01f)
        // Fills the 100x300 box (scale = min(100/20, 300/60) = 5).
        assertEquals(5f, placed[0].scale, 0.001f)
        assertEquals(0f, placed[0].top, 0.001f)
        assertEquals(100f, placed[1].top, 0.001f)
        assertEquals(200f, placed[2].top, 0.001f)
    }

    @Test
    fun `columns are ordered right to left`() {
        val placed = layoutVerticalText(
            columns(listOf("あ"), listOf("い")),
            rectLeft = 0f,
            rectTop = 0f,
            rectRight = 100f,
            rectBottom = 200f,
            minScale = 0.1f,
        )

        val first = placed.first { it.text == "あ" }
        val second = placed.first { it.text == "い" }
        assertTrue(first.left > second.left, "the first column must be the rightmost")
        assertEquals(50f, first.left, 0.001f)
        assertEquals(0f, second.left, 0.001f)
    }

    @Test
    fun `glyphs are sized from the box width`() {
        val placed = layoutVerticalText(
            columns(listOf("あ")),
            rectLeft = 0f,
            rectTop = 0f,
            rectRight = 40f,
            rectBottom = 400f,
            minScale = 0.1f,
        )

        // A tall, narrow box: the width wins (40/20), not the generous height (400/20).
        assertEquals(2f, placed.single().scale, 0.001f)
        assertEquals(0f, placed.single().left, 0.001f)
    }

    @Test
    fun `a long column shrinks to fit the box height`() {
        val placed = layoutVerticalText(
            columns(List(10) { "あ" }),
            rectLeft = 0f,
            rectTop = 0f,
            rectRight = 100f,
            rectBottom = 100f,
            minScale = 0.1f,
        )

        // 10 cells must fit 100px tall: 100 / (20 * 10) = 0.5.
        assertEquals(0.5f, placed.first().scale, 0.001f)
        assertEquals(0f, placed.first().top, 0.001f)
        assertEquals(90f, placed.last().top, 0.001f)
    }

    @Test
    fun `a short column starts at the box top, not centred`() {
        val placed = layoutVerticalText(
            columns(listOf("あ")),
            rectLeft = 0f,
            rectTop = 0f,
            rectRight = 100f,
            rectBottom = 400f,
            minScale = 0.1f,
        )

        // scale = 100/20 = 5 -> a 100px cell, but top-anchored in the 400px box (was 150 when the
        // column used to float to the vertical middle).
        assertEquals(5f, placed.single().scale, 0.001f)
        assertEquals(0f, placed.single().top, 0.001f)
    }

    @Test
    fun `every column is top-anchored even when shorter than the longest`() {
        val placed = layoutVerticalText(
            columns(listOf("あ", "い", "う"), listOf("か")),
            rectLeft = 0f,
            rectTop = 10f,
            rectRight = 200f,
            rectBottom = 610f,
            minScale = 0.1f,
        )

        // Longest column (3) defines the fit; the 1-glyph column must still start at rectTop.
        val first = placed.first { it.text == "あ" }
        val short = placed.first { it.text == "か" }
        assertEquals(10f, first.top, 0.001f)
        assertEquals(10f, short.top, 0.001f)
        assertTrue(short.top < placed.first { it.text == "い" }.top)
    }

    @Test
    fun `the cell is the glyph advance, not the taller line box`() {
        // A realistic glyph: advance 20 (its em), line-box height 30 (font leading). The cell must
        // be 20, so a 100x100 box scales by 5 -- not 100/30 = 3.33 as when max(width,height) was used.
        val placed = layoutVerticalText(
            listOf(listOf(GlyphMetric("あ", width = 20f, height = 30f))),
            rectLeft = 0f,
            rectTop = 0f,
            rectRight = 100f,
            rectBottom = 100f,
            minScale = 0.1f,
        )

        assertEquals(5f, placed.single().scale, 0.001f)
        // The 20-wide glyph now fills the 100-wide column exactly.
        assertEquals(100f, 20f * placed.single().scale, 0.001f)
        assertEquals(0f, placed.single().left, 0.001f)
    }

    @Test
    fun `advance-sized glyphs fill their column so the run is not inset in the block`() {
        // Two columns in a 100-wide box: each column owns 50px. Advance 20, line box 30: with the
        // correct reference the glyph edge reaches rectRight on the right column and rectLeft on the
        // left one, instead of being centred in its slot with a gap on both sides.
        val placed = layoutVerticalText(
            listOf(
                listOf(GlyphMetric("あ", width = 20f, height = 30f)),
                listOf(GlyphMetric("い", width = 20f, height = 30f)),
            ),
            rectLeft = 0f,
            rectTop = 0f,
            rectRight = 100f,
            rectBottom = 100f,
            minScale = 0.1f,
        )

        val right = placed.first { it.text == "あ" }
        val left = placed.first { it.text == "い" }
        val width = 20f * right.scale
        assertEquals(2.5f, right.scale, 0.001f)
        assertEquals(100f, right.left + width, 0.001f) // flush to the right edge
        assertEquals(0f, left.left, 0.001f) // flush to the left edge
    }

    @Test
    fun `the legibility floor wins and overflows the box for the clip to trim`() {
        val placed = layoutVerticalText(
            columns(listOf("あ")),
            rectLeft = 0f,
            rectTop = 0f,
            rectRight = 5f,
            rectBottom = 200f,
            minScale = 0.45f,
        )

        // Fit would be 0.25 (5 / 20); the floor lifts it to 0.45, so the glyph is wider than the box.
        assertEquals(0.45f, placed.single().scale, 0.001f)
        assertTrue(placed.single().left < 0f, "floored glyph overflows and relies on the caller's clip")
    }

    @Test
    fun `the legibility floor overflows a short box downward from the top`() {
        val placed = layoutVerticalText(
            columns(List(10) { "あ" }),
            rectLeft = 0f,
            rectTop = 0f,
            rectRight = 100f,
            rectBottom = 20f,
            minScale = 0.45f,
        )

        val cell = 20f * placed.first().scale
        // Top-anchored: the first cell sits at the box top and the overflow runs below it.
        assertEquals(0f, placed.first().top, 0.001f)
        assertTrue(placed.last().top + cell > 20f, "the column overflows below the box")
    }

    @Test
    fun `punctuation occupies its own cell`() {
        val placed = layoutVerticalText(
            columns(listOf("あ", "。", "い")),
            rectLeft = 0f,
            rectTop = 0f,
            rectRight = 100f,
            rectBottom = 300f,
            minScale = 0.1f,
        )

        assertEquals(listOf("あ", "。", "い"), placed.map { it.text })
        assertEquals(placed[0].left, placed[1].left, 0.001f)
        assertEquals(placed[1].left, placed[2].left, 0.001f)
        assertEquals(100f, placed[1].top - placed[0].top, 0.001f)
    }

    @Test
    fun `empty columns are ignored`() {
        val placed = layoutVerticalText(
            columns(listOf("あ"), emptyList()),
            rectLeft = 0f,
            rectTop = 0f,
            rectRight = 100f,
            rectBottom = 200f,
            minScale = 0.1f,
        )

        assertEquals(1, placed.size)
        // The lone non-empty column is centred in the box (empty columns contribute no width).
        assertEquals(50f, placed.single().left + 20f * placed.single().scale / 2f, 0.001f)
    }

    @Test
    fun `kana and kanji stay upright while ascii latin and digits rotate`() {
        val placed = layoutVerticalText(
            columns(listOf("A", "あ", "1", "０", "。")),
            rectLeft = 0f,
            rectTop = 0f,
            rectRight = 100f,
            rectBottom = 500f,
            minScale = 0.1f,
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
        val placed = layoutVerticalText(
            columns(listOf("あ", " ", "い")),
            rectLeft = 0f,
            rectTop = 0f,
            rectRight = 100f,
            rectBottom = 300f,
            minScale = 0.1f,
        )

        assertEquals(listOf("あ", " ", "い"), placed.map { it.text })
        assertTrue(placed[0].top < placed[1].top && placed[1].top < placed[2].top)
    }
}
// KUN <--
