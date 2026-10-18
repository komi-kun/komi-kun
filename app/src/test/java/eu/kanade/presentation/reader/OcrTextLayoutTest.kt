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
    fun `a short column is centred vertically`() {
        val placed = layoutVerticalText(
            columns(listOf("あ")),
            rectLeft = 0f,
            rectTop = 0f,
            rectRight = 100f,
            rectBottom = 400f,
            minScale = 0.1f,
        )

        // scale = 100/20 = 5 -> a 100px cell centred in the 400px box.
        assertEquals(150f, placed.single().top, 0.001f)
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
    fun `the legibility floor overflows a short box vertically`() {
        val placed = layoutVerticalText(
            columns(List(10) { "あ" }),
            rectLeft = 0f,
            rectTop = 0f,
            rectRight = 100f,
            rectBottom = 20f,
            minScale = 0.45f,
        )

        val cell = 20f * placed.first().scale
        assertTrue(placed.first().top < 0f, "the column starts above the box")
        assertTrue(placed.last().top + cell > 20f, "the column ends below the box")
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
