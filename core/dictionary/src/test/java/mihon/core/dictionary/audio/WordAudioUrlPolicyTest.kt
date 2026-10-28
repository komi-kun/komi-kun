// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.audio

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The renderer (and therefore any markup it is tricked into running) can call `WordAudioBridge` with
 * an arbitrary string; only network URLs and the resolver's TTS pseudo-URL may reach the player.
 */
class WordAudioUrlPolicyTest {

    @Test
    fun `network and resolver urls are accepted`() {
        isAllowedWordAudioUrl("https://example.com/a.mp3") shouldBe true
        isAllowedWordAudioUrl("HTTP://example.com/a.mp3") shouldBe true
        isAllowedWordAudioUrl("tts://speak?text=%E4%B8%80&lang=ja") shouldBe true
    }

    @Test
    fun `every other scheme is rejected`() {
        isAllowedWordAudioUrl("javascript:alert(1)") shouldBe false
        isAllowedWordAudioUrl("file:///data/data/app.komikun/shared_prefs/prefs.xml") shouldBe false
        isAllowedWordAudioUrl("content://com.example.provider/audio") shouldBe false
        isAllowedWordAudioUrl("data:audio/mpeg;base64,AAAA") shouldBe false
        isAllowedWordAudioUrl("//example.com/a.mp3") shouldBe false
        isAllowedWordAudioUrl("example.com/a.mp3") shouldBe false
        isAllowedWordAudioUrl("https:example.com") shouldBe false
        isAllowedWordAudioUrl("") shouldBe false
    }
}
