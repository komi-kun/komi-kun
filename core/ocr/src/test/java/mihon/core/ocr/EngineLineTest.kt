// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Unit tests for the box normalization / derived IR (contract §5, 03 §3.6).
// Ported from Chimahon (GPL-3.0-or-later) @ f85c063.
// KUN <--
package mihon.core.ocr

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EngineLineTest {

    @Test
    fun `normalized bbox derived geometry`() {
        val box = NormalizedBBox(left = 0.1, top = 0.2, right = 0.5, bottom = 0.6)

        box.width shouldBe (0.4 plusOrMinus 1e-9)
        box.height shouldBe (0.4 plusOrMinus 1e-9)
        box.centerX shouldBe (0.3 plusOrMinus 1e-9)
        box.centerY shouldBe (0.4 plusOrMinus 1e-9)
    }

    @Test
    fun `degenerate box has zero size and a stable center`() {
        val box = NormalizedBBox(left = 0.5, top = 0.5, right = 0.5, bottom = 0.5)

        box.width shouldBe 0.0
        box.height shouldBe 0.0
        box.centerX shouldBe 0.5
        box.centerY shouldBe 0.5
    }

    @Test
    fun `reversed box yields negative size`() {
        val box = NormalizedBBox(left = 0.8, top = 0.8, right = 0.2, bottom = 0.2)

        box.width shouldBe (-0.6 plusOrMinus 1e-9)
        box.height shouldBe (-0.6 plusOrMinus 1e-9)
    }

    @Test
    fun `horizontal line sizes characters by width`() {
        val line = EngineLine(
            text = "あいう",
            bbox = NormalizedBBox(0.1, 0.1, 0.4, 0.2),
            writingDirection = WritingDirection.LTR,
            language = OcrLanguage.JAPANESE,
        )

        line.text shouldBe "あいう"
        line.characterSize shouldBe (0.1 plusOrMinus 1e-9)
        line.hasJpText shouldBe true
        line.hasKanji shouldBe false
        line.rotation shouldBe 0.0
    }

    @Test
    fun `vertical line sizes characters by height`() {
        val line = EngineLine(
            text = "漢字",
            bbox = NormalizedBBox(0.1, 0.1, 0.2, 0.5),
            writingDirection = WritingDirection.TTB,
            language = OcrLanguage.JAPANESE,
        )

        line.characterSize shouldBe (0.2 plusOrMinus 1e-9)
        line.hasJpText shouldBe true
        line.hasKanji shouldBe true
    }

    @Test
    fun `latin-only text is not japanese`() {
        val line = EngineLine(
            text = "hello",
            bbox = NormalizedBBox(0.0, 0.0, 0.5, 0.1),
            writingDirection = WritingDirection.LTR,
            language = OcrLanguage.ENGLISH,
        )

        line.hasJpText shouldBe false
        line.hasKanji shouldBe false
        line.characterSize shouldBe (0.1 plusOrMinus 1e-9)
    }

    @Test
    fun `rotation is taken from the bounding box`() {
        val line = EngineLine(
            text = "あ",
            bbox = NormalizedBBox(0.0, 0.0, 0.1, 0.1, rotation = 0.2),
            writingDirection = WritingDirection.LTR,
            language = OcrLanguage.JAPANESE,
        )

        line.rotation shouldBe 0.2
    }

    @Test
    fun `language flags`() {
        OcrLanguage.JAPANESE.prefersVertical shouldBe true
        OcrLanguage.JAPANESE.isJapanese shouldBe true
        OcrLanguage.ENGLISH.isJapanese shouldBe false
        OcrLanguage.ARABIC.isRtl shouldBe true
        OcrLanguage.ENGLISH.isRtl shouldBe false
        OcrLanguage.CHINESE.prefersNoSpace shouldBe true
    }
}
