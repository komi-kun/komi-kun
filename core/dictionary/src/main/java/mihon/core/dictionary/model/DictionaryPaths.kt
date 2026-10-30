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
 * Suffix of the sibling directory a type is staged into before it replaces the live one.
 *
 * The import (`DictionaryImportCoordinator`) writes these next to a live type directory, so they are
 * **not** dictionaries. A process killed mid-import leaves them behind, and a scanner that lists every
 * child of a type dir hands `<name>.importing` to the engine as a term/frequency path for the life of
 * the install — it is never cleaned up on its own.
 */
const val DICTIONARY_STAGING_SUFFIX = ".importing"

/** Suffix of the sibling directory the live tree is moved aside to while a type swaps. */
const val DICTIONARY_BACKUP_SUFFIX = ".replacing"

/** Whether [name] is one of those import artifacts rather than a dictionary directory name. */
fun isDictionaryArtifactName(name: String): Boolean =
    name.endsWith(DICTIONARY_STAGING_SUFFIX) || name.endsWith(DICTIONARY_BACKUP_SUFFIX)

/**
 * All dictionary directory names present across the four type subdirs, distinct and sorted.
 * Single source of truth for import/management UI and path resolution (02 §3.4/§6.1).
 *
 * Import artifacts ([isDictionaryArtifactName]) are skipped: they are never a dictionary, and a
 * reader resolving one would build a session from a half-written or previous-revision tree.
 */
fun allDictionaryNames(context: Context): List<String> {
    val root = dictionariesDir(context)
    return TYPE_SUBDIRS
        .flatMap { type ->
            File(root, type).listFiles()
                ?.filter { it.isDirectory && !isDictionaryArtifactName(it.name) }
                ?.map { it.name }
                .orEmpty()
        }
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

    val ordered = resolveDictionaryOrder(allNames, profile.dictionaryOrder, context)
    val enabled = profile.enabledDictionaries
    val finalNames = if (enabled.isEmpty()) {
        ordered
    } else {
        val resolvedEnabled = enabled.mapNotNull { matchDictionaryName(it, allNames, context) }.toSet()
        if (resolvedEnabled.isEmpty()) {
            ordered.filter { it in enabled }
        } else {
            ordered.filter { it in resolvedEnabled }
        }
    }

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

/**
 * Normalizes a dictionary title for loose matching (strips bracketed dates, punctuation, extra spaces).
 */
fun normalizeDictionaryTitle(title: String): String {
    return title
        .replace(Regex("""\s*\[[^\]]*\d{4}[^\]]*\]|\s*\([^)]*\d{4}[^)]*\)"""), "")
        .trim()
        .lowercase()
}

/**
 * Reads the dictionary title from index.json for [dirName], or null if unavailable.
 */
fun readDictionaryIndexTitle(context: Context, dirName: String): String? {
    val root = dictionariesDir(context)
    for (type in TYPE_SUBDIRS) {
        val indexFile = File(File(root, type), "$dirName/index.json")
        if (indexFile.exists()) {
            return runCatching {
                val text = indexFile.readText()
                val match = Regex(""""title"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""").find(text)
                match?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }
    }
    return null
}

/**
 * Resolves a dictionary name (which may be a raw Yomitan title, an alias, or a dirName)
 * to an installed [dirName] from [allNames].
 */
fun matchDictionaryName(name: String, allNames: List<String>, context: Context): String? {
    if (name in allNames) return name

    // 1. Exact match on index.json title
    for (candidate in allNames) {
        val title = readDictionaryIndexTitle(context, candidate)
        if (title != null && title.equals(name, ignoreCase = true)) {
            return candidate
        }
    }

    // 2. Normalized match (ignoring date stamps like [2024-10-25] or (2024-10-25))
    val normName = normalizeDictionaryTitle(name)
    if (normName.isNotBlank()) {
        for (candidate in allNames) {
            val title = readDictionaryIndexTitle(context, candidate)
            if (title != null && normalizeDictionaryTitle(title) == normName) {
                return candidate
            }
            if (normalizeDictionaryTitle(candidate) == normName) {
                return candidate
            }
        }
    }

    return null
}

/**
 * Reorders [allNames] according to [profileOrder], resolving any titles or aliases to installed dirNames.
 */
fun resolveDictionaryOrder(
    allNames: List<String>,
    profileOrder: List<String>,
    context: Context,
): List<String> {
    if (profileOrder.isEmpty()) return allNames
    val matchedDirNames = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    for (orderItem in profileOrder) {
        val matched = matchDictionaryName(orderItem, allNames, context)
        if (matched != null && seen.add(matched)) {
            matchedDirNames.add(matched)
        }
    }
    return matchedDirNames + allNames.filter { it !in seen }
}
