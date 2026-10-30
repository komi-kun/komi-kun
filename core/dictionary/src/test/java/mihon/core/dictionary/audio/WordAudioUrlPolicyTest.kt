// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.audio

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import okhttp3.Dns
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.UnknownHostException

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

    /**
     * The DNS half of the fence. A name passes the string check (nothing about `http://evil.example`
     * is internal), so the address is what has to be judged — at fetch time, on the lookup the
     * connection actually uses, or a rebinding name (public when checked, loopback when connected)
     * reaches the app's own network stack.
     */
    @Test
    fun `the fetch fence refuses a name that resolves to loopback, metadata or a LAN address`() {
        // Loopback in both families, and the metadata endpoint rebinding is normally aimed at.
        shouldThrow<UnknownHostException> { dnsResolvingTo("127.0.0.1").lookup("evil.example") }
        shouldThrow<UnknownHostException> { dnsResolvingTo("::1").lookup("evil.example") }
        shouldThrow<UnknownHostException> { dnsResolvingTo("169.254.169.254").lookup("evil.example") }
        // RFC 1918, CGNAT and IPv6 unique-local.
        shouldThrow<UnknownHostException> { dnsResolvingTo("10.0.0.5").lookup("evil.example") }
        shouldThrow<UnknownHostException> { dnsResolvingTo("192.168.1.1").lookup("evil.example") }
        shouldThrow<UnknownHostException> { dnsResolvingTo("100.64.0.1").lookup("evil.example") }
        shouldThrow<UnknownHostException> { dnsResolvingTo("fd12:3456::1").lookup("evil.example") }
        // An IPv4-mapped internal address is the same address.
        shouldThrow<UnknownHostException> { dnsResolvingTo("::ffff:127.0.0.1").lookup("evil.example") }
    }

    /** A rebinding answer: one public address is not enough to make the whole answer public. */
    @Test
    fun `a mixed answer is refused even when it also holds a public address`() {
        shouldThrow<UnknownHostException> {
            dnsResolvingTo("93.184.216.34", "127.0.0.1").lookup("rebind.example")
        }
    }

    @Test
    fun `a public name proceeds and keeps every address`() {
        dnsResolvingTo("93.184.216.34", "2001:4860:4860::8888").lookup("cdn.jisho.org") shouldBe
            listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("2001:4860:4860::8888"))
    }

    /** A resolver stub: the classification of the *answer* is what is under test, not DNS itself. */
    private fun dnsResolvingTo(vararg literals: String): Dns = globalOnlyDns(
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = literals.map { InetAddress.getByName(it) }
        },
    )
}
