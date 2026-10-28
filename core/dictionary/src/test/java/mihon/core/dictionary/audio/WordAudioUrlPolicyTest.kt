// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.audio

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The renderer (and therefore any markup it is tricked into running) can call `WordAudioBridge` with
 * an arbitrary string; only network URLs and the resolver's TTS pseudo-URL may reach the player.
 *
 * Everything the app fetches natively on the page's behalf has to stay on the public internet: a
 * dictionary page must not be able to probe loopback, link-local, private ranges or cloud metadata
 * endpoints (SSRF), nor hide a blocked host behind userinfo or an alternative IP spelling.
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

    @Test
    fun `ordinary public cdns stay playable`() {
        isAllowedWordAudioUrl("https://cdn.jisho.org/audio/1.mp3") shouldBe true
        isAllowedWordAudioUrl("https://d1vjc5dkabm9x6.cloudfront.net/audio.mp3") shouldBe true
        isAllowedWordAudioUrl("http://93.184.216.34/audio.mp3") shouldBe true
        isAllowedWordAudioUrl("https://[2001:4860:4860::8888]/audio.mp3") shouldBe true
        isAllowedWordAudioUrl("https://cdn.jisho.org:8443/audio/1.mp3") shouldBe true
    }

    @Test
    fun `loopback link-local and private hosts are rejected`() {
        // Loopback, in every spelling the URL parser accepts.
        isAllowedWordAudioUrl("http://localhost/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://LOCALHOST:8080/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://localhost./audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://127.0.0.1/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://127.1/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://2130706433/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://0x7f.0.0.1/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://[::1]/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://[0:0:0:0:0:0:0:1]/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://[::ffff:127.0.0.1]/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://[::ffff:7f00:1]/audio.mp3") shouldBe false

        // Unspecified and link-local (cloud metadata lives on 169.254.169.254).
        isAllowedWordAudioUrl("http://0.0.0.0/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://169.254.169.254/latest/meta-data/") shouldBe false
        isAllowedWordAudioUrl("http://[fe80::1]/audio.mp3") shouldBe false

        // RFC 1918, CGNAT, benchmarking, multicast.
        isAllowedWordAudioUrl("http://10.0.0.5/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://172.16.4.4/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://172.31.255.254/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://192.168.0.1/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://100.64.0.1/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://198.18.0.1/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://224.0.0.1/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://[fc00::1]/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://[fd12:3456::1]/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://[ff02::1]/audio.mp3") shouldBe false
    }

    @Test
    fun `internal host names and userinfo masked hosts are rejected`() {
        isAllowedWordAudioUrl("http://foo.local/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://printer.internal/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://nas.home.arpa/audio.mp3") shouldBe false

        // `evil.com@127.0.0.1` reads like evil.com but fetches 127.0.0.1.
        isAllowedWordAudioUrl("http://evil.com@127.0.0.1/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("http://user:pass@example.com/audio.mp3") shouldBe false
        isAllowedWordAudioUrl("https://cdn.jisho.org@169.254.169.254/audio.mp3") shouldBe false
    }
}
