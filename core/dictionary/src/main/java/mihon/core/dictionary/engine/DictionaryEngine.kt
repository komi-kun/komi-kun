// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.engine

import mihon.core.dictionary.model.DictionaryPaths
import mihon.core.dictionary.model.EntryTypeCounts
import mihon.core.dictionary.model.ImportResult
import java.io.File

/**
 * The vendored `hoshidicts` dictionary engine (contract §2.1).
 *
 * Every other workstream depends on this interface, never on [HoshiDictsNative].
 */
interface DictionaryEngine {
    /** True once the native library loaded; callers must degrade gracefully when false. */
    val isAvailable: Boolean

    /** Long-running, CPU-heavy import; the JNI blocks on a 16 MB-stack native thread. */
    suspend fun importDictionary(
        zip: File,
        outputDir: File,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportResult

    fun probeEntryTypes(dictDir: File): EntryTypeCounts

    fun openSession(paths: DictionaryPaths): DictionarySession
}

/** Coarse import phases only — the native importer reports no counters (contract §0). */
sealed interface ImportProgress {
    data object Started : ImportProgress
    data class Finished(val result: ImportResult) : ImportProgress
}
