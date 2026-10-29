// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.icerock.moko.resources.StringResource
import mihon.core.dictionary.model.TYPE_SUBDIRS
import mihon.core.dictionary.model.dictionariesDir
import mihon.core.dictionary.model.dictionaryTypeDir
import mihon.core.dictionary.repository.DictionaryRepository
import mihon.core.dictionary.ui.invalidateDictionaryTitle
import org.json.JSONObject
import tachiyomi.i18n.kun.KUNMR
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

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
            } catch (e: IOException) {
                // Reading the picked document failed (deleted, detached volume, I/O error). That is
                // the *source*, not our storage: only the tree write below is a storage failure.
                Log.e(TAG, "reading $uri failed", e)
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
            // The engine joins `output_dir / index.title` and creates that directory itself
            // (`hoshidicts/src/importer.cpp`), i.e. before any title reaches the copy below — an
            // unsafe title has to be refused *here*, or the engine writes a tree outside
            // `tempImportDir` and the post-import sanitiser below runs too late to prevent it.
            if (hasUnsafeIndexTitle(zip)) {
                return DictionaryImportOutcome.Failed(DictionaryImportFailure.InvalidDictionary)
            }
            tempImportDir.mkdirs()
            val result = repository.importDictionary(zip, tempImportDir)
            if (!result.success) return DictionaryImportOutcome.Failed(DictionaryImportFailure.InvalidDictionary)

            val importedDir = tempImportDir.listFiles()?.firstOrNull { it.isDirectory }
                ?: return DictionaryImportOutcome.Failed(DictionaryImportFailure.InvalidDictionary)
            val title = result.title.ifBlank { importedDir.name }
            // The title is an untrusted `index.json` field used as a directory name below: sanitise
            // it *before* the first `File` operation, or `../../anki_media` makes
            // `dictionaryTypeDir` resolve outside the dictionary root and the delete-and-copy below
            // destroys an unrelated directory.
            val dirName = dictionaryPathName(title)
                ?: return DictionaryImportOutcome.Failed(DictionaryImportFailure.InvalidDictionary)

            val targets = buildList {
                if (result.termCount > 0) add("term")
                if (result.freqCount > 0) add("frequency")
                if (result.pitchCount > 0) add("pitch")
                if (result.kanjiCount > 0) add("kanji")
            }.ifEmpty { TYPE_SUBDIRS }

            // The staging + swap runs inside the repository's serialized tree mutation: the open
            // native session is dropped first and no lookup/media read can run until the new tree is
            // in place. A same-title re-import keeps every configured path identical, so this close
            // is also what makes the new files visible (see
            // `DictionaryRepository.withDictionaryTreeMutation`).
            repository.withDictionaryTreeMutation {
                replaceDictionaryTrees(dictionariesDir(context), importedDir, dirName, targets)
            }

            val profile = prefs.profileStore.getActiveProfile()
            val order = profile.dictionaryOrder.filter { it.isNotBlank() }
            if (dirName !in order) {
                prefs.profileStore.updateProfile(
                    profile.copy(
                        dictionaryOrder = order + dirName,
                        enabledDictionaries = if (profile.enabledDictionaries.isNotEmpty()) {
                            profile.enabledDictionaries + dirName
                        } else {
                            profile.enabledDictionaries
                        },
                    ),
                )
            }
            invalidateDictionaryTitle(dirName)

            return DictionaryImportOutcome.Imported(
                ImportedDictionary(
                    // The dictionary's own title, kept verbatim for display and for the backup
                    // journal; only the path segment (`dirName`) is sanitised.
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
     * Put the freshly imported tree in place of the previous one, type dir by type dir.
     *
     * Every type is **staged** next to its destination (`<destDir>.importing`) and only then swapped
     * in, inside the caller's [DictionaryRepository.withDictionaryTreeMutation] section. The previous
     * code deleted the live directory and copied onto it in place; `copyRecursively`'s default
     * `onError` rethrows, so a disk-full or interrupted re-import destroyed the working dictionary and
     * left nothing (not even the `dictionaryOrder` entry) to look up with — the whole feature was
     * dead until the user re-imported. Now a failure leaves the previous tree exactly as it was: the
     * staging directories are removed and the swap never starts.
     *
     * The swap is one rename per type (same parent directory, so no copy), after which the types the
     * new build has **no data for** are deleted: `targets` only lists the types the fresh import
     * produced, so without that a re-import that dropped e.g. the pitch entries kept serving the
     * previous dictionary's pitches.
     */
    internal fun replaceDictionaryTrees(
        root: File,
        importedDir: File,
        dirName: String,
        targets: List<String>,
    ) {
        val staged = ArrayList<Pair<File, File>>(targets.size)
        try {
            for (type in targets) {
                val destDir = File(File(root, type), dirName)
                val stagingDir = File(destDir.parentFile, "${destDir.name}$STAGING_SUFFIX")
                if (stagingDir.exists()) stagingDir.deleteRecursively()
                destDir.parentFile?.mkdirs()
                importedDir.copyRecursively(stagingDir, overwrite = true)
                staged += stagingDir to destDir
            }
            for ((stagingDir, destDir) in staged) {
                if (destDir.exists()) destDir.deleteRecursively()
                destDir.parentFile?.mkdirs()
                if (!stagingDir.renameTo(destDir)) {
                    throw IOException("could not move $stagingDir into place at $destDir")
                }
            }
            (TYPE_SUBDIRS - targets.toSet()).forEach { type ->
                val stale = File(File(root, type), dirName)
                if (stale.exists()) stale.deleteRecursively()
            }
        } catch (e: Throwable) {
            // The previous dictionary is still where it was: drop the staging copies and let the
            // caller report the storage failure.
            staged.forEach { (stagingDir, _) -> if (stagingDir.exists()) stagingDir.deleteRecursively() }
            throw e
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
     * The single place a dictionary title becomes a path segment.
     *
     * The title comes from the ZIP's own `index.json`, so it is untrusted: `../../anki_media` or an
     * absolute `/…` would make [dictionaryTypeDir] resolve outside the dictionary root, and this
     * file deletes that directory recursively before copying into it. Separators, NULs, control
     * characters and a dots-only name therefore never reach a [File]: they are folded to `_` and
     * surrounding dots dropped (mirroring `sanitizeTitleForPath` in `DictionaryStaging`, plus DEL).
     *
     * `null` means nothing usable is left, and the import fails cleanly rather than guessing a name.
     * Only the path segment changes — the title reported to the UI stays the dictionary's own.
     */
    private fun dictionaryPathName(raw: String): String? {
        val cleaned = buildString(raw.length) {
            for (c in raw) {
                when {
                    c == '/' || c == '\\' || c == '\u0000' || c.code < 0x20 || c.code == 0x7f -> append('_')
                    else -> append(c)
                }
            }
        }.trim().trim('.').trim()
        return cleaned.take(MAX_PATH_NAME_CHARS).takeIf { it.isNotEmpty() }
    }

    /**
     * Whether [zip]'s own `index.json` carries a title the engine must never see.
     *
     * Only a title we can actually read is judged: a file that is not a ZIP, has no `index.json`, or
     * whose `index.json` is not JSON is left to the engine, which rejects it before writing
     * anything — that keeps "InvalidDictionary" for corrupt input on the engine's side, as before.
     * A repeated `index.json` and an entry that cannot be read within [MAX_INDEX_BYTES] are *not*
     * "nothing to judge": those are refused, see [readIndexTitle].
     */
    private fun hasUnsafeIndexTitle(zip: File): Boolean = when (val check = readIndexTitle(zip)) {
        IndexTitleCheck.None -> false
        IndexTitleCheck.Unreadable, IndexTitleCheck.Duplicate -> true
        is IndexTitleCheck.Title -> isUnsafeTitle(check.value)
    }

    /**
     * Reads the title out of [zip]'s `index.json` the way the engine reads it.
     *
     * * **Exact name, first match.** The engine's own ZIP reader scans the central directory and
     *   returns the *first* entry whose name equals `index.json` (`hoshidicts/src/zip/zip.cpp`,
     *   `Zip::find`), while [ZipFile] resolves an entry **by name**: with two `index.json` entries,
     *   `getEntry` (and even `getInputStream`) answer the last one, so a ZIP like that made this
     *   fence judge `{"title":"Safe"}` while the engine used `{"title":"../../evil"}` — and the
     *   engine wrote and deleted outside the dictionary root. The title is therefore taken from the
     *   first match of [ZipFile.entries] (central-directory order, as the engine scans) and any
     *   repeated entry is reported as [IndexTitleCheck.Duplicate] and refused outright: with only
     *   one entry both readers provably see the same bytes. No legitimate Yomitan dictionary ships
     *   a duplicate.
     * * **Bounded.** Only `title` is needed, so at most [MAX_INDEX_BYTES] are read; a longer or
     *   unreadable entry is [IndexTitleCheck.Unreadable] rather than an unbounded read.
     */
    internal fun readIndexTitle(zip: File): IndexTitleCheck {
        val archive = runCatching { ZipFile(zip) }.getOrNull() ?: return IndexTitleCheck.None
        return archive.use { file ->
            var first: ZipEntry? = null
            var count = 0
            for (entry in file.entries()) {
                if (entry.name == INDEX_ENTRY) {
                    if (first == null) first = entry
                    count++
                }
            }
            // Refused before reading anything: the bytes behind the two entries are not the ones
            // this fence would get back, so nothing about the archive can be trusted here.
            if (count > 1) return@use IndexTitleCheck.Duplicate
            val entry = first ?: return@use IndexTitleCheck.None
            val text = readCapped(file, entry)?.toString(Charsets.UTF_8)
                ?: return@use IndexTitleCheck.Unreadable
            // Not JSON, or no title: the engine rejects such a file before it writes anything.
            val title = runCatching { JSONObject(text).optString("title", null) }.getOrNull()
                ?: return@use IndexTitleCheck.None
            IndexTitleCheck.Title(title)
        }
    }

    /** At most [MAX_INDEX_BYTES] of [entry]; `null` when it cannot be read or is longer. */
    private fun readCapped(archive: ZipFile, entry: ZipEntry): ByteArray? = runCatching {
        archive.getInputStream(entry).use { input ->
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(1024)
            var read = input.read(chunk)
            while (read >= 0) {
                out.write(chunk, 0, read)
                if (out.size() > MAX_INDEX_BYTES) return@use null
                read = input.read(chunk)
            }
            out.toByteArray()
        }
    }.getOrNull()

    /** What [readIndexTitle] found in a dictionary ZIP. */
    internal sealed interface IndexTitleCheck {
        /** No `index.json` at all: nothing to judge, the engine rejects or imports the file. */
        data object None : IndexTitleCheck

        /** The entry exists but is longer than [MAX_INDEX_BYTES] or could not be read: refused. */
        data object Unreadable : IndexTitleCheck

        /**
         * More than one entry is named `index.json`, so the fence and the engine cannot be trusted
         * to read the same bytes — however the titles compare. Refused.
         */
        data object Duplicate : IndexTitleCheck

        /** The single `index.json` title, and therefore the one the engine will use. */
        data class Title(val value: String) : IndexTitleCheck
    }

    /**
     * Whether [raw] cannot be a path segment: separators, NULs and control characters let it point
     * outside any directory (the engine's `output_dir / title` included), and a name that is only
     * dots is `.`/`..`.
     *
     * Deliberately narrower than [dictionaryPathName]: it flags only what is dangerous, so the
     * post-import sanitiser still handles merely awkward titles (stray spaces, over-long names).
     */
    private fun isUnsafeTitle(raw: String): Boolean {
        if (raw.isEmpty()) return true
        for (c in raw) {
            if (c == '/' || c == '\\' || c == '\u0000' || c.code < 0x20 || c.code == 0x7f) return true
        }
        return raw.trim().trim('.').trim().isEmpty()
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
        // Same guard as the import: a name that is not a plain segment could resolve outside the
        // dictionary root. It is refused rather than sanitised — deleting a *sanitised* variant
        // could remove a different dictionary than the one the caller meant.
        if (dictionaryPathName(name) != name) {
            Log.e(TAG, "refusing to delete non-segment dictionary name")
            return
        }
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

    /** Suffix of the sibling directory a type is staged into before it replaces the live one. */
    private const val STAGING_SUFFIX = ".importing"

    /** Cap on a title-derived path segment; the same length `sanitizeTitleForPath` uses. */
    private const val MAX_PATH_NAME_CHARS = 120

    /** The ZIP entry the engine takes the dictionary title from. */
    private const val INDEX_ENTRY = "index.json"

    /**
     * Cap on the `index.json` bytes the title fence reads. A Yomitan `index.json` only needs a
     * `title`; a real one is well under a kilobyte, so a few KiB is generous and still bounded.
     */
    private const val MAX_INDEX_BYTES = 8 * 1024
}
// KUN <--
