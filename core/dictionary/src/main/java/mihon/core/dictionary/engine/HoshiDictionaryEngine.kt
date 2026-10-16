// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.core.dictionary.model.DictionaryPaths
import mihon.core.dictionary.model.DictionaryStyle
import mihon.core.dictionary.model.EntryTypeCounts
import mihon.core.dictionary.model.ImportResult
import mihon.core.dictionary.model.KanjiResult
import mihon.core.dictionary.model.LookupResult
import mihon.core.dictionary.model.TermResult
import java.io.File

/** [DictionaryEngine] backed by the vendored `hoshidicts` native library. */
class HoshiDictionaryEngine : DictionaryEngine {

    override val isAvailable: Boolean = runCatching { HoshiDictsNative.ensureLoaded() }.isSuccess

    override suspend fun importDictionary(
        zip: File,
        outputDir: File,
        onProgress: (ImportProgress) -> Unit,
    ): ImportResult {
        ensureAvailable()
        onProgress(ImportProgress.Started)
        val result = withContext(Dispatchers.IO) {
            HoshiDictsNative.importDictionary(zip.absolutePath, outputDir.absolutePath)
        }
        onProgress(ImportProgress.Finished(result))
        return result
    }

    override fun probeEntryTypes(dictDir: File): EntryTypeCounts {
        ensureAvailable()
        return HoshiDictsNative.probeEntryTypes(dictDir.absolutePath).toEntryTypeCounts()
    }

    override fun openSession(paths: DictionaryPaths): DictionarySession {
        ensureAvailable()
        return HoshiDictionarySession(paths)
    }

    private fun ensureAvailable() {
        check(isAvailable) { "hoshidicts_jni is not available on this ABI" }
    }
}

/**
 * Maps the 4-long `probeEntryTypes` reply `[term, freq, pitch, kanji]` to [EntryTypeCounts].
 *
 * Kept as a pure function so the mapping (including a short/empty array from a degraded
 * native call) is unit-testable on the JVM without loading `libhoshidicts_jni`.
 */
internal fun LongArray.toEntryTypeCounts(): EntryTypeCounts = EntryTypeCounts(
    term = getOrElse(0) { 0L },
    freq = getOrElse(1) { 0L },
    pitch = getOrElse(2) { 0L },
    kanji = getOrElse(3) { 0L },
)

/**
 * [DictionarySession] over one native handle. Every call is serialised (`@Synchronized`)
 * because the C++ session is not thread-safe (contract §2.1).
 */
internal class HoshiDictionarySession(paths: DictionaryPaths) : DictionarySession {
    private val handle: Long = HoshiDictsNative.createSession()

    init {
        rebuild(paths)
    }

    @Synchronized
    override fun lookup(text: String, maxResults: Int, scanLength: Int): List<LookupResult> =
        HoshiDictsNative.lookup(handle, text, maxResults, scanLength).toList()

    @Synchronized
    override fun query(text: String): List<TermResult> =
        HoshiDictsNative.query(handle, text).toList()

    @Synchronized
    override fun queryKanji(character: String): KanjiResult =
        HoshiDictsNative.queryKanji(handle, character)

    @Synchronized
    override fun styles(): List<DictionaryStyle> = HoshiDictsNative.getStyles(handle).toList()

    @Synchronized
    override fun mediaBytes(dictName: String, path: String): ByteArray? =
        HoshiDictsNative.getMediaFile(handle, dictName, path)

    @Synchronized
    override fun rebuild(paths: DictionaryPaths) {
        HoshiDictsNative.rebuildQuery(
            handle,
            paths.termPaths.toTypedArray(),
            paths.freqPaths.toTypedArray(),
            paths.pitchPaths.toTypedArray(),
            paths.kanjiPaths.toTypedArray(),
        )
    }

    @Synchronized
    override fun close() {
        HoshiDictsNative.destroySession(handle)
    }
}
