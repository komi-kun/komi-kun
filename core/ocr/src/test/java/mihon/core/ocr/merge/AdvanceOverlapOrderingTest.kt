// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Reading order of two successive lines that `nextLine` merges at an advance overlap inside the
// 0.45..0.55 band. The ordering stage unions "same run" lines with its own threshold; while that was
// the stale literal 0.45 it unioned exactly the pairs `nextLine` accepts up to
// `MAX_ADVANCE_OVERLAP` (0.55) and then ordered them by top *inside* the union, which puts a
// right-hand tategaki column that starts lower before the left-hand column that continues the text.
// The two columns overlap horizontally by half a column width (detector unclip expansion), which is
// what makes their advance overlap 0.50 rather than 0.
// KUN <--
package mihon.core.ocr.merge

import io.kotest.matchers.shouldBe
import mihon.core.ocr.EngineLine
import mihon.core.ocr.MergeConfig
import mihon.core.ocr.NormalizedBBox
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.WritingDirection
import org.junit.jupiter.api.Test

class AdvanceOverlapOrderingTest {

    private companion object {
        const val PAGE = 1000

        /** One vertical column's width; the two columns overlap by half of it (0.50 advance overlap). */
        const val COLUMN_WIDTH = 0.03
    }

    @Test
    fun `two columns merged at an advance overlap of 0 to 55 keep right-to-left order`() {
        // Column 1 of the block: the right-hand one, read first, and it starts 0.03 lower than the
        // column that continues the text below-left of it.
        val firstColumn = column("あい", left = 0.50, top = 0.20, height = 0.40)
        // Column 2: left of column 1 and 0.03 higher, tall enough that both columns still share a
        // centre (which is what `nextLine`'s centring rule requires for a vertical run).
        val secondColumn = column("かきく", left = 0.485, top = 0.17, height = 0.46)

        val blocks = OwOCRMerger(MergeConfig(language = OcrLanguage.JAPANESE))
            .merge(listOf(firstColumn, secondColumn), PAGE, PAGE)

        // The two are one paragraph (nextLine accepts them at a 0.50 advance overlap)...
        blocks.size shouldBe 1
        // ...and its columns are in reading order: the right column first, then the one to its left.
        blocks.single().lines shouldBe listOf("あい", "かきく")
    }

    /** A vertical column of [text] at normalized page coordinates (0..1) for a 1000x1000 page. */
    private fun column(text: String, left: Double, top: Double, height: Double): EngineLine = EngineLine(
        text = text,
        bbox = NormalizedBBox(
            left = left,
            top = top,
            right = left + COLUMN_WIDTH,
            bottom = top + height,
        ),
        writingDirection = WritingDirection.TTB,
        language = OcrLanguage.JAPANESE,
    )
}
