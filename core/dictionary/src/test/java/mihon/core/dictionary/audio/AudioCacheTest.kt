// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.audio

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

class AudioCacheTest {

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        AudioCache.clear()
        // A test that aborts mid-download must not leak its gate (or its join hook) into the next.
        AudioCache.resetInFlightForTest()
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
     * The fetch client allows localhost for local audio servers (AnkiConnect Android), but refuses
     * any external hostname rebinding to loopback.
     */
    @Test
    fun `audio fetch client permits localhost and refuses external hostname resolving to loopback`() {
        val rebindingDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        }
        val audioClient = AudioCache.audioHttpClient(OkHttpClient.Builder().dns(rebindingDns).build())

        audioClient.dns.lookup("localhost") shouldBe listOf(InetAddress.getByName("127.0.0.1"))
        shouldThrow<UnknownHostException> { audioClient.dns.lookup("evil.example") }
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
    fun `safe redirect interceptor refuses https to http downgrade`() {
        val interceptor = AudioCache.SafeAudioRedirectInterceptor()
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                val req = chain.request()
                when (req.url.host) {
                    "secure.example" -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(302)
                        .message("Found")
                        .header("Location", "http://insecure.example/audio.mp3")
                        .body("".toResponseBody(null))
                        .build()
                    else -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("insecure_content".toResponseBody(null))
                        .build()
                }
            }
            .build()

