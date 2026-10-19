// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

object AudioCache {

    /** JPod101 returns a 52,288-byte placeholder MP3 when a word has no audio. */
    const val JPOD_DUMMY_AUDIO_BYTES = 52288L

    private const val MAX_CACHE_SIZE_BYTES = 50L * 1024 * 1024 // 50 MB
    private const val PRUNE_TARGET_BYTES = 40L * 1024 * 1024 // 40 MB

    fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, "word_audio")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun sha256(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Returns the cached local audio file if it exists, is non-empty, and is not a known
     * dummy placeholder. Returns null on cache miss.
     */
    fun getCachedFile(context: Context?, url: String): File? {
        if (context == null || url.isBlank() || url.startsWith("tts:")) return null
        val cacheDir = getCacheDir(context)
        val file = File(cacheDir, "${sha256(url)}.mp3")
        return if (file.exists() && file.length() > 0 && file.length() != JPOD_DUMMY_AUDIO_BYTES) {
            file.setLastModified(System.currentTimeMillis())
            file
        } else {
            null
        }
    }

    /**
     * Returns the cached file if present, or downloads it via [client] on [Dispatchers.IO].
     * Returns null if download fails or the content is the dummy placeholder.
     */
    suspend fun getOrDownload(context: Context?, url: String, client: OkHttpClient): File? = withContext(Dispatchers.IO) {
        if (context == null || url.isBlank() || url.startsWith("tts:")) return@withContext null
        val cached = getCachedFile(context, url)
        if (cached != null) return@withContext cached

        val cacheDir = getCacheDir(context)
        val hash = sha256(url)
        val targetFile = File(cacheDir, "$hash.mp3")
        val tempFile = File(cacheDir, "$hash.tmp")

        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body
                val declaredLength = body.contentLength()
                if (declaredLength == JPOD_DUMMY_AUDIO_BYTES) return@withContext null

                tempFile.outputStream().use { out ->
                    body.byteStream().copyTo(out)
                }

                if (tempFile.length() == 0L || tempFile.length() == JPOD_DUMMY_AUDIO_BYTES) {
                    tempFile.delete()
                    return@withContext null
                }

                if (tempFile.renameTo(targetFile)) {
                    pruneIfNeeded(cacheDir)
                    targetFile
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            runCatching { tempFile.delete() }
            null
        }
    }

    /** Pre-download in the background so future playback hits the local disk cache. */
    fun prefetch(context: Context?, url: String, client: OkHttpClient, scope: CoroutineScope) {
        if (context == null || url.isBlank() || url.startsWith("tts:")) return
        if (getCachedFile(context, url) != null) return
        scope.launch(Dispatchers.IO) {
            getOrDownload(context, url, client)
        }
    }

    internal fun pruneIfNeeded(
        cacheDir: File,
        maxBytes: Long = MAX_CACHE_SIZE_BYTES,
        targetBytes: Long = PRUNE_TARGET_BYTES,
    ) {
        val files = cacheDir.listFiles() ?: return
        var totalSize = files.sumOf { it.length() }
        if (totalSize > maxBytes) {
            val sorted = files.sortedBy { it.lastModified() }
            for (f in sorted) {
                if (totalSize <= targetBytes) break
                val len = f.length()
                if (f.delete()) {
                    totalSize -= len
                }
            }
        }
    }
}
// KUN <--
