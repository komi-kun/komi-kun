// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.icerock.moko.resources.StringResource
import mihon.core.dictionary.model.TYPE_SUBDIRS
import mihon.core.dictionary.model.dictionaryTypeDir
import mihon.core.dictionary.repository.DictionaryRepository
import mihon.core.dictionary.ui.invalidateDictionaryTitle
import tachiyomi.i18n.kun.KUNMR
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

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
 * Why an import produced no dictionary. An import has exactly two outcomes, so a caller can never
 * mistake a failure for "nothing was picked": [message] is the string the UI shows for it.
 */
enum class DictionaryImportFailure(val message: StringResource) {
    /** The picked file could not be opened or read (permission revoked, deleted, I/O error). */
    UnreadableSource(KUNMR.strings.pref_import_dictionary_failed_source),

    /** The engine rejected the file, or it produced no dictionary tree (corrupt/unsupported ZIP). */
    InvalidDictionary(KUNMR.strings.pref_import_dictionary_failed_invalid),

    /** Writing the imported tree failed (out of space, read-only storage, I/O error). */
    StorageFailure(KUNMR.strings.pref_import_dictionary_failed_storage),

    /** Another import is still running; this one never started. */
    AlreadyRunning(KUNMR.strings.pref_import_dictionary_failed_busy),

    /** Anything else. */
    Unknown(KUNMR.strings.pref_import_dictionary_failed),
}

/** The outcome of one import: either the imported dictionary, or the reason there is none. */
sealed interface DictionaryImportOutcome {
    data class Imported(val dictionary: ImportedDictionary) : DictionaryImportOutcome

    data class Failed(val reason: DictionaryImportFailure) : DictionaryImportOutcome
}

/**
 * The single implementation of "add a dictionary to the app": run the native engine import over a
 * Yomitan ZIP, copy the resulting directory into the applicable type subdirs, register it in the
 * active profile, and drop it from the resolver cache.
 *
 * Both the existing single-ZIP "Import dictionary" action and the new Yomitan-backup import reuse
 * this — there is no second engine entry point.
 */
object DictionaryImportCoordinator {

    /**
     * Set while one import runs. The engine import and the delete-and-copy are a single logical
     * operation, and two overlapping ones would copy into the same type dirs and let the first
     * completion clear the second's progress in the UI — so the second is rejected instead.
     */
    private val importInFlight = AtomicBoolean(false)

    /** Import from a SAF ZIP [uri] (the existing single-dictionary flow). */
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        prefs: DictionaryPreferences,
        repository: DictionaryRepository,
    ): DictionaryImportOutcome = guarded {
        val tempZip = File(context.cacheDir, "dict_import_${System.currentTimeMillis()}.zip")
        try {
            val copied = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempZip.outputStream().use { output -> input.copyTo(output) }
                    true
                } ?: false
            } catch (e: SecurityException) {
                Log.e(TAG, "opening $uri failed", e)
                false
            }
            if (!copied) {
                return@guarded DictionaryImportOutcome.Failed(DictionaryImportFailure.UnreadableSource)
            }
            importZipLocked(context, tempZip, prefs, repository)
        } catch (e: Throwable) {
            Log.e(TAG, "import failed", e)
            DictionaryImportOutcome.Failed(failureOf(e))
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
    ): DictionaryImportOutcome = guarded { importZipLocked(context, zip, prefs, repository) }

    /** Run [block] as the only import in flight, or reject it with [DictionaryImportFailure.AlreadyRunning]. */
    private suspend fun guarded(block: suspend () -> DictionaryImportOutcome): DictionaryImportOutcome {
        if (!importInFlight.compareAndSet(false, true)) {
            return DictionaryImportOutcome.Failed(DictionaryImportFailure.AlreadyRunning)
        }
        return try {
            block()
        } finally {
            importInFlight.set(false)
        }
    }

    /** The single import body; callers must already hold the in-flight guard ([guarded]). */
    private suspend fun importZipLocked(
        context: Context,
        zip: File,
        prefs: DictionaryPreferences,
        repository: DictionaryRepository,
    ): DictionaryImportOutcome {
        val tempImportDir = File(context.cacheDir, "dict_import_tmp_${System.nanoTime()}")
        try {
            tempImportDir.mkdirs()
            val result = repository.importDictionary(zip, tempImportDir)
            if (!result.success) return DictionaryImportOutcome.Failed(DictionaryImportFailure.InvalidDictionary)

            val importedDir = tempImportDir.listFiles()?.firstOrNull { it.isDirectory }
                ?: return DictionaryImportOutcome.Failed(DictionaryImportFailure.InvalidDictionary)
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

            return DictionaryImportOutcome.Imported(
                ImportedDictionary(
                    title = title,
                    termCount = result.termCount,
                    freqCount = result.freqCount,
                    pitchCount = result.pitchCount,
                    kanjiCount = result.kanjiCount,
                    mediaCount = result.mediaCount,
                ),
            )
        } catch (e: Throwable) {
            Log.e(TAG, "import failed", e)
            return DictionaryImportOutcome.Failed(failureOf(e))
        } finally {
            if (tempImportDir.exists()) tempImportDir.deleteRecursively()
        }
    }

    /**
     * A failure the coordinator could not name is still a failure: an I/O fault while the tree is
     * written is almost always storage (disk full, read-only volume), everything else is generic.
     */
    private fun failureOf(e: Throwable): DictionaryImportFailure = when (e) {
        is IOException -> DictionaryImportFailure.StorageFailure
        else -> DictionaryImportFailure.Unknown
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
