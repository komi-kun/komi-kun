// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Regression suite for the dialogue-merge defects. The box geometry is taken from a real
// vertical-text page that lost dialogue, but every line of text here is a synthetic placeholder:
// the original dialogue is deliberately not reproduced. Each placeholder keeps the character count
// and the script/kanji mix of the line it replaced, so the filters under test see the same
// per-character sizes and take the same text-driven branches as they did on the device.
// Every box below is a detector quad from the desktop replay of the two detectors the app ships
// (fine-tuned `exp2_ep40` and stock `ppocrv6_small_det_stock`), taken with the app's own detection
// parameters (thresh 0.15, box_thresh 0.25, unclip 1.40). Coordinates are kept in page pixels and
// divided by the page size here, so they can be checked against the replay dump one for one.
//
// The defects these tests pin down live in the merge/filter layer of `OwOCRMerger`, not in the
// models: on that page the furigana filter deleted a genuine full-height column (and two short
// full-size ones), the single-character SFX filter deleted a column the recogniser had under-read,
// and a column-gap "fragment" edge glued two stacked, border-less bubbles into one interleaved block.
// KUN <--
package mihon.core.ocr.merge

import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import mihon.core.ocr.EngineLine
import mihon.core.ocr.NormalizedBBox
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.WritingDirection
import mihon.core.ocr.model.OcrTextBlock
import org.junit.jupiter.api.Test

class DialogueMergeRegressionTest {

    private companion object {
        /** Page pixel extents of the replay: the space every box below is written in. */
        const val PAGE_W = 4500.0
        const val PAGE_H = 6400.0

        /** The lower-right bubble: four columns, right to left. */
        const val BUBBLE_LEFT = 2600.0
        const val BUBBLE_TOP = 4930.0
        const val BUBBLE_RIGHT = 3300.0
        const val BUBBLE_BOTTOM = 5850.0

        val BUBBLE_COLUMNS = listOf("サンプルでした", "作業中なので", "例が", "ご説明しますね")

        val UPPER_BUBBLE = listOf("…そうですね", "テストの件")
        val LOWER_BUBBLE = listOf("あの例の文章", "何か変なことでも", "思って", "おわかりでしたか？")
    }

    private fun line(
        text: String,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        direction: WritingDirection = WritingDirection.TTB,
    ) = EngineLine(
        text = text,
        bbox = NormalizedBBox(left / PAGE_W, top / PAGE_H, right / PAGE_W, bottom / PAGE_H),
        writingDirection = direction,
        language = OcrLanguage.JAPANESE,
    )

    /** The fine-tuned detector's 23 boxes on the page, in the order the engine emitted them. */
    private fun fineTunedPage(): List<EngineLine> = listOf(
        line("これから", 4145.0, 506.0, 4292.0, 973.0),
        line("サンプル説明の一文でした", 3943.0, 512.0, 4152.0, 1927.0),
        line("テスト・サンプルの説明文です", 3810.0, 499.0, 3957.0, 2060.0),
        line("例は上の文章…", 837.0, 766.0, 1031.0, 1473.0),
        line("のような例文でして", 709.0, 773.0, 850.0, 1633.0),
        line("そちらのことは常々", 548.0, 759.0, 710.0, 1640.0),
        line("お聞きしておりました", 435.0, 766.0, 549.0, 1726.0),
        line("はい", 1024.0, 760.0, 1104.0, 973.0),
        line("ご質問", 1526.0, 1433.0, 1680.0, 1773.0),
        line("ですよね？", 1412.0, 1433.0, 1533.0, 1946.0),
        // Non-dialogue: a latin misread (dropped: no Japanese) and two tilted drawn SFX.
        line("Gg", 1156.0, 2310.0, 1281.0, 2642.0, direction = WritingDirection.LTR),
        line("タタ", 1794.0, 3471.0, 2411.0, 4259.0, direction = WritingDirection.LTR),
        line("…そうですね", 756.0, 3726.0, 897.0, 4326.0),
        line("テストの件", 602.0, 3713.0, 770.0, 4246.0),
        line("タ", 1759.0, 3999.0, 1962.0, 4223.0, direction = WritingDirection.LTR),
        line("あの例の文章", 929.0, 4418.0, 1112.0, 5087.0),
        line("何か変なことでも", 768.0, 4398.0, 985.0, 5268.0),
        line("思って", 649.0, 4413.0, 803.0, 4786.0),
        line("おわかりでしたか？", 542.0, 4433.0, 656.0, 5333.0),
        // The four-line bubble. Column 2 swallowed its ruby column (217 px vs column 1's 147 px) and
        // column 3 is a bloated 281 px box for two characters (low confidence: 0.50).
        line("サンプルでした", 3127.0, 5006.0, 3274.0, 5779.0),
        line("作業中なので", 2931.0, 4998.0, 3148.0, 5721.0),
        line("例が", 2712.0, 4973.0, 2993.0, 5380.0),
        line("ご説明しますね", 2651.0, 5000.0, 2792.0, 5786.0),
    )

