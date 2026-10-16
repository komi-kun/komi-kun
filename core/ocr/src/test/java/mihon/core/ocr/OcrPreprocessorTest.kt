// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Unit tests for the Chimahon-derived OCR pipeline (contract §5, 03 §3.6).
// Most of this logic is ported from Chimahon (GPL-3.0-or-later) @ f85c063.
// KUN <--
package mihon.core.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OcrPreprocessorTest {

    @Test
    fun `collapses exclamation runs`() {
        OcrPreprocessor.clean("すごい!!!") shouldBe "すごい!"
    }

    @Test
    fun `collapses question runs`() {
        OcrPreprocessor.clean("ほんと???") shouldBe "ほんと?"
    }

    @Test
    fun `collapses dot runs to an ellipsis`() {
        OcrPreprocessor.clean("え...") shouldBe "え…"
    }

    @Test
    fun `collapses mixed question and exclamation to question`() {
        OcrPreprocessor.clean("なに?!") shouldBe "なに?"
    }

    @Test
    fun `replaces katakana middle dots`() {
        OcrPreprocessor.clean("あ･･･") shouldBe "あ…"
    }

    @Test
    fun `leaves a single punctuation mark untouched`() {
        OcrPreprocessor.clean("あ!") shouldBe "あ!"
    }

    @Test
    fun `leaves plain text untouched`() {
        OcrPreprocessor.clean("hello 世界") shouldBe "hello 世界"
    }

    @Test
    fun `handles null and blank input`() {
        OcrPreprocessor.clean(null) shouldBe ""
        OcrPreprocessor.clean("") shouldBe ""
    }
}
