// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import android.net.Uri
import android.util.Log
import mihon.core.dictionary.model.TYPE_SUBDIRS
import mihon.core.dictionary.model.dictionaryTypeDir
import mihon.core.dictionary.repository.DictionaryRepository
import mihon.core.dictionary.ui.invalidateDictionaryTitle
import java.io.File

/** One dictionary successfully imported through the engine + type-dir copy path. */
data class ImportedDictionary(
    val title: String,
    val termCount: Long,
    val freqCount: Long,
    val pitchCount: Long,
    val kanjiCount: Long,
    val mediaCount: Long,
)

/**
 * The single implementation of "add a dictionary to the app": run the native engine import over a
 * Yomitan ZIP, copy the resulting directory into the applicable type subdirs, register it in the
 * active profile, and drop it from the resolver cache.
 *
 * Both the existing single-ZIP "Import dictionary" action and the new Yomitan-backup import reuse
 * this — there is no second engine entry point.
 */
object DictionaryImportCoordinator {

    /** Import from a SAF ZIP [uri] (the existing single-dictionary flow). */
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        prefs: DictionaryPreferences,
        repository: DictionaryRepository,
    ): ImportedDictionary? {
        val tempZip = File(context.cacheDir, "dict_import_${System.currentTimeMillis()}.zip")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempZip.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            importFromZip(context, tempZip, prefs, repository)
        } catch (e: Throwable) {
            Log.e(TAG, "import failed", e)
            null
        } finally {
            if (tempZip.exists()) tempZip.delete()
        }
    }

    /**
     * Import an already-materialised ZIP file. The caller owns [zip] (it is not deleted here) so the
     * backup flow can manage its own staging lifecycle.
     */
    suspend fun importFromZip(
        context: Context,
        zip: File,
        prefs: DictionaryPreferences,
        repository: DictionaryRepository,
    ): ImportedDictionary? {
        val tempImportDir = File(context.cacheDir, "dict_import_tmp_${System.nanoTime()}")
        try {
            tempImportDir.mkdirs()
            val result = repository.importDictionary(zip, tempImportDir)
            if (!result.success) return null

            val importedDir = tempImportDir.listFiles()?.firstOrNull { it.isDirectory } ?: return null
            val title = result.title.ifBlank { importedDir.name }

            val targets = buildList {
                if (result.termCount > 0) add("term")
                if (result.freqCount > 0) add("frequency")
                if (result.pitchCount > 0) add("pitch")
                if (result.kanjiCount > 0) add("kanji")
            }.ifEmpty { TYPE_SUBDIRS }

            // The delete+copy runs inside the repository's serialized tree mutation: the open native
            // session is dropped first and no lookup/media read can run until the new tree is in
            // place. A same-title re-import keeps every configured path identical, so this close is
            // also what makes the new files visible (see `DictionaryRepository.withDictionaryTreeMutation`).
            repository.withDictionaryTreeMutation {
                for (type in targets) {
                    val destDir = dictionaryTypeDir(context, type, title)
                    if (destDir.exists()) destDir.deleteRecursively()
                    destDir.parentFile?.mkdirs()
                    importedDir.copyRecursively(destDir, overwrite = true)
                }
            }

            val profile = prefs.profileStore.getActiveProfile()
            val order = profile.dictionaryOrder.filter { it.isNotBlank() }
            if (title !in order) {
                prefs.profileStore.updateProfile(
                    profile.copy(
                        dictionaryOrder = order + title,
                        enabledDictionaries = if (profile.enabledDictionaries.isNotEmpty()) {
                            profile.enabledDictionaries + title
                        } else {
                            profile.enabledDictionaries
                        },
                    ),
                )
            }
            invalidateDictionaryTitle(title)

            return ImportedDictionary(
                title = title,
                termCount = result.termCount,
                freqCount = result.freqCount,
                pitchCount = result.pitchCount,
                kanjiCount = result.kanjiCount,
                mediaCount = result.mediaCount,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "import failed", e)
            return null
        } finally {
            if (tempImportDir.exists()) tempImportDir.deleteRecursively()
        }
    }

    /**
     * Remove all type subdirs for [name] and drop it from the active profile order.
     *
     * The directory deletion and the profile update run inside
     * [DictionaryRepository.withDictionaryTreeMutation]: the live session is dropped first and the
     * repository monitor is held for both, so a concurrent lookup can neither read a half-deleted
     * tree nor be served from the pre-delete query, and the next lookup rebuilds from what is left.
     */
    fun delete(
        context: Context,
        name: String,
        prefs: DictionaryPreferences,
        repository: DictionaryRepository,
    ) {
        repository.withDictionaryTreeMutation {
            TYPE_SUBDIRS.forEach { type ->
                val dir = dictionaryTypeDir(context, type, name)
                if (dir.exists()) dir.deleteRecursively()
            }
            val profile = prefs.profileStore.getActiveProfile()
            prefs.profileStore.updateProfile(
                profile.copy(
                    dictionaryOrder = profile.dictionaryOrder.filter { it != name },
                    enabledDictionaries = profile.enabledDictionaries - name,
                ),
            )
        }
        // Display-name aliases are UI-only state, not part of the tree the session is built from.
        prefs.setDisplayName(name, null)
    }

    private const val TAG = "DictImport"
}
// KUN <--
