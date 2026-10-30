// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.audio

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.UnknownHostException

class AudioCacheTest {

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        AudioCache.clear()
        AudioCache.setMaxMemoryBytes(AudioCache.DEFAULT_MAX_MEMORY_BYTES)
    }

    @Test
    fun `get returns null for blank or tts url`() {
        AudioCache.get("") shouldBe null
        AudioCache.get("   ") shouldBe null
        AudioCache.get("tts:something") shouldBe null
        AudioCache.get("tts://speak?text=hi") shouldBe null
    }

    @Test
    fun `get returns null on cache miss`() {
        AudioCache.get("https://example.com/audio.mp3") shouldBe null
    }

    @Test
    fun `put and get stores and retrieves bytes in memory`() {
        val url = "https://example.com/audio.mp3"
        val bytes = ByteArray(1024) { it.toByte() }

        AudioCache.put(url, bytes)

        val cached = AudioCache.get(url)
        cached shouldNotBe null
        cached shouldBe bytes
        AudioCache.sizeBytes() shouldBe 1024L
        AudioCache.count() shouldBe 1
    }

    @Test
    fun `put rejects jpod dummy audio`() {
        val url = "https://example.com/dummy.mp3"
        val dummyBytes = ByteArray(AudioCache.JPOD_DUMMY_AUDIO_BYTES) { 0 }

        AudioCache.put(url, dummyBytes)

        AudioCache.get(url) shouldBe null
        AudioCache.count() shouldBe 0
    }

    @Test
    fun `put rejects empty byte array`() {
        val url = "https://example.com/empty.mp3"
        AudioCache.put(url, byteArrayOf())

        AudioCache.get(url) shouldBe null
        AudioCache.count() shouldBe 0
    }

    @Test
    fun `LRU eviction drops oldest unaccessed entry when exceeding max memory`() {
        AudioCache.setMaxMemoryBytes(250L)

        val bytes1 = ByteArray(100) { 1 }
        val bytes2 = ByteArray(100) { 2 }
        val bytes3 = ByteArray(100) { 3 }

        AudioCache.put("url1", bytes1)
        AudioCache.put("url2", bytes2)
        AudioCache.put("url3", bytes3)

        // 300 bytes total > 250 bytes max -> url1 (oldest) should be evicted
        AudioCache.get("url1") shouldBe null
        AudioCache.get("url2") shouldBe bytes2
        AudioCache.get("url3") shouldBe bytes3
        AudioCache.sizeBytes() shouldBe 200L
        AudioCache.count() shouldBe 2
    }

    @Test
    fun `accessing an entry refreshes its LRU position`() {
        AudioCache.setMaxMemoryBytes(250L)

        val bytes1 = ByteArray(100) { 1 }
        val bytes2 = ByteArray(100) { 2 }
        val bytes3 = ByteArray(100) { 3 }

        AudioCache.put("url1", bytes1)
        AudioCache.put("url2", bytes2)

        // Access url1 so it becomes newer than url2
        AudioCache.get("url1") shouldBe bytes1

        // Add url3 -> total 300 > 250 -> url2 (least recently used) should be evicted!
        AudioCache.put("url3", bytes3)

        AudioCache.get("url1") shouldBe bytes1
        AudioCache.get("url2") shouldBe null
        AudioCache.get("url3") shouldBe bytes3
    }

    @Test
    fun `purgeLegacyDiskCache cleans up old word_audio directory`() {
        val legacyDir = File(tempDir, "word_audio").apply { mkdirs() }
        val dummyFile = File(legacyDir, "old.mp3").apply { writeText("old") }

        legacyDir.exists() shouldBe true
        dummyFile.exists() shouldBe true

        AudioCache.purgeLegacyDiskCache(tempDir)

        legacyDir.exists() shouldBe false
    }

    @Test
    fun `audio fetch client does not follow redirects`() {
        val base = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val audioClient = AudioCache.audioHttpClient(base)

        // A 302 must surface as a failed fetch: following it would let a page-reachable host bounce
        // the request to a host the WordAudioBridge fence never approved.
        audioClient.followRedirects shouldBe false
        audioClient.followSslRedirects shouldBe false
        // The source client is untouched (it also serves the resolver's config requests).
        base.followRedirects shouldBe true
        // Derivation is cached, so repeated downloads do not rebuild a client per call.
        (AudioCache.audioHttpClient(base) === audioClient) shouldBe true
    }

    /**
     * The textual URL fence cannot know where a *name* points, so the fetch resolves through its own
     * fence. `localhost` is the one name every JVM resolves without a network, and the system
     * resolver answers it with loopback — a client whose DNS is not the fence would return it.
     */
    @Test
    fun `audio fetch client refuses a name that resolves to loopback`() {
        val audioClient = AudioCache.audioHttpClient(OkHttpClient())

        shouldThrow<UnknownHostException> { audioClient.dns.lookup("localhost") }
    }

    @Test
    fun `safe redirect interceptor follows redirect to allowed public url`() {
        val interceptor = AudioCache.SafeAudioRedirectInterceptor()
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                val req = chain.request()
                when (req.url.host) {
                    "assets.languagepod101.com" -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(301)
                        .message("Moved Permanently")
                        .header("Location", "https://cdn.innovativelanguage.com/audio.mp3")
                        .body("".toResponseBody(null))
                        .build()
                    "cdn.innovativelanguage.com" -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("audio_bytes".toResponseBody(null))
                        .build()
                    else -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("".toResponseBody(null))
                        .build()
                }
            }
            .build()

        val resp = client.newCall(Request.Builder().url("https://assets.languagepod101.com/dict.php").build()).execute()
        resp.isSuccessful shouldBe true
        resp.code shouldBe 200
        resp.body.string() shouldBe "audio_bytes"
    }

    @Test
    fun `safe redirect interceptor blocks redirect to loopback or private url`() {
        val interceptor = AudioCache.SafeAudioRedirectInterceptor()
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                val req = chain.request()
                when (req.url.host) {
                    "evil.example" -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(302)
                        .message("Found")
                        .header("Location", "http://127.0.0.1/audio.mp3")
                        .body("".toResponseBody(null))
                        .build()
                    else -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("internal".toResponseBody(null))
                        .build()
                }
            }
            .build()

        val resp = client.newCall(Request.Builder().url("https://evil.example/audio.mp3").build()).execute()
        resp.code shouldBe 302
        resp.header("Location") shouldBe "http://127.0.0.1/audio.mp3"
    }

    @Test
    fun `readAudioBodyCapped returns the body up to the cap`() {
        val bytes = ByteArray(2048) { it.toByte() }

        AudioCache.readAudioBodyCapped(bytes.inputStream(), maxBytes = 2048L) shouldBe bytes
    }

    @Test
    fun `readAudioBodyCapped rejects a body one byte over the cap`() {
        val bytes = ByteArray(2049) { it.toByte() }

        AudioCache.readAudioBodyCapped(bytes.inputStream(), maxBytes = 2048L) shouldBe null
        // The real cap is a bounded few MB, not unbounded growth.
        MAX_WORD_AUDIO_BYTES shouldBe 4L * 1024 * 1024

        val huge = ByteArray((MAX_WORD_AUDIO_BYTES + 1).toInt()) { 1 }
        AudioCache.readAudioBodyCapped(huge.inputStream()) shouldBe null
    }
}
// KUN <--
