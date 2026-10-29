// SPDX-License-Identifier: Apache-2.0
package tachiyomi.core.common.mokuro

import android.content.Context
import logcat.LogPriority
import okhttp3.Interceptor
import okhttp3.Response
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.security.MessageDigest

/**
 * Caches `.mokuro` HTTP responses on disk, keyed by a hash of the request URL, so the reader can use
 * a source-provided OCR sidecar without downloading it again.
 *
 * This is a deliberately generic, Apache-2.0 network cache: it knows nothing about OCR engines,
 * `.mokuro` parsing, or any source. It only recognises the `.mokuro` extension, lets the response
 * through untouched, and stores a copy under `{filesDir}/mokuro_ocr/{sha256(url)}.json`. The GPL
 * `.mokuro` parser/resolver lives in `:core:ocr`; only the URL→cache-file mapping is shared here.
 *
 * **Inert while the immersion feature is off.** [isEnabled] is the combined dictionary/OCR opt-in
 * (`DictionaryOcrPreferences.isEnabled`) and is consulted per request, not in the constructor, so
 * turning the feature on later needs no process restart. While it is off no `.mokuro` body is
 * buffered or written, and the cache directory is never created (it is created lazily, on the first
 * `.mokuro` response that is actually cached) — a user who never opted in gets no `mokuro_ocr/`
 * directory and nothing buffers a sidecar body. Existing cached files are left alone when the
 * switch goes off: the cache belongs to the user.
 */
class MokuroOcrInterceptor(
    context: Context,
    private val isEnabled: () -> Boolean,
) : Interceptor {

    // Lazy, so construction (which happens for every process, feature on or off) never creates
    // `{filesDir}/mokuro_ocr/`.
    private val cacheDir: File by lazy { cacheDir(context) }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isMokuroRequest(request.url.encodedPath)) {
            return chain.proceed(request)
        }
        // Only `.mokuro` traffic pays for the preference read.
        if (!isEnabled()) {
            return chain.proceed(request)
        }

        val response = chain.proceed(request)
        if (!response.isSuccessful) {
            return response
        }

        try {
            val body = response.peekBody(MAX_BODY_BYTES).bytes()
            if (body.isNotEmpty()) {
                val target = File(cacheDir, "${request.url.toString().sha256()}$CACHE_FILE_SUFFIX")
                target.writeBytes(body)
                logcat { "MokuroOcrInterceptor: cached .mokuro for ${request.url}" }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "MokuroOcrInterceptor: failed to cache .mokuro for ${request.url}" }
        }

        return response
    }

    companion object {
        /** Upper bound on the sidecar size we are willing to buffer while peeking. */
        const val MAX_BODY_BYTES: Long = 64L * 1024 * 1024

        private const val CACHE_DIR_NAME = "mokuro_ocr"
        private const val CACHE_FILE_SUFFIX = ".json"

        /** True when [path] names a `.mokuro` sidecar (query strings already stripped by the caller). */
        fun isMokuroRequest(path: String): Boolean = path.endsWith(".mokuro", ignoreCase = true)

        /** The directory that holds every cached sidecar. */
        fun cacheDir(context: Context): File = File(context.filesDir, CACHE_DIR_NAME).apply { mkdirs() }

        /** The cached sidecar for [url], or null when it has not been fetched yet. */
        fun cacheFile(context: Context, url: String): File? {
            val file = File(cacheDir(context), "${url.sha256()}$CACHE_FILE_SUFFIX")
            return file.takeIf { it.isFile }
        }

        private fun String.sha256(): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
            return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
