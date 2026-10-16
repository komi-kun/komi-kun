// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.engine

import mihon.core.dictionary.model.DictionaryPaths
import mihon.core.dictionary.model.DictionaryStyle
import mihon.core.dictionary.model.KanjiResult
import mihon.core.dictionary.model.LookupResult
import mihon.core.dictionary.model.TermResult

/**
 * A lookup session over one native handle (contract §2.1).
 *
 * **Not thread-safe:** the engine serialises every call and the repository owns exactly one
 * long-lived session. Never share a session across threads.
 */
interface DictionarySession : AutoCloseable {
    fun lookup(text: String, maxResults: Int = 20, scanLength: Int = 25): List<LookupResult>

    fun query(text: String): List<TermResult>

    fun queryKanji(character: String): KanjiResult

    fun styles(): List<DictionaryStyle>

    fun mediaBytes(dictName: String, path: String): ByteArray?

    /** Rebuild the query from a new dictionary set; cheap no-op when paths are unchanged. */
    fun rebuild(paths: DictionaryPaths)
}
