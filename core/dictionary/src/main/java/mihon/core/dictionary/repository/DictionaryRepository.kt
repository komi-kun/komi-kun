// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.repository

import android.content.Context
import android.util.Base64
import android.util.Log
import mihon.core.dictionary.engine.DictionaryEngine
import mihon.core.dictionary.engine.DictionarySession
import mihon.core.dictionary.engine.ImportProgress
import mihon.core.dictionary.model.DictionaryPaths
import mihon.core.dictionary.model.DictionaryStyle
import mihon.core.dictionary.model.EntryTypeCounts
import mihon.core.dictionary.model.ImportResult
import mihon.core.dictionary.model.KanjiResult
import mihon.core.dictionary.model.LookupResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap

/**
 * The single owner of the native [DictionarySession] (02 §8.1: app code goes through this
 * repository, never the engine facade directly).
 *
 * The session is opened lazily and rebuilt only when the dictionary set changes, so
 * [warmUp] is cheap to call on every reader/tab open. Provided by the app's
 * `DictionaryHostBindings` (contract §3.6c).
 */
class DictionaryRepository(
    private val engine: DictionaryEngine,
    private val context: Context,
) {
    private var session: DictionarySession? = null
    private var configuredPaths: DictionaryPaths = DictionaryPaths()
    private var cachedStyles: List<DictionaryStyle> = emptyList()
    private var lastWarmedProfileId: String = ""

    /** False on an ABI without the native library; callers degrade to an empty lookup. */
    val isAvailable: Boolean get() = engine.isAvailable

    /** Import a Yomitan ZIP through the engine (02 §6.2). Not `@Synchronized`: heavy + suspend. */
    suspend fun importDictionary(
        zip: File,
        outputDir: File,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportResult = engine.importDictionary(zip, outputDir, onProgress)

    fun probeEntryTypes(dictDir: File): EntryTypeCounts = engine.probeEntryTypes(dictDir)

    @Synchronized
    fun warmUp(paths: DictionaryPaths, profileId: String = "") {
        if (!engine.isAvailable) return
        val active = session ?: engine.openSession(paths).also { session = it }
        val pathsChanged = paths != configuredPaths
        val profileChanged = profileId.isNotEmpty() && profileId != lastWarmedProfileId
        if (pathsChanged || profileChanged || cachedStyles.isEmpty()) {
            active.rebuild(paths)
            cachedStyles = active.styles()
            configuredPaths = paths
            if (profileId.isNotEmpty()) lastWarmedProfileId = profileId
        }
    }

    @Synchronized
    fun lookup(query: String, paths: DictionaryPaths, languageCode: String = ""): LookupResult2 {
        if (!engine.isAvailable) return LookupResult2(emptyList(), emptyList(), emptyMap(), null)
        warmUp(paths)
        val active = session ?: return LookupResult2(emptyList(), emptyList(), emptyMap(), null)
        val results = runCatching { active.lookup(query, MAX_RESULTS, SCAN_LENGTH) }
            .onFailure { Log.e(TAG, "lookup failed for '$query'", it) }
            .getOrDefault(emptyList())
        return LookupResult2(results = results, styles = cachedStyles, mediaDataUris = emptyMap(), error = null)
    }

    @Synchronized
    fun loadMediaAsync(results: List<LookupResult>): Map<String, String> {
        val active = session ?: return emptyMap()
        return buildMediaDataUris(active, results)
    }

    @Synchronized
    fun queryKanji(char: String): KanjiResult? {
        val active = session ?: return null
        return runCatching { active.queryKanji(char) }
            .onFailure { Log.e(TAG, "queryKanji failed for '$char'", it) }
            .getOrNull()
    }

    @Synchronized
    fun close() {
        session?.close()
        session = null
        configuredPaths = DictionaryPaths()
        cachedStyles = emptyList()
        lastWarmedProfileId = ""
    }

    private fun buildMediaDataUris(
        activeSession: DictionarySession,
        results: List<LookupResult>,
    ): Map<String, String> {
        val requested = linkedSetOf<Pair<String, String>>()
        run loop@{
            results.forEach { lookup ->
                lookup.term.glossaries.forEach { glossary ->
                    extractImagePaths(glossary.glossary).forEach { path ->
                        requested += glossary.dictName to path
                        if (requested.size >= MAX_PRELOADED_MEDIA_ITEMS) return@loop
                    }
                }
            }
        }

        val limitedRequested = requested.take(MAX_PRELOADED_MEDIA_ITEMS)
        val out = LinkedHashMap<String, String>(limitedRequested.size)
        limitedRequested.forEach { (dictName, path) ->
            val bytes = activeSession.mediaBytes(dictName, path) ?: return@forEach
            if (bytes.size > MAX_PRELOADED_MEDIA_BYTES) return@forEach
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUri = "data:${guessMimeType(path)};base64,$b64"
            for (candidate in normalizeMediaPathCandidates(path)) {
                out[mediaKey(dictName, candidate)] = dataUri
            }
        }
        return out
    }

    private fun extractImagePaths(glossary: String): Set<String> {
        val text = glossary.trim()
        if (text.isEmpty() || !(text.startsWith("{") || text.startsWith("["))) return emptySet()
        if (!text.contains("\"img\"") && !text.contains("\"image\"")) return emptySet()
        return runCatching {
            val root: Any = if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
            val found = linkedSetOf<String>()
            walkJson(root) { node ->
                val isImageNode = node.optString("tag") == "img" || node.optString("type") == "image"
                if (isImageNode) {
                    node.optString("path").ifBlank { node.optString("src") }
                        .takeIf { it.isNotBlank() }
                        ?.let(found::add)
                }
            }
            found
        }.getOrDefault(emptySet())
    }

    private fun walkJson(element: Any?, onObject: (JSONObject) -> Unit) {
        when (element) {
            is JSONObject -> {
                onObject(element)
                val keys = element.keys()
                while (keys.hasNext()) {
                    val next = element.opt(keys.next())
                    if (next is JSONObject || next is JSONArray) walkJson(next, onObject)
                }
            }
            is JSONArray -> {
                for (i in 0 until element.length()) {
                    val next = element.opt(i)
                    if (next is JSONObject || next is JSONArray) walkJson(next, onObject)
                }
            }
        }
    }

    private fun normalizeMediaPathCandidates(rawPath: String): Set<String> {
        val candidates = linkedSetOf(
            rawPath,
            rawPath.removePrefix("./"),
            rawPath.removePrefix("/"),
            rawPath.replace('\\', '/'),
        )
        runCatching { URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name()) }
            .getOrNull()
            ?.let {
                candidates += it
                candidates += it.removePrefix("./")
                candidates += it.removePrefix("/")
                candidates += it.replace('\\', '/')
            }
        return candidates
    }

    private fun mediaKey(dictName: String, path: String): String = "$dictName\u0000$path"

    private fun guessMimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "bmp" -> "image/bmp"
        else -> "image/png"
    }

    data class LookupResult2(
        val results: List<LookupResult>,
        val styles: List<DictionaryStyle>,
        val mediaDataUris: Map<String, String>,
        val error: String?,
    )

    companion object {
        private const val TAG = "DictionaryRepo"
        private const val MAX_RESULTS = 20
        private const val SCAN_LENGTH = 25
        private const val MAX_PRELOADED_MEDIA_ITEMS = 20
        private const val MAX_PRELOADED_MEDIA_BYTES = 256 * 1024
    }
}
