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

    // KUN -->
    /** Three same-orientation dialogue columns, so the page's median column thickness is 0.038. */
    private fun dialogueColumns(): List<EngineLine> = listOf(
        line("おはよう", 0.70, 0.10, 0.738, 0.30, direction = WritingDirection.TTB),
        line("ございます", 0.65, 0.10, 0.688, 0.30, direction = WritingDirection.TTB),
        line("きょうも", 0.60, 0.10, 0.638, 0.30, direction = WritingDirection.TTB),
    )

    /** [text] as a vertical column 0.115 wide (1.55x the dialogue median) with no neighbour. */
    private fun isolatedOversizedColumn(text: String): EngineLine =
        line(text, 0.20, 0.10, 0.315, 0.30, direction = WritingDirection.TTB)
    // KUN <--

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

    // ------------------------------------------------------------------------------------
    // Border-touching boxes: two text patches whose boxes abut must not become one block
    // ------------------------------------------------------------------------------------

    @Test
    fun `touching side-by-side text blocks stay separate`() {
        val merger = OwOCRMerger()
        val lines = listOf(
            line("あいう", 0.10, 0.10, 0.40, 0.20),
            line("かきく", 0.10, 0.21, 0.40, 0.31),
            line("さしす", 0.40, 0.10, 0.70, 0.20),
            line("たちつ", 0.40, 0.21, 0.70, 0.31),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 2
        blocks[0].lines shouldBe listOf("あいう", "かきく")
        blocks[1].lines shouldBe listOf("さしす", "たちつ")
    }

    @Test
    fun `small-gap side-by-side text blocks stay separate`() {
        val merger = OwOCRMerger()
        val lines = listOf(
            line("あいう", 0.10, 0.10, 0.40, 0.20),
            line("かきく", 0.10, 0.21, 0.40, 0.31),
            line("さしす", 0.45, 0.10, 0.75, 0.20),
            line("たちつ", 0.45, 0.21, 0.75, 0.31),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 2
        blocks[0].lines shouldBe listOf("あいう", "かきく")
        blocks[1].lines shouldBe listOf("さしす", "たちつ")
    }

    @Test
    fun `touching single-line patches stay separate`() {
        val merger = OwOCRMerger()
        val lines = listOf(
            line("あい", 0.10, 0.10, 0.30, 0.20),
            line("うえ", 0.30, 0.10, 0.50, 0.20),
        )

        merger.merge(lines, imageW = 1000, imageH = 1000) shouldHaveSize 2
    }

    @Test
    fun `detector-split single line with a small gap is rejoined`() {
        val merger = OwOCRMerger()
        val lines = listOf(
            line("あい", 0.10, 0.10, 0.30, 0.20),
            line("うえ", 0.305, 0.10, 0.50, 0.20),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 1
        blocks.first().lines shouldBe listOf("あい", "うえ")
    }

    @Test
    fun `mixed-size stacked boxes stay separate`() {
        val merger = OwOCRMerger()
        val lines = listOf(
            line("あいうえお", 0.10, 0.10, 0.40, 0.20),
            line("かきく", 0.10, 0.21, 0.25, 0.41),
        )

        merger.merge(lines, imageW = 1000, imageH = 1000) shouldHaveSize 2
    }

    @Test
    fun `horizontal and vertical boxes never merge`() {
        val merger = OwOCRMerger()
        val lines = listOf(
            line("キャプション", 0.10, 0.10, 0.50, 0.20),
            line("たてがき", 0.45, 0.10, 0.55, 0.25, direction = WritingDirection.TTB),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 2
        blocks.flatMap { it.lines }.toSet() shouldBe setOf("キャプション", "たてがき")
    }

    @Test
    fun `side-by-side tategaki columns still form one paragraph`() {
        val merger = OwOCRMerger()
        val lines = listOf(
            line("あいうえ", 0.80, 0.10, 0.90, 0.60, direction = WritingDirection.TTB),
            line("かきくけ", 0.70, 0.10, 0.80, 0.60, direction = WritingDirection.TTB),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 1
        blocks.first().vertical shouldBe true
        blocks.first().lines shouldBe listOf("あいうえ", "かきくけ")
    }

    @Test
    fun `legacy side-by-side text blocks stay separate`() {
        val merger = OwOCRMerger(MergeConfig(language = OcrLanguage.ENGLISH))
        val lines = listOf(
            line("hello", 0.10, 0.10, 0.30, 0.15, language = OcrLanguage.ENGLISH),
            line("there", 0.10, 0.16, 0.30, 0.21, language = OcrLanguage.ENGLISH),
            line("world", 0.32, 0.10, 0.50, 0.15, language = OcrLanguage.ENGLISH),
            line("again", 0.32, 0.16, 0.50, 0.21, language = OcrLanguage.ENGLISH),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 2
        blocks[0].lines shouldBe listOf("hello", "there")
        blocks[1].lines shouldBe listOf("world", "again")
    }

    // KUN -->
    @Test
    fun `implausible Japanese noise reads are rejected`() {
        val merger = OwOCRMerger()
        val lines = listOf(
            line("干S", 0.10, 0.10, 0.20, 0.20),
            line("手15", 0.20, 0.10, 0.30, 0.20),
            line("七5", 0.30, 0.10, 0.40, 0.20),
            line("115", 0.40, 0.10, 0.50, 0.20),
            line("415", 0.50, 0.10, 0.60, 0.20),
            line("漢字", 0.60, 0.10, 0.70, 0.20),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 1
        blocks.first().lines shouldBe listOf("漢字")
    }

    @Test
    fun `plausible short Japanese reads are kept`() {
        val merger = OwOCRMerger()
        val lines = listOf(
            line("ざわざわ", 0.10, 0.10, 0.30, 0.15),
            line("あ", 0.10, 0.30, 0.20, 0.35),
            line("第51話", 0.10, 0.50, 0.30, 0.55),
            line("100円", 0.10, 0.70, 0.30, 0.75),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 4
        blocks.map { it.lines.first() } shouldBe listOf("ざわざわ", "あ", "第51話", "100円")
    }

    @Test
    fun `horizontal same-line fragments where right fragment is slightly higher still order left-to-right`() {
        val merger = OwOCRMerger()
        val lines = listOf(
            line("あい", 0.10, 0.100, 0.30, 0.200),
            line("うえ", 0.305, 0.099, 0.50, 0.199),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 1
        blocks.first().lines shouldBe listOf("あい", "うえ")
    }

    @Test
    fun `detector-split fragment inside a multi-line horizontal paragraph joins the paragraph in reading order`() {
        val merger = OwOCRMerger()
        val lines = listOf(
            line("あい", 0.10, 0.10, 0.30, 0.20),
            line("うえ", 0.305, 0.10, 0.50, 0.20),
            line("かきくけ", 0.10, 0.21, 0.50, 0.31),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 1
        blocks.first().lines shouldBe listOf("あい", "うえ", "かきくけ")
    }

    @Test
    fun `detector-split fragment inside a multi-column vertical paragraph joins the paragraph in reading order`() {
        val merger = OwOCRMerger()
        val lines = listOf(
            line("あい", 0.80, 0.10, 0.90, 0.30, direction = WritingDirection.TTB),
            line("うえ", 0.80, 0.305, 0.90, 0.50, direction = WritingDirection.TTB),
            line("かきくけ", 0.70, 0.10, 0.80, 0.50, direction = WritingDirection.TTB),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 1
        blocks.first().vertical shouldBe true
        blocks.first().lines shouldBe listOf("あい", "うえ", "かきくけ")
    }

    @Test
    fun `single-character squareish line adopts vertical from multi-character neighbor`() {
        val merger = OwOCRMerger()
        // "同年" (vertical=true, right column) and "秋" (aspect ratio near 1.0, left column)
        val lines = listOf(
            line("同年", 0.80, 0.10, 0.839, 0.171, direction = WritingDirection.TTB),
            line("秋", 0.75, 0.10, 0.792, 0.135, direction = WritingDirection.LTR),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 1
        blocks.first().vertical shouldBe true
        blocks.first().lines shouldBe listOf("同年", "秋")
    }

    @Test
    fun `tilted and oversized single-character SFX noise is filtered out`() {
        val merger = OwOCRMerger()
        val lines = listOf(
            // Normal dialogue lines (thickness ~0.038)
            line("おはよう", 0.70, 0.10, 0.738, 0.30, direction = WritingDirection.TTB),
            line("ございます", 0.65, 0.10, 0.688, 0.30, direction = WritingDirection.TTB),
            line("きょうも", 0.60, 0.10, 0.638, 0.30, direction = WritingDirection.TTB),
            // Legitimate 1-character line (thickness 0.035, rotation 0.0)
            line("あ", 0.55, 0.10, 0.585, 0.135, direction = WritingDirection.TTB),
            // Tilted 1-char SFX ("心" with rotation 0.20 rad)
            EngineLine(
                text = "心",
                bbox = NormalizedBBox(0.40, 0.10, 0.52, 0.194, rotation = 0.20),
                writingDirection = WritingDirection.LTR,
                characterSize = 0.12,
                hasJpText = true,
                hasKanji = true,
                rotation = 0.20,
            ),
            // Oversized 1-char SFX ("出", thickness 0.096 > 1.55 * 0.038)
            EngineLine(
                text = "出",
                bbox = NormalizedBBox(0.20, 0.10, 0.31, 0.196, rotation = 0.0),
                writingDirection = WritingDirection.LTR,
                characterSize = 0.11,
                hasJpText = true,
                hasKanji = true,
                rotation = 0.0,
            ),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        // "心" and "出" must be dropped, while dialogue and "あ" are kept
        val allLines = blocks.flatMap { it.lines }
        allLines shouldBe listOf("おはよう", "ございます", "きょうも", "あ")
    }

    @Test
    fun `isolated oversized column read as one character plus an ellipsis is kept`() {
        val merger = OwOCRMerger()
        val blocks = merger.merge(dialogueColumns() + isolatedOversizedColumn("話…"), imageW = 1000, imageH = 1000)

        // `話…` is two characters on the cleaned transcript, so the one-character SFX rule does not
        // apply at all; the line survives as its own block.
        blocks.flatMap { it.lines } shouldBe listOf("おはよう", "ございます", "きょうも", "話…")
    }

    @Test
    fun `isolated oversized single kanji is still dropped`() {
        val merger = OwOCRMerger()
        val blocks = merger.merge(dialogueColumns() + isolatedOversizedColumn("話"), imageW = 1000, imageH = 1000)

        blocks.flatMap { it.lines } shouldBe listOf("おはよう", "ございます", "きょうも")
    }

    @Test
    fun `isolated oversized single katakana SFX is still dropped`() {
        val merger = OwOCRMerger()
        val blocks = merger.merge(dialogueColumns() + isolatedOversizedColumn("ド"), imageW = 1000, imageH = 1000)

        blocks.flatMap { it.lines } shouldBe listOf("おはよう", "ございます", "きょうも")
    }

    @Test
    fun `isolated oversized two-glyph line is unaffected`() {
        val merger = OwOCRMerger()
        val blocks = merger.merge(dialogueColumns() + isolatedOversizedColumn("はい"), imageW = 1000, imageH = 1000)

        blocks.flatMap { it.lines } shouldBe listOf("おはよう", "ございます", "きょうも", "はい")
    }

    @Test
    fun `short kanji furigana misread is filtered out`() {
        val merger = OwOCRMerger()
        val lines = listOf(
            // Main kanji line (thickness 0.04)
            line("文章", 0.50, 0.10, 0.54, 0.30, direction = WritingDirection.TTB),
            // Tiny 1-char kanji misread furigana to the right of the column (thickness 0.0108 < 0.55 * 0.04 = 0.022)
            line("会", 0.542, 0.10, 0.5528, 0.15, direction = WritingDirection.TTB),
        )

        val blocks = merger.merge(lines, imageW = 1000, imageH = 1000)

        blocks shouldHaveSize 1
        blocks.first().lines shouldBe listOf("文章")
    }
    // KUN <--
}
