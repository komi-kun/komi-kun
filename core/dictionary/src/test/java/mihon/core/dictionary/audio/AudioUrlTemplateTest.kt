// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.audio

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

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
    fun `leaves unknown placeholders untouched`() {
        AudioUrlTemplate.expand("http://x/{term}/{unknown}", "a", "b", "ja") shouldBe "http://x/a/{unknown}"
    }

    @Test
    fun `handles a template with no placeholders`() {
        AudioUrlTemplate.expand("http://x/static.mp3", "a", "b", "ja") shouldBe "http://x/static.mp3"
    }
}
// KUN <--
