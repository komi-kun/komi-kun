// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Unit tests for lookup-text extraction and language gating (contract §5, 03 §3.6).
// Ported from Chimahon (GPL-3.0-or-later) @ f85c063; the Anki constants were inlined.
// KUN <--
package mihon.core.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OcrLookupTextTest {

    @Test
    fun `lookup start chars`() {
        isOcrLookupStartChar('あ') shouldBe true
        isOcrLookupStartChar('漢') shouldBe true
        isOcrLookupStartChar('a') shouldBe true
        isOcrLookupStartChar('5') shouldBe true
        isOcrLookupStartChar(' ') shouldBe false
        isOcrLookupStartChar('.') shouldBe false
        isOcrLookupStartChar('、') shouldBe false
        isOcrLookupStartChar('!') shouldBe false
        isOcrLookupStartChar('-') shouldBe false
    }

    @Test
    fun `extract stops at punctuation`() {
        extractOcrLookupText("これは本です。", 0) shouldBe "これは本です"
    }

    @Test
    fun `extract clamps the start index`() {
        extractOcrLookupText("abc", 99) shouldBe ""
        extractOcrLookupText("abc", -5) shouldBe "abc"
    }

    // KUN -->
    @Test
    fun `extract advances past leading punctuation`() {
        extractOcrLookupText("…はい", 0) shouldBe "はい"
        extractOcrLookupText("⋯はい", 0) shouldBe "はい"
        extractOcrLookupText("「剣聖」", 0) shouldBe "剣聖"
    }
    // KUN <--

    @Test
    fun `extract from mid-text`() {
        extractOcrLookupText("tabc def", 1) shouldBe "abc"
    }

    @Test
    fun `normalize language code`() {
        normalizeOcrLanguageCode("es-419") shouldBe "es"
        normalizeOcrLanguageCode("zh_Hant") shouldBe "zh"
        normalizeOcrLanguageCode("JA") shouldBe "ja"
    }

    @Test
    fun `whole-word scan by language`() {
        isLanguageWholeWordScan("en") shouldBe true
        isLanguageWholeWordScan("ja") shouldBe false
        isLanguageWholeWordScan("zh-Hans") shouldBe false
        isLanguageWholeWordScan("ko") shouldBe false
        isLanguageWholeWordScan("all") shouldBe false
        isLanguageWholeWordScan("") shouldBe false
        isLanguageWholeWordScan(null) shouldBe false
    }

    @Test
    fun `scan resolution with explicit value`() {
        shouldScanWholeWord("word", "ja") shouldBe true
        shouldScanWholeWord("character", "en") shouldBe false
    }

    @Test
    fun `scan resolution falls back to the language`() {
        shouldScanWholeWord("auto", "en") shouldBe true
        shouldScanWholeWord("", "ja") shouldBe false
    }

    @Test
    fun `effective scan and search resolutions`() {
        effectiveScanResolution("word", "ja") shouldBe "word"
        effectiveScanResolution("", "en") shouldBe "word"
        effectiveScanResolution("", "ja") shouldBe "character"
        effectiveSearchResolution("", "en") shouldBe "word"
        effectiveSearchResolution("", "ja") shouldBe "letter"
        effectiveSearchResolution("letter", "en") shouldBe "letter"
    }

    @Test
    fun `language match gating`() {
        isOcrAllowedForLanguage("ja", "ja") shouldBe true
        isOcrAllowedForLanguage("ja", "ja-JP") shouldBe true
        isOcrAllowedForLanguage("ja", "en") shouldBe false
        isOcrAllowedForLanguage("all", "en") shouldBe true
        isOcrAllowedForLanguage("ja", "all") shouldBe true
        isOcrAllowedForLanguage("", "en") shouldBe true
    }

    @Test
    fun `whole word expansion`() {
        extractWholeWord("hello world", 2, 0, 11) shouldBe "hello"
        extractWholeWord("hello world", 8, 0, 11) shouldBe "world"
    }

    @Test
    fun `whole word expansion is clamped to the line`() {
        // Line 2 of "abcdefghij" starts at 5; a tap at 6 must not spill into line 1.
        extractWholeWord("abcd efghij", 6, 5, 11) shouldBe "efghij"
    }

    @Test
    fun `next word boundary substring`() {
        nextWordBoundarySubstring("hello world") shouldBe "hello"
        nextWordBoundarySubstring("hello") shouldBe ""
        nextWordBoundarySubstring("") shouldBe ""
    }
}
