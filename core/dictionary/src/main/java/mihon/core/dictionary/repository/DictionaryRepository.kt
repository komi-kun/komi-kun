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
import tachiyomi.core.common.preference.DictionaryOcrPreferences
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
    // KUN -->
    /** Explicit dictionary/OCR opt-in: no engine or dictionary work happens while it is off. */
    private val dictionaryOcr: DictionaryOcrPreferences,
    // KUN <--
) {
    private var session: DictionarySession? = null
    private var configuredPaths: DictionaryPaths = DictionaryPaths()
    private var cachedStyles: List<DictionaryStyle> = emptyList()
    private var lastWarmedProfileId: String = ""

    /** False on an ABI without the native library; callers degrade to an empty lookup. */
    val isAvailable: Boolean get() = engine.isAvailable

    // KUN -->
    /**
     * True while the user has not opted into the dictionary/OCR features. Every public entry point
     * that would import, open a session, look up or read media is a no-op in that state, so the
     * engine is never touched (and no stale result is served) before the user enables it.
     */
    private fun dictionaryDisabled(): Boolean = !dictionaryOcr.isEnabled()
    // KUN <--

    /** Import a Yomitan ZIP through the engine (02 §6.2). Not `@Synchronized`: heavy + suspend. */
    suspend fun importDictionary(
        zip: File,
        outputDir: File,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportResult {
        // KUN -->
        if (dictionaryDisabled()) return disabledImportResult()
        // KUN <--
        return engine.importDictionary(zip, outputDir, onProgress)
    }

    fun probeEntryTypes(dictDir: File): EntryTypeCounts {
        // KUN -->
        if (dictionaryDisabled()) return EntryTypeCounts(term = 0, freq = 0, pitch = 0, kanji = 0)
        // KUN <--
        return engine.probeEntryTypes(dictDir)
    }

    @Synchronized
    fun warmUp(paths: DictionaryPaths, profileId: String = "") {
        // KUN -->
        if (dictionaryDisabled()) return
        // KUN <--
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
        // KUN -->
        // Off means "no dictionary at all": never serve the previous session's (stale) results/styles.
        if (dictionaryDisabled()) return disabledLookupResult()
        // KUN <--
        if (!engine.isAvailable) return LookupResult2(emptyList(), emptyList(), emptyMap(), null)
        warmUp(paths)
        val active = session ?: return LookupResult2(emptyList(), emptyList(), emptyMap(), null)
        val results = runCatching { active.lookup(query, MAX_RESULTS, SCAN_LENGTH) }
            .onFailure { Log.e(TAG, "lookup failed for '$query'", it) }
            .getOrDefault(emptyList())
        return LookupResult2(results = results, styles = cachedStyles, mediaDataUris = emptyMap(), error = null)
    }

    // KUN -->
    @Synchronized
    fun loadMediaAsync(results: List<LookupResult>, kanjiResult: KanjiResult? = null): Map<String, String> {
        if (dictionaryDisabled()) return emptyMap()
        val active = session ?: return emptyMap()
        return buildMediaDataUris(active, results, kanjiResult)
    }
    // KUN <--

    @Synchronized
    fun queryKanji(char: String): KanjiResult? {
        // KUN -->
        if (dictionaryDisabled()) return null
        // KUN <--
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
        // KUN -->
        kanjiResult: KanjiResult? = null,
        // KUN <--
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
            // KUN --> Support images/diagrams referenced in kanji entries
            kanjiResult?.entries?.forEach { entry ->
                entry.definitions.forEach { def ->
                    extractImagePaths(def).forEach { path ->
                        requested += entry.dictName to path
                        if (requested.size >= MAX_PRELOADED_MEDIA_ITEMS) return@loop
                    }
                }
                entry.stats.values.forEach { statVal ->
                    extractImagePaths(statVal).forEach { path ->
                        requested += entry.dictName to path
                        if (requested.size >= MAX_PRELOADED_MEDIA_ITEMS) return@loop
                    }
                }
            }
            // KUN <--
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
        if (text.isEmpty()) return emptySet()
        // KUN --> Direct image/SVG path support (e.g. stroke order diagram file paths)
        val lower = text.lowercase()
        if ((
                lower.endsWith(".svg") || lower.endsWith(".png") || lower.endsWith(".jpg") ||
                    lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".webp")
                ) &&
            !text.contains("\n") && text.length < 256 && !text.startsWith("<")
        ) {
            return setOf(text)
        }
        // KUN <--
        if (!(text.startsWith("{") || text.startsWith("["))) return emptySet()
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

    // KUN -->
    /** The failure an import reports while the feature is off (never a fake success). */
    private fun disabledImportResult(): ImportResult = ImportResult(
        success = false,
        title = "",
        termCount = 0,
        metaCount = 0,
        freqCount = 0,
        pitchCount = 0,
        kanjiCount = 0,
        mediaCount = 0,
        errors = listOf(DISABLED_MESSAGE),
    )

    /** An empty, error-free lookup result: nothing to show, and no stale styles/results. */
    private fun disabledLookupResult(): LookupResult2 = LookupResult2(
        results = emptyList(),
        styles = emptyList(),
        mediaDataUris = emptyMap(),
        error = null,
    )
    // KUN <--

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

        // KUN -->
        /** Reported when a caller asks the engine for work while the feature is opted out. */
        private const val DISABLED_MESSAGE =
            "Dictionary/OCR is disabled: enable the immersion features before importing dictionaries"
        // KUN <--
    }
}
