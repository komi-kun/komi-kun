// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.audio

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

// NOTE ON THE PLATFORM GAP
// ------------------------
// The Android crash that motivated the hand-written scanner in `AudioUrlTemplate` was a
// `PatternSyntaxException` thrown by Android's ICU regex engine for a pattern with a *lone*
// closing brace (`\{([^}]*)}`). The JVM regex engine used by these unit tests silently accepts
// that pattern, so a plain JVM test can never reproduce the original failure. These tests instead
// pin the *behaviour* (and the absence of any regex at all), which is what the scanner must keep
// correct. Only an on-device/emulator run can prove the class initialises under ICU.
class AudioUrlTemplateTest {

    @Test
    fun `substitutes term reading and language`() {
        val out = AudioUrlTemplate.expand(
            "http://127.0.0.1:5050/?term={term}&reading={reading}&lang={language}",
            term = "読む",
            reading = "よむ",
            language = "ja",
        )
        out shouldBe "http://127.0.0.1:5050/?term=読む&reading=よむ&lang=ja"
    }

    @Test
    fun `substitutes the local audio server template`() {
        val out = AudioUrlTemplate.expand(
            "http://127.0.0.1:8765/localaudio/get/?term={term}&reading={reading}",
            term = "食べる",
            reading = "たべる",
            language = "ja",
        )
        out shouldBe "http://127.0.0.1:8765/localaudio/get/?term=食べる&reading=たべる"
    }

    @Test
    fun `leaves unknown placeholders untouched`() {
        AudioUrlTemplate.expand("http://x/{term}/{unknown}", "a", "b", "ja") shouldBe "http://x/a/{unknown}"
    }

    @Test
    fun `preserves unknown placeholders verbatim including braces`() {
        AudioUrlTemplate.expand(
            "{term}-{not-a-key}-{reading}",
            term = "t",
            reading = "r",
            language = "ja",
        ) shouldBe "t-{not-a-key}-r"
    }

    @Test
    fun `substitutes every occurrence`() {
        AudioUrlTemplate.expand(
            "{term}/{term}?r={reading}&r2={reading}&l={language}&l2={language}",
            term = "y",
            reading = "r",
            language = "ja",
        ) shouldBe "y/y?r=r&r2=r&l=ja&l2=ja"
    }

    @Test
    fun `handles a template with no placeholders`() {
        AudioUrlTemplate.expand("http://x/static.mp3", "a", "b", "ja") shouldBe "http://x/static.mp3"
    }

    @Test
    fun `empty values collapse to nothing`() {
        AudioUrlTemplate.expand("http://x/?a={term}&b={reading}", "", "", "ja") shouldBe "http://x/?a=&b="
    }

    @Test
    fun `empty placeholder is treated as unknown and preserved`() {
        AudioUrlTemplate.expand("http://x/{}", "a", "b", "ja") shouldBe "http://x/{}"
    }

    @Test
    fun `braces in the surrounding url are preserved`() {
        // Braces that are not part of a placeholder must not be mangled.
        AudioUrlTemplate.expand("http://x/{term}?raw={a}{b}", "t", "r", "ja") shouldBe "http://x/t?raw={a}{b}"
    }

    @Test
    fun `a stray opening brace is preserved`() {
        AudioUrlTemplate.expand("http://x/{term}/{unclosed", "t", "r", "ja") shouldBe "http://x/t/{unclosed"
    }

    @Test
    fun `a stray closing brace is preserved`() {
        AudioUrlTemplate.expand("http://x/a}b/{term}", "t", "r", "ja") shouldBe "http://x/a}b/t"
    }

    @Test
    fun `substituted values containing braces are not re-scanned`() {
        // The replacement is inserted literally; a value that itself looks like a placeholder is
        // never substituted again (same as Yomitan's single-pass replacer).
        AudioUrlTemplate.expand("{term}", term = "{reading}", reading = "r", language = "ja") shouldBe "{reading}"
    }
}
// KUN <--
