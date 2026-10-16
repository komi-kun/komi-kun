// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.model

import android.content.Context
import java.io.File

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

/** Directory holding the imported dictionary tree: `externalFilesDir/dictionaries`. */
fun dictionariesDir(context: Context): File = File(context.getExternalFilesDir(null), "dictionaries")

/**
 * All dictionary directory names present across the four type subdirs, distinct and sorted.
 * Single source of truth for import/management UI and path resolution (02 §3.4/§6.1).
 */
fun allDictionaryNames(context: Context): List<String> {
    val root = dictionariesDir(context)
    return TYPE_SUBDIRS
        .flatMap { type -> File(root, type).listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty() }
        .distinct()
        .sorted()
}

/** Directory for one dictionary of one type, e.g. `dictionaries/term/<name>`. */
fun dictionaryTypeDir(context: Context, type: String, name: String): File =
    File(File(dictionariesDir(context), type), name)

private var cachedPaths: DictionaryPaths? = null
private var cachedProfileHash: Int? = null
private var cachedDirModified: Long = 0L

/**
 * Resolve the engine [DictionaryPaths] for [profile] (02 §3.4).
 *
 * Result is cached and only recomputed when the on-disk dictionary tree or [profile] changes,
 * so this is cheap to call on every reader/tab open. Paths are ordered by [DictionaryProfile.dictionaryOrder]
 * first, then any newly-imported dictionaries, and filtered by [DictionaryProfile.enabledDictionaries]
 * (an empty set means "all enabled").
 */
fun getDictionaryPaths(context: Context, profile: DictionaryProfile): DictionaryPaths {
    val root = dictionariesDir(context)
    if (!root.exists()) return DictionaryPaths()

    val currentModified = root.lastModified()
    if (cachedPaths != null && cachedDirModified == currentModified && cachedProfileHash == profile.hashCode()) {
        return cachedPaths!!
    }

    val allNames = allDictionaryNames(context)
    if (allNames.isEmpty()) return DictionaryPaths()

    val ordered = profile.dictionaryOrder.filter { it in allNames } + allNames.filter { it !in profile.dictionaryOrder }
    val enabled = profile.enabledDictionaries
    val finalNames = if (enabled.isEmpty()) ordered else ordered.filter { it in enabled }

    fun pathsForType(type: String): List<String> = finalNames
        .map { File(File(root, type), it) }
        .filter { it.isDirectory }
        .map { it.absolutePath }

    val result = DictionaryPaths(
        termPaths = pathsForType("term"),
        freqPaths = pathsForType("frequency"),
        pitchPaths = pathsForType("pitch"),
        kanjiPaths = pathsForType("kanji"),
    )

    cachedPaths = result
    cachedProfileHash = profile.hashCode()
    cachedDirModified = currentModified
    return result
}

val TYPE_SUBDIRS = listOf("term", "frequency", "pitch", "kanji")