    /** The stock detector's 22 boxes on the same page. */
    private fun stockPage(): List<EngineLine> = listOf(
        line("これから", 4145.0, 480.0, 4312.0, 973.0),
        line("サンプル説明の一文でした", 3957.0, 472.0, 4138.0, 1920.0),
        line("テスト・サンプルの説明文です", 3789.0, 472.0, 3971.0, 2087.0),
        line("はい", 1011.0, 740.0, 1138.0, 980.0),
        line("例は上の文章…", 850.0, 726.0, 1024.0, 1460.0),
        line("のような例文でして", 702.0, 739.0, 864.0, 1640.0),
        line("そちらのことは常々", 554.0, 732.0, 717.0, 1641.0),
        line("お聞きしておりました", 414.0, 732.0, 563.0, 1747.0),
        line("ご質問", 1526.0, 1440.0, 1687.0, 1766.0),
        line("ですよね？", 1399.0, 1426.0, 1546.0, 1960.0),
        line("タ", 2015.0, 3553.0, 2317.0, 3940.0, direction = WritingDirection.LTR),
        line("…そうですね", 756.0, 3720.0, 897.0, 4320.0),
        line("テストの件", 609.0, 3693.0, 776.0, 4240.0),
        line("あの例の文章", 936.0, 4418.0, 1126.0, 5081.0),
        line("何か変なことでも", 796.0, 4413.0, 964.0, 5280.0),
        line("思って", 656.0, 4426.0, 803.0, 4786.0),
        line("おわかりでしたか？", 515.0, 4426.0, 656.0, 5366.0),
        // Punctuation-only read (dropped: no Japanese text in it).
        line("・・・", 388.0, 4493.0, 435.0, 4593.0),
        line("サンプルでした", 3140.0, 5000.0, 3287.0, 5800.0),
        line("作業中なので", 2966.0, 4986.0, 3133.0, 5693.0),
        line("例が", 2789.0, 4977.0, 2962.0, 5289.0),
        line("ご説明しますね", 2617.0, 4979.0, 2800.0, 5800.0),
    )

    private fun merge(lines: List<EngineLine>): List<OcrTextBlock> =
        OwOCRMerger().merge(lines, PAGE_W.toInt(), PAGE_H.toInt())

    /** Reading order of the lines of every block that touches the given page rectangle. */
    private fun linesIn(
        blocks: List<OcrTextBlock>,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ): List<String> = blocks
        .filter { block ->
            block.xmax > (left / PAGE_W).toFloat() &&
                block.xmin < (right / PAGE_W).toFloat() &&
                block.ymax > (top / PAGE_H).toFloat() &&
                block.ymin < (bottom / PAGE_H).toFloat()
        }
        .flatMap { it.lines }

    private fun List<OcrTextBlock>.blockContaining(text: String): OcrTextBlock =
        first { text in it.lines }

    // ------------------------------------------------------------------------------------------
    // (a) The furigana filter must not delete a full-size column.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `fine-tuned detector keeps all four columns of the four-line bubble`() {
        val columns = linesIn(merge(fineTunedPage()), BUBBLE_LEFT, BUBBLE_TOP, BUBBLE_RIGHT, BUBBLE_BOTTOM)

