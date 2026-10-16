// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.model

/**
 * Paths to the imported dictionary files a session is built from (contract §2.1).
 *
 * Owned by W2 for the repository/store side; the engine only reads it.
 */
data class DictionaryPaths(
    val termPaths: List<String> = emptyList(),
    val freqPaths: List<String> = emptyList(),
    val pitchPaths: List<String> = emptyList(),
    val kanjiPaths: List<String> = emptyList(),
)