        val resp = client.newCall(Request.Builder().url("https://secure.example/audio.mp3").build()).execute()
        resp.code shouldBe 302
        resp.header("Location") shouldBe "http://insecure.example/audio.mp3"
    }

    @Test
    fun `safe redirect interceptor strips sensitive headers when redirecting across hosts`() {
        val interceptor = AudioCache.SafeAudioRedirectInterceptor()
        var redirectedAuthHeader: String? = "initial"
        var redirectedCookieHeader: String? = "initial"
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                val req = chain.request()
                when (req.url.host) {
                    "origin.example" -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(302)
                        .message("Found")
                        .header("Location", "https://cdn.example/audio.mp3")
                        .body("".toResponseBody(null))
                        .build()
                    "cdn.example" -> {
                        redirectedAuthHeader = req.header("Authorization")
                        redirectedCookieHeader = req.header("Cookie")
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("content".toResponseBody(null))
                            .build()
                    }
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

        val request = Request.Builder()
            .url("https://origin.example/audio.mp3")
            .header("Authorization", "Bearer token123")
            .header("Cookie", "session=abc")
            .build()
        val resp = client.newCall(request).execute()
        resp.isSuccessful shouldBe true
        redirectedAuthHeader shouldBe null
        redirectedCookieHeader shouldBe null
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

    // --- in-flight dedupe (a play tap must not re-GET a clip that is already downloading) ------

    /**
     * A client with one canned response that counts the requests that reach it. [onFirstRequest]
     * runs at the start of the first request, while it is still open — the only place a concurrent
     * second caller can be started from deterministically, without a timing fence.
     */
    private fun countingClient(
        bytes: ByteArray,
        requests: AtomicInteger,
        onFirstRequest: ((OkHttpClient) -> Unit)? = null,
    ): OkHttpClient {
        var fired = false
        lateinit var client: OkHttpClient
        client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests.incrementAndGet()
                if (!fired) {
                    fired = true
                    onFirstRequest?.invoke(client)
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bytes.toResponseBody(null))
                    .build()
            }
            .build()
        return client
    }

    @Test
    fun `two concurrent downloads of one url issue a single request`() {
        runBlocking {
            val url = "https://example.com/audio.mp3"
            val bytes = ByteArray(512) { 7 }
            val requests = AtomicInteger()
            val leaderEntered = CompletableDeferred<Unit>()
            val releaseLeader = CompletableDeferred<Unit>()
            lateinit var client: OkHttpClient
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requests.incrementAndGet()
                    leaderEntered.complete(Unit)
                    // Hold the response open until the concurrent caller has been started, so that
                    // it provably runs while this download is still in flight.
                    runBlocking { withTimeout(5_000) { releaseLeader.await() } }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(bytes.toResponseBody(null))
                        .build()
                }
                .build()

            val joined = CompletableDeferred<Unit>()
            AudioCache.onJoinedInFlight = { joined.complete(Unit) }

            val leader = async(Dispatchers.IO) { AudioCache.getOrDownload(url, client) }
            withTimeout(5_000) { leaderEntered.await() }
            val follower = async(Dispatchers.IO) { AudioCache.getOrDownload(url, client) }
            // The join latch proves the follower entered the dedupe path while the leader's request
            // was still open — no sleep, and without dedupe it never fires and the count below is 2.
            withTimeout(5_000) { joined.await() }
            releaseLeader.complete(Unit)

            leader.await() shouldBe bytes
            follower.await() shouldBe bytes
            requests.get() shouldBe 1
        }
    }

    @Test
    fun `a prefetch and a play tap of the same url share one request`() = runBlocking {
        val url = "https://example.com/prefetched.mp3"
        val bytes = ByteArray(256) { 3 }
        val requests = AtomicInteger()
        val played = CompletableDeferred<ByteArray?>()
        val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = countingClient(bytes, requests) { built ->
            // `prefetch` funnels through `getOrDownload`, so a tap landing during the prefetch's own
            // GET must join that download instead of starting another.
            prefetchScope.launch { played.complete(AudioCache.getOrDownload(url, built)) }
        }

        AudioCache.prefetch(url, client, prefetchScope)

        withTimeout(5_000) { played.await() } shouldBe bytes
        requests.get() shouldBe 1
        AudioCache.get(url) shouldBe bytes
        prefetchScope.cancel()
    }

    @Test
    fun `concurrent downloads of different urls each issue their own request`() {
        runBlocking {
            val first = "https://example.com/one.mp3"
            val second = "https://example.com/two.mp3"
            val firstBytes = ByteArray(64) { 1 }
            val secondBytes = ByteArray(64) { 2 }
            val requests = AtomicInteger()
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requests.incrementAndGet()
                    val body = if (chain.request().url.toString() == first) firstBytes else secondBytes
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(null))
                        .build()
                }
                .build()

            // A single global gate would let one of these return the other's bytes (or skip a GET).
            val one = async(Dispatchers.IO) { AudioCache.getOrDownload(first, client) }
            val two = async(Dispatchers.IO) { AudioCache.getOrDownload(second, client) }

            one.await() shouldBe firstBytes
            two.await() shouldBe secondBytes
            requests.get() shouldBe 2
        }
    }

    @Test
    fun `a failed download removes the in-flight gate so the next attempt retries`() {
        runBlocking {
            val url = "https://example.com/fails-once.mp3"
            val bytes = ByteArray(128) { 9 }
            val attempts = AtomicInteger()
            lateinit var client: OkHttpClient
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val attempt = attempts.incrementAndGet()
                    val code = if (attempt == 1) 404 else 200
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message(if (code == 200) "OK" else "Not Found")
                        .body(if (code == 200) bytes.toResponseBody(null) else "".toResponseBody(null))
                        .build()
                }
                .build()

            AudioCache.getOrDownload(url, client) shouldBe null
            // The gate must not outlive the failed request: it would otherwise hand every later
            // caller the same "no bytes" answer without ever retrying. The download coroutine
            // removes it just *after* completing the gate, so wait for that instead of assuming it
            // already happened — the retry below is the real assertion.
            awaitNoInFlightDownloads()

            AudioCache.getOrDownload(url, client) shouldBe bytes
            attempts.get() shouldBe 2
            awaitNoInFlightDownloads()
        }
    }

    /** Bounded poll for the gate removal that the download coroutine performs after completion. */
    private suspend fun awaitNoInFlightDownloads() {
        withTimeout(5_000) {
            while (AudioCache.inFlightDownloadCount() != 0) delay(10)
        }
    }

    @Test
    fun `a cancelled waiter does not release the in-flight gate`() {
        runBlocking {
            val url = "https://example.com/cancelled-waiter.mp3"
            val bytes = ByteArray(64) { 5 }
            val requests = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            lateinit var client: OkHttpClient
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requests.incrementAndGet()
                    entered.complete(Unit)
                    runBlocking { withTimeout(5_000) { release.await() } }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(bytes.toResponseBody(null))
                        .build()
                }
                .build()

            // The previous playback job is cancelled on every tap; that caller must not take the
            // gate down with it while the fetch is still running.
            val waiter = async(Dispatchers.IO) { AudioCache.getOrDownload(url, client) }
            withTimeout(5_000) { entered.await() }
            waiter.cancelAndJoin()
            AudioCache.inFlightDownloadCount() shouldBe 1

            val second = async(Dispatchers.IO) { AudioCache.getOrDownload(url, client) }
            release.complete(Unit)

            withTimeout(5_000) { second.await() } shouldBe bytes
            requests.get() shouldBe 1
            awaitNoInFlightDownloads()
        }
    }
}
// KUN <--