        // The rightmost column (147x773, thinner than the 217 px column beside it) was dropped as
        // ruby of that neighbour, leaving two of the four columns.
        columns shouldBe BUBBLE_COLUMNS
    }

    @Test
    fun `stock detector keeps all four columns of the four-line bubble`() {
        val columns = linesIn(merge(stockPage()), BUBBLE_LEFT, BUBBLE_TOP, BUBBLE_RIGHT, BUBBLE_BOTTOM)

        columns shouldBe BUBBLE_COLUMNS
    }

    @Test
    fun `the short full-size columns the furigana filter used to drop are kept`() {
        // A 2-kana line (213 px tall next to a 707 px column) and a 4-kana line are real lines
        // whose *characters* are the same size as the column they sit beside — only their boxes are
        // shorter/thinner, which is what the filter used to key on.
        merge(fineTunedPage()).blockContaining("これから").lines shouldBe
            listOf("これから", "サンプル説明の一文でした", "テスト・サンプルの説明文です")
        merge(fineTunedPage()).blockContaining("はい").lines shouldBe listOf("はい")

        merge(stockPage()).blockContaining("これから").lines shouldBe
            listOf("これから", "サンプル説明の一文でした", "テスト・サンプルの説明文です")
        merge(stockPage()).blockContaining("はい").lines shouldBe
            listOf("はい", "例は上の文章…", "のような例文でして", "そちらのことは常々", "お聞きしておりました")
    }

    // ------------------------------------------------------------------------------------------
    // (b) A single character read out of a real column is not SFX noise.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `an under-read single-character column of the four-line bubble survives`() {
        // On the device the recogniser returned the first character alone from the 281 px box of the
        // two-character column; the box was 1.82x the page's median column width, so the
        // oversized-single-glyph rule deleted the whole column and the overlay showed only columns
        // 2 and 4.
        val underRead = fineTunedPage().map { if (it.text == "例が") it.copy(text = "例") else it }

        val columns = linesIn(merge(underRead), BUBBLE_LEFT, BUBBLE_TOP, BUBBLE_RIGHT, BUBBLE_BOTTOM)

        columns shouldBe listOf("サンプルでした", "作業中なので", "例", "ご説明しますね")
    }

    @Test
    fun `an oversized single character inside a column paragraph is kept`() {
        val blocks = merge(sfxFixture())

        blocks.blockContaining("作業中なので").lines shouldBe listOf("作業中なので", "例")
    }

    @Test
    fun `an isolated oversized single character is still dropped as SFX`() {
        // Same box and text, moved away from every column: a drawn glyph on its own is not dialogue.
        val isolated = sfxFixture().map {
            if (it.text == "例") {
                it.copy(bbox = NormalizedBBox(1200.0 / PAGE_W, 4973.0 / PAGE_H, 1481.0 / PAGE_W, 5380.0 / PAGE_H))
            } else {
                it
            }
        }

        val blocks = merge(isolated)

        blocks.blockContaining("作業中なので").lines shouldBe listOf("作業中なので")
        blocks.flatMap { it.lines } shouldNotContain "例"
    }

    /**
     * A real four-column bubble whose third column was under-read to one character, in isolation from
     * the rest of the page: the 281 px box is above the 1.55x cut-off of the 168 px median of this
     * set, and the 6-character column beside it is a successive column of it.
     */
    private fun sfxFixture(): List<EngineLine> = listOf(
        line("作業中なので", 2931.0, 4998.0, 3148.0, 5721.0),
        line("例", 2712.0, 4973.0, 2993.0, 5380.0),
        line("ご説明しますね", 2651.0, 5000.0, 2792.0, 5786.0),
    ) + lowerLeft()

    // ------------------------------------------------------------------------------------------
    // (c) Two stacked, border-less bubbles are two paragraphs.
    // ------------------------------------------------------------------------------------------

    /**
     * **Device story (unmodified quads).** The two lower-left bubbles as the fine-tuned detector
     * emitted them. Statically no edge joins them at all here, so this case documents the device
     * overlay rather than reproducing the glue: the two bubbles are two paragraphs because nothing
     * bridged them in this particular capture.
     */
    @Test
    fun `two border-less stacked bubbles stay two paragraphs in reading order`() {
        val blocks = merge(lowerLeft())

        blocks.map { it.lines } shouldBe listOf(UPPER_BUBBLE, LOWER_BUBBLE)
    }

    /**
     * **Device story (the capture that reproduced it), knife-edge.** The same quads with one detector
     * box 5 % wider and 34 px taller: the upper bubble's left column then sits 0.691x the lower
     * column's thickness with a 0.253x gap, so the fragment rule bridges the two bubbles into one
     * cluster whose columns interleave (the shape the device overlay showed).
     *
     * It is kept because it is the real measurement, but it must not be the only reproduction: at
     * 0.6498/0.511 the unmodified quads miss both limits by a hair, so raising
     * `MIN_THICKNESS_RATIO` or lowering `MAX_FRAGMENT_GAP_RATIO` stops exercising
     * `splitInterleavedBands` here without any test noticing. [bridgedLowerLeft] is the fixture that
     * sits comfortably inside the bridge regime.
     */
    @Test
    fun `a column-gap fragment bridge does not glue two stacked bubbles`() {
        val bridged = lowerLeft().map {
            if (it.text == "…そうですね") {
                it.copy(bbox = it.bbox.copy(right = 906.0 / PAGE_W, bottom = 4360.0 / PAGE_H))
            } else {
                it
            }
        }

        merge(bridged).map { it.lines } shouldBe listOf(UPPER_BUBBLE, LOWER_BUBBLE)
    }

    /**
     * The same two bubbles with the bridge geometry **comfortably** inside the fragment limits: the
     * upper left column is 195 px to the lower column's 217 px (0.90x thickness, over the 0.65
     * cut-off), x-aligned over 0.83 of its width (over the 0.8 cut-off) and 29 px above it (0.15x,
     * less than half the 0.35 cut-off). Pre-fix — or with `splitInterleavedBands` removed — the
     * fragment edge joins all four columns of the two bubbles into one cluster, the right-to-left
     * sort interleaves them and the page shows a single block.
     */
    @Test
    fun `a comfortably bridged pair of stacked bubbles is still two paragraphs`() {
        val bridged = bridgedLowerLeft()

        merge(bridged).map { it.lines } shouldBe listOf(UPPER_BUBBLE, LOWER_BUBBLE)
    }

    /** [lowerLeft] with the fragment bridge widened from the knife-edge to the comfortable regime. */
    private fun bridgedLowerLeft(): List<EngineLine> = lowerLeft().map {
        if (it.text == "…そうですね") {
            it.copy(
                bbox = NormalizedBBox(
                    left = 735.0 / PAGE_W,
                    top = it.bbox.top,
                    right = 930.0 / PAGE_W,
                    bottom = 4369.0 / PAGE_H,
                ),
            )
        } else {
            it
        }
    }

    /**
     * **The default detector's two bubbles.** The stock detector ("bubbles + sound effects") reads the
     * same two bubbles with its own quads and no edge joins them, so it must also produce two
     * paragraphs in reading order — the bubble geometry is a property of the page, not of one
     * detector's boxes.
     */
    @Test
    fun `the stock detector also keeps the two stacked bubbles as two paragraphs`() {
        val blocks = merge(stockLowerLeft())

        blocks.map { it.lines } shouldBe listOf(UPPER_BUBBLE, LOWER_BUBBLE)
    }

    /**
     * The stock quads bridged the same comfortable way (0.90x thickness, 0.15x gap, 0.82 advance
     * overlap): the detector-independent half of the `splitInterleavedBands` guard.
     */
    @Test
    fun `the stock detector's bubbles are split back apart when a fragment bridges them`() {
        val bridged = stockLowerLeft().map {
            if (it.text == "…そうですね") {
                it.copy(
                    bbox = NormalizedBBox(
                        left = 769.0 / PAGE_W,
                        top = it.bbox.top,
                        right = 920.0 / PAGE_W,
                        bottom = 4390.0 / PAGE_H,
                    ),
                )
            } else {
                it
            }
        }

        merge(bridged).map { it.lines } shouldBe listOf(UPPER_BUBBLE, LOWER_BUBBLE)
    }

    private fun lowerLeft(): List<EngineLine> = fineTunedPage().filter {
        it.text in (UPPER_BUBBLE + LOWER_BUBBLE).toSet()
    }

    private fun stockLowerLeft(): List<EngineLine> = stockPage().filter {
        it.text in (UPPER_BUBBLE + LOWER_BUBBLE).toSet()
    }

    // ------------------------------------------------------------------------------------------
    // (d) The band threshold: columns of one bubble vs two stacked bubbles.
    // ------------------------------------------------------------------------------------------

    /**
     * `BAND_RUN_OVERLAP` is the whole rule: two columns whose run-axis (vertical) overlap is at least
     * half the shorter one belong to the same stacked band — one bubble's stack — while the two
     * stacked bubbles of this page overlap 0.
     *
     * Three columns: an upper pair (`上これは` / `上テスト`, one bubble, side by side) and `下ですよ`,
     * below and to the right, bridged to `上これは` by a fragment edge. `上テスト` extends down past
     * `下ですよ`'s top by `overlapPx` px, out of `下ですよ`'s 400 px height, so the two bands' overlap
     * ratio is exactly `overlapPx / 400`:
     *
     *  * `>= 0.5` (200 px): `上テスト` counts as part of the lower band, so there is only one band and
     *    nothing is split — the column really does live in both rows, which is what the threshold
     *    means by "same bubble";
     *  * `< 0.5` (190 px): two bands whose reading order alternates, i.e. two bubbles, split back
     *    into one paragraph per bubble.
     */
    @Test
    fun `a band overlap of exactly half still counts as one bubble`() {
        // One band, so the cluster is one paragraph: the right column top-to-bottom, then the
        // left column that spans both rows.
        merge(bandFixture(overlapPx = 200.0)).map { it.lines } shouldBe listOf(
            listOf("上これは", "下ですよ", "上テスト"),
        )
    }

    @Test
    fun `a band overlap above half also counts as one bubble`() {
        merge(bandFixture(overlapPx = 240.0)).map { it.lines } shouldBe listOf(
            listOf("上これは", "下ですよ", "上テスト"),
        )
    }

    @Test
    fun `a band overlap below half is two bubbles and is split`() {
        // Two bands, so the guard splits the cluster into the upper bubble and the lower one. (The
        // row stage then reverses them because their spans overlap; the grouping is what is pinned.)
        merge(bandFixture(overlapPx = 190.0)).map { it.lines.toSet() }.toSet() shouldBe setOf(
            setOf("上これは", "上テスト"),
            setOf("下ですよ"),
        )
    }

    /**
     * Three vertical columns in page pixels: `上これは` (upper right, 200x400), `上テスト` (upper left,
     * 200x(430+`overlapPx`)) and `下ですよ` (lower right, 200x400), the last one 30 px (0.15x, well
     * inside the 0.35 fragment limit) below `上これは` so the fragment rule bridges them. Every box is
     * 200 px wide and the texts are kana-only, so neither the furigana nor the single-character SFX
     * filter (nor the bloated-column trim) touches them.
     */
    private fun bandFixture(overlapPx: Double): List<EngineLine> = listOf(
        line("上これは", 800.0, 2000.0, 1000.0, 2400.0),
        line("上テスト", 600.0, 2000.0, 800.0, 2430.0 + overlapPx),
        line("下ですよ", 800.0, 2430.0, 1000.0, 2830.0),
    )

    // ------------------------------------------------------------------------------------------
    // Ruby is still dropped: the filter was narrowed, not disabled.
    // ------------------------------------------------------------------------------------------

    /**
     * A vertical kanji column and a ruby run on its right: one character wide per the column, 3 kanji
     * tall (533 px per character), the ruby's box 0.70x the column and its characters
     * [subCharSizeRatio] x the kanji's.
     */
    private fun verticalRuby(subCharSizeRatio: Double): List<EngineLine> {
        val main = line("堕天使", 2700.0, 3000.0, 3233.0, 4600.0)
        val mainChar = 1600.0 / 3.0
        val rubyHeight = mainChar * subCharSizeRatio * 4.0
        val ruby = line("だてんし", 3233.0, 3000.0, 3233.0 + mainChar * 0.70, 3000.0 + rubyHeight)
        return listOf(main, ruby)
    }

    @Test
    fun `ruby characters beside a vertical kanji column are still dropped`() {
        merge(verticalRuby(0.50)).map { it.lines } shouldBe listOf(listOf("堕天使"))
        merge(verticalRuby(0.75)).map { it.lines } shouldBe listOf(listOf("堕天使"))
    }

    @Test
    fun `text at 85 percent of the annotated character size is kept, not treated as ruby`() {
        // The other side of the discriminator: a narrower box whose characters are nearly the size of
        // the annotated column's is a second column of dialogue, not an annotation.
        merge(verticalRuby(0.85)).map { it.lines } shouldBe listOf(listOf("だてんし", "堕天使"))
    }
}
