// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.model

/**
 * Counts of the four entry types in an imported dictionary directory, as returned by
 * `HoshiDictsNative.probeEntryTypes` (4 longs; contract §2.1).
 */
data class EntryTypeCounts(
    val term: Long,
    val freq: Long,
    val pitch: Long,
    val kanji: Long,
)
