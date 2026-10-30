// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KanaInputConversionTest {

    @Test
    fun `an IME composition is left untouched`() {
        // Defect: rewriting the field on every keystroke cancels the IME's composing region, so a
        // Japanese IME can never build up composition. Nothing may be written back while composing.
        KanaInputConversion.convert(
            newText = "k",
            cursor = 1,
            previousText = "",
            isComposing = true,
        ) shouldBe null
        KanaInputConversion.convert(
            newText = "kyou",
            cursor = 4,
            previousText = "kyo",
            isComposing = true,
        ) shouldBe null
    }

    @Test
    fun `an edit that converts to itself is not written back`() {
        // "k" is an unfinished syllable: toKanaIME returns it unchanged. Writing the value back
        // anyway replaces the TextFieldValue (dropping composition/selection) on every keystroke.
        KanaInputConversion.convert(newText = "k", cursor = 1, previousText = "", isComposing = false) shouldBe null
        KanaInputConversion.convert(newText = "sh", cursor = 2, previousText = "s", isComposing = false) shouldBe null
    }

    @Test
    fun `completed romaji is converted with the adjusted cursor`() {
        KanaInputConversion.convert(newText = "ka", cursor = 2, previousText = "k", isComposing = false) shouldBe
            KanaInputConversion.Converted("か", 1)
        KanaInputConversion.convert(
            newText = "kanji",
            cursor = 5,
            previousText = "kanj",
            isComposing = false,
        ) shouldBe KanaInputConversion.Converted("かんじ", 3)
    }

    @Test
    fun `uppercase romaji still reaches katakana`() {
        KanaInputConversion.convert(newText = "KA", cursor = 2, previousText = "K", isComposing = false) shouldBe
            KanaInputConversion.Converted("カ", 1)
        KanaInputConversion.convert(
            newText = "Kara",
            cursor = 4,
            previousText = "Kar",
            isComposing = false,
        ) shouldBe KanaInputConversion.Converted("から", 2)
    }

    @Test
    fun `romaji committed by an IME is converted once composition ends`() {
        // The commit of a composition can carry the *same* text as the composing value; skipping
        // it would leave the latin text the IME committed unconverted for good.
        KanaInputConversion.convert(newText = "ka", cursor = 2, previousText = "ka", isComposing = false) shouldBe
            KanaInputConversion.Converted("か", 1)
    }

    @Test
    fun `deleting text is never converted`() {
        // A shrinking edit must reach the field verbatim, or backspace fights the converter.
        KanaInputConversion.convert(newText = "k", cursor = 1, previousText = "ka", isComposing = false) shouldBe null
        KanaInputConversion.convert(newText = "", cursor = 0, previousText = "k", isComposing = false) shouldBe null
    }
}
// KUN <--
