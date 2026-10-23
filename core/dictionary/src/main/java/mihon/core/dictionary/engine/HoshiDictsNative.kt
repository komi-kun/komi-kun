// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.engine

import mihon.core.dictionary.model.DictionaryStyle
import mihon.core.dictionary.model.ImportResult
import mihon.core.dictionary.model.KanjiResult
import mihon.core.dictionary.model.LookupResult
import mihon.core.dictionary.model.TermResult

/**
 * Thin 1:1 binding to the `hoshidicts_jni` shared library (contract §2.1). Kept `internal`
 * so no consumer ever touches raw handles.
 *
 * `System.loadLibrary` is **not** run in a hard `init {}`: [ensureLoaded] is a guarded
 * initialiser, so an ABI without the library (or a plain JVM test) degrades to
 * `DictionaryEngine.isAvailable = false` instead of crashing (contract §3.3).
 */
internal object HoshiDictsNative {
    private val lock = Any()
    private var initialized = false
    private var failure: Throwable? = null

    fun ensureLoaded() {
        synchronized(lock) {
            if (initialized) {
                failure?.let { throw it }
                return
            }
            initialized = true
            try {
                System.loadLibrary("hoshidicts_jni")
            } catch (t: Throwable) {
                failure = t
                throw t
            }
        }
    }

    external fun createSession(): Long

    external fun destroySession(session: Long)

    external fun rebuildQuery(
        session: Long,
        termPaths: Array<String>,
        freqPaths: Array<String>,
        pitchPaths: Array<String>,
        kanjiPaths: Array<String>,
    )

    external fun importDictionary(zipPath: String, outputDir: String): ImportResult

    external fun probeEntryTypes(dictPath: String): LongArray

    external fun lookup(session: Long, text: String, maxResults: Int, scanLength: Int): Array<LookupResult>

    external fun query(session: Long, text: String): Array<TermResult>

    external fun queryKanji(session: Long, character: String): KanjiResult

    external fun getStyles(session: Long): Array<DictionaryStyle>

    external fun getMediaFile(session: Long, dictName: String, mediaPath: String): ByteArray?
}
