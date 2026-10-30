// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Thread-safe, in-memory LRU audio cache for word pronunciation clips.
 * Audio bytes reside entirely in RAM without hitting storage, preventing disk I/O and flash wear.
 */
object AudioCache {

    /** JPod101 returns a 52,288-byte placeholder MP3 when a word has no audio. */
    const val JPOD_DUMMY_AUDIO_BYTES = 52288

    /** Maximum memory allocated to cached audio clips (default 24 MB). */
    const val DEFAULT_MAX_MEMORY_BYTES = 24L * 1024 * 1024

    private val lock = Any()
    private val memoryMap = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = false
    }
    private var currentMemoryBytes: Long = 0L
    private var maxMemoryBytes: Long = DEFAULT_MAX_MEMORY_BYTES

    @Volatile
    private var noRedirectClient: Pair<OkHttpClient, OkHttpClient>? = null

    fun setMaxMemoryBytes(bytes: Long) {
        synchronized(lock) {
            maxMemoryBytes = bytes
            trimToSize(bytes)
        }
    }

    fun sizeBytes(): Long = synchronized(lock) {
        currentMemoryBytes
    }

    fun count(): Int = synchronized(lock) {
        memoryMap.size
    }

    fun clear() {
        synchronized(lock) {
            memoryMap.clear()
            currentMemoryBytes = 0L
        }
    }

    /**
     * Returns the cached in-memory audio byte array if present.
     * Returns null on cache miss or if [url] is blank or synthetic TTS.
     */
    fun get(url: String): ByteArray? = synchronized(lock) {
        if (url.isBlank() || url.startsWith("tts:")) return null
        memoryMap[url]
    }

    /**
     * Stores [data] in the in-memory cache if valid and not the JPod dummy placeholder.
     */
    fun put(url: String, data: ByteArray) {
        synchronized(lock) {
            if (url.isBlank() || url.startsWith("tts:")) return
            if (data.isEmpty() || data.size == JPOD_DUMMY_AUDIO_BYTES) return

            val old = memoryMap.put(url, data)
            currentMemoryBytes += data.size
            if (old != null) {
                currentMemoryBytes -= old.size
            }
            trimToSize(maxMemoryBytes)
        }
    }

    fun remove(url: String): ByteArray? = synchronized(lock) {
        val old = memoryMap.remove(url)
        if (old != null) {
            currentMemoryBytes -= old.size
        }
        old
    }

    private fun trimToSize(targetBytes: Long) {
        val iterator = memoryMap.entries.iterator()
        while (currentMemoryBytes > targetBytes && iterator.hasNext()) {
            val entry = iterator.next()
            currentMemoryBytes -= entry.value.size
            iterator.remove()
        }
    }

    /**
     * Returns cached audio bytes from memory if present, or fetches via [client] on [Dispatchers.IO]
     * directly into memory. Returns null if download fails or content is the dummy placeholder.
     * Zero storage/disk I/O.
     *
     * The fetch is bounded: redirects are not followed (a `302` to a host the
     * [isAllowedWordAudioUrl] fence never saw would otherwise turn this into an open proxy) and a
     * body larger than [MAX_WORD_AUDIO_BYTES] is dropped while streaming, so a hostile response
     * cannot be inflated into RAM before the cache trim runs.
     */
    suspend fun getOrDownload(url: String, client: OkHttpClient): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank() || url.startsWith("tts:")) return@withContext null
        val cached = get(url)
        if (cached != null) return@withContext cached

        try {
            val request = Request.Builder().url(url).build()
            audioHttpClient(client).newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body
                val declaredLength = body.contentLength()
                if (declaredLength == JPOD_DUMMY_AUDIO_BYTES.toLong()) return@withContext null
                if (declaredLength > MAX_WORD_AUDIO_BYTES) return@withContext null

                val bytes = readAudioBodyCapped(body.byteStream()) ?: return@withContext null
                if (bytes.isEmpty() || bytes.size == JPOD_DUMMY_AUDIO_BYTES) {
                    return@withContext null
                }
                put(url, bytes)
                bytes
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Follows HTTP/HTTPS redirects safely up to [maxRedirects] hops, ensuring every redirected URL
     * passes [isAllowedWordAudioUrl] before proceeding. This allows legitimate CDN redirects
     * (e.g. JapanesePod101 assets -> CloudFront/CDN) while preventing SSRF bounces to internal hosts.
     */
    internal class SafeAudioRedirectInterceptor(
        private val maxRedirects: Int = 10,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request()
            var response = chain.proceed(request)
            var redirects = 0
            while (response.isRedirect && redirects < maxRedirects) {
                val location = response.header("Location") ?: break
                val nextHttpUrl = response.request.url.resolve(location) ?: break
                val nextUrl = nextHttpUrl.toString()

                // Reject HTTPS -> HTTP downgrade
                if (request.url.isHttps && !nextHttpUrl.isHttps) {
                    break
                }

                if (!isAllowedWordAudioUrl(nextUrl)) {
                    break
                }
                response.close()
                redirects++

                val requestBuilder = request.newBuilder().url(nextHttpUrl)
                if (request.url.host != nextHttpUrl.host) {
                    requestBuilder.removeHeader("Authorization")
                    requestBuilder.removeHeader("Cookie")
                }
                request = requestBuilder.build()
                response = chain.proceed(request)
            }
            return response
        }
    }

    /**
     * [client] with OkHttp's built-in blind redirect-following switched off, replaced with
     * [SafeAudioRedirectInterceptor] so redirects to legitimate CDNs (e.g. JapanesePod101) are
     * followed while every intermediate/target URL is checked against [isAllowedWordAudioUrl],
     * and with [globalOnlyDns] as its resolver, so an external host name that answers with LAN,
     * link-local, metadata or rebinding addresses (or unexpected loopback) is refused at fetch time
     * rather than merely at parse time. Derived clients share the connection pool and dispatcher of [client]; the
     * derivation is cached per source client.
     */
    internal fun audioHttpClient(client: OkHttpClient): OkHttpClient {
        val cached = noRedirectClient
        if (cached != null && cached.first === client) return cached.second
        val derived = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(SafeAudioRedirectInterceptor())
            .dns(globalOnlyDns(client.dns))
            .build()
        noRedirectClient = client to derived
        return derived
    }

    /**
     * Reads [stream] into memory, returning null as soon as it exceeds [maxBytes] (the stream is
     * abandoned rather than truncated, so no partial clip is ever cached or played).
     */
    internal fun readAudioBodyCapped(
        stream: InputStream,
        maxBytes: Long = MAX_WORD_AUDIO_BYTES,
    ): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) return out.toByteArray()
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
    }

    /** Pre-fetch in the background into memory so future playback is instantaneous. */
    fun prefetch(url: String, client: OkHttpClient, scope: CoroutineScope) {
        if (url.isBlank() || url.startsWith("tts:")) return
        if (get(url) != null) return
        scope.launch(Dispatchers.IO) {
            getOrDownload(url, client)
        }
    }

    /** Clean up any legacy disk cache directory from older builds. */
    fun purgeLegacyDiskCache(cacheDir: File) {
        runCatching {
            val dir = File(cacheDir, "word_audio")
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }
}
// KUN <--
