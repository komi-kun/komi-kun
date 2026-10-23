// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

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
     */
    suspend fun getOrDownload(url: String, client: OkHttpClient): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank() || url.startsWith("tts:")) return@withContext null
        val cached = get(url)
        if (cached != null) return@withContext cached

        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body
                val declaredLength = body.contentLength()
                if (declaredLength == JPOD_DUMMY_AUDIO_BYTES.toLong()) return@withContext null

                val bytes = body.bytes()
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
