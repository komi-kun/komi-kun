// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Unit tests for the `OcrLineMerger` seam implementation: paragraph grouping, reading order
// (horizontal + vertical/RTL), furigana filtering and the legacy path (contract §5, 03 §3.6).
// The merger is a clean reimplementation of Chimahon's `OwOCRMerger` (GPL-3.0-or-later) @ f85c063.
// KUN <--
package mihon.core.ocr.merge

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import mihon.core.ocr.EngineLine
import mihon.core.ocr.MergeConfig
import mihon.core.ocr.NormalizedBBox
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.WritingDirection
import org.junit.jupiter.api.Test

class OcrLineMergerTest {

    private fun line(
        text: String,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        direction: WritingDirection = WritingDirection.LTR,
        language: OcrLanguage = OcrLanguage.JAPANESE,
    ) = EngineLine(
        text = text,
        bbox = NormalizedBBox(left, top, right, bottom),
        writingDirection = direction,
        language = language,
    )

    @Test
    fun `stacked horizontal lines merge into one ordered block`() {
        val merger = OwOCRMerger()
        val lines = listOf(
            line("あいう", 0.10, 0.10, 0.40, 0.20),
            line("かきく", 0.10, 0.21, 0.40, 0.31),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 1
        blocks.first().lines shouldBe listOf("あいう", "かきく")
        blocks.first().vertical shouldBe false
        blocks.first().lineGeometries!! shouldHaveSize 2
        blocks.first().language shouldBe "ja"
    }

    @Test
    fun `distant horizontal lines stay as separate blocks in top-to-bottom order`() {
        val merger = OwOCRMerger()
        val lines = listOf(
            line("あ", 0.10, 0.10, 0.20, 0.20),
            line("い", 0.70, 0.70, 0.80, 0.80),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 2
        blocks[0].lines shouldBe listOf("あ")
        blocks[1].lines shouldBe listOf("い")
    }

    @Test
    fun `vertical columns are ordered right-to-left`() {
        val merger = OwOCRMerger()
        val lines = listOf(
            line("あいうえお", 0.80, 0.10, 0.90, 0.60, direction = WritingDirection.TTB),
            line("かきくけこ", 0.30, 0.10, 0.40, 0.60, direction = WritingDirection.TTB),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 2
        blocks[0].xmin shouldBe 0.80f
        blocks[1].xmin shouldBe 0.30f
        blocks[0].vertical shouldBe true
        blocks[1].vertical shouldBe true
    }

    @Test
    fun `furigana is dropped`() {
        val merger = OwOCRMerger()
        val lines = listOf(
            line("漢字", 0.10, 0.20, 0.30, 0.30),
            line("かんじ", 0.10, 0.16, 0.30, 0.19),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 1
        blocks.first().lines shouldBe listOf("漢字")
    }

    @Test
    fun `furigana filter can be disabled`() {
        val merger = OwOCRMerger(MergeConfig(furiganaFilter = false))
        val lines = listOf(
            line("漢字", 0.10, 0.20, 0.30, 0.30),
            line("かんじ", 0.10, 0.16, 0.30, 0.19),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 1
        blocks.first().lines.size shouldBe 2
    }

    @Test
    fun `legacy path groups latin lines with a shared left edge`() {
        val merger = OwOCRMerger(MergeConfig(language = OcrLanguage.ENGLISH))
        val lines = listOf(
            line("hello", 0.10, 0.10, 0.30, 0.15, language = OcrLanguage.ENGLISH),
            line("world", 0.10, 0.16, 0.30, 0.21, language = OcrLanguage.ENGLISH),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 1
        blocks.first().lines shouldBe listOf("hello", "world")
        blocks.first().language shouldBe "en"
    }

    @Test
    fun `empty input yields no blocks`() {
        OwOCRMerger().merge(emptyList(), imageW = 1000, imageH = 1000) shouldHaveSize 0
    }

    @Test
    fun `blank lines are ignored`() {
        val merger = OwOCRMerger()
        val lines = listOf(
            line("  ", 0.10, 0.10, 0.20, 0.20),
            line("あ", 0.10, 0.30, 0.20, 0.40),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 1
        blocks.first().lines shouldBe listOf("あ")
    }

    @Test
    fun `disabled merge config yields no blocks`() {
        val merger = OwOCRMerger(MergeConfig(enabled = false))
        val lines = listOf(line("あ", 0.10, 0.10, 0.20, 0.20))

        merger.merge(lines, imageW = 1000, imageH = 1000) shouldHaveSize 0
    }
}
