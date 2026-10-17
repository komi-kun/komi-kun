// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import mihon.core.dictionary.backup.BackupConversionProgress
import mihon.core.dictionary.backup.BackupFormatException
import mihon.core.dictionary.backup.DexieBackupConverter
import mihon.core.dictionary.repository.DictionaryRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Pre-flight facts about a picked backup, shown before the import starts. */
data class BackupInspection(
    val displayName: String,
    val sizeBytes: Long,
    val freeBytes: Long,
    val requiredBytes: Long,
) {
    /** The temp bank tree alone needs roughly half the backup size; below this it cannot work. */
    val hasMinimumSpace: Boolean get() = freeBytes >= sizeBytes / 2 + 50L * 1024 * 1024

    /** Conservative estimate covering temp tree + ZIPs + imported tree. */
    val hasRecommendedSpace: Boolean get() = freeBytes >= requiredBytes
}

data class BackupImportFailure(val title: String, val reason: String)

data class BackupImportSummary(
    val imported: List<ImportedDictionary>,
    val skipped: List<String>,
    val failed: List<BackupImportFailure>,
    val malformedRows: Long,
    val elapsedMs: Long,
)

/** Progress the UI renders. */
sealed interface BackupImportProgress {
    data object Preparing : BackupImportProgress
    data class Parsing(val rowsRead: Long, val totalRows: Long, val table: String) : BackupImportProgress
    data class Importing(val index: Int, val total: Int, val title: String) : BackupImportProgress
}

class DictionaryBackupImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * On-device driver for the in-app "Import Yomitan backup" action.
 *
 * It streams the picked Dexie JSON with [DexieBackupConverter] (O(row) memory), then feeds every
 * reconstructed ZIP through the **existing** [DictionaryImportCoordinator] engine path — no second
 * engine entry point, no offline detour.
 *
 * Resilience:
 *  - free-space is checked before opening the stream (see [inspect]);
 *  - each dictionary is zipped → imported → its staging freed immediately, so peak disk stays near
 *    one dictionary over the running total rather than one giant tree;
 *  - a small manifest (backup name + size + completed titles) makes a re-run skip dictionaries that
 *    were already imported, and makes re-running fully idempotent.
 */
class DictionaryBackupImporter(
    private val context: Context,
    private val repository: DictionaryRepository,
    private val prefs: DictionaryPreferences,
) {

    private val stagingDir: File get() = File(context.cacheDir, STAGING_NAME)

    fun inspect(uri: Uri): BackupInspection {
        val meta = queryMetadata(uri)
        val probeDir = stagingDir.parentFile ?: context.cacheDir
        val free = runCatching { StatFs(probeDir.absolutePath).availableBytes }.getOrDefault(0L)
        // Temp tree ≈ 0.6× the backup, ZIPs ≈ 0.3×, imported tree ≈ 0.55× → ≈1.45×; round to 1.5×.
        val required = meta.size * 3 / 2 + 200L * 1024 * 1024
        return BackupInspection(meta.name, meta.size, free, required)
    }

    suspend fun import(
        uri: Uri,
        onProgress: (BackupImportProgress) -> Unit,
    ): BackupImportSummary {
        val startedAt = System.currentTimeMillis()
        onProgress(BackupImportProgress.Preparing)

        val inspection = inspect(uri)
        if (!inspection.hasMinimumSpace) {
            throw DictionaryBackupImportException(
                "Not enough free space: ~${inspection.freeBytes / 1_048_576} MB free, " +
                    "at least ~${(inspection.sizeBytes / 2 + 50L * 1024 * 1024) / 1_048_576} MB needed.",
            )
        }

        val manifest = ResumeManifest.open(stagingDir, inspection)
        val converter = DexieBackupConverter()
        val imported = ArrayList<ImportedDictionary>()
        val failed = ArrayList<BackupImportFailure>()

        val stream = context.contentResolver.openInputStream(uri)
            ?: throw DictionaryBackupImportException("Could not open the picked file.")

        val conversion = try {
            stream.use { input ->
                converter.convert(
                    input = input,
                    stagingDir = stagingDir,
                    shouldInclude = { title -> title !in manifest.completed },
                    onProgress = { progress ->
                        when (progress) {
                            BackupConversionProgress.Sniffing -> onProgress(BackupImportProgress.Preparing)
                            is BackupConversionProgress.Parsing -> onProgress(
                                BackupImportProgress.Parsing(progress.rowsRead, progress.totalRows, progress.table),
                            )
                            is BackupConversionProgress.Finalizing -> onProgress(
                                BackupImportProgress.Importing(progress.index, progress.total, progress.title),
                            )
                        }
                    },
                    onDictionaryReady = { staged ->
                        try {
                            val dictionary = DictionaryImportCoordinator.importFromZip(
                                context = context,
                                zip = staged.zip,
                                prefs = prefs,
                                repository = repository,
                            )
                            if (dictionary != null) {
                                imported += dictionary
                                manifest.record(dictionary.title)
                            } else {
                                failed += BackupImportFailure(staged.title, "engine import failed")
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "import of '${staged.title}' failed", e)
                            failed += BackupImportFailure(staged.title, e.message ?: "error")
                        } finally {
                            runCatching { staged.zip.delete() }
                        }
                    },
                )
            }
        } catch (e: BackupFormatException) {
            throw DictionaryBackupImportException(e.message ?: "Unsupported backup file.", e)
        } finally {
            manifest.flush()
        }

        // A failed engine import must not be remembered as done, so drop those from the manifest.
        manifest.removeAll(failed.map { it.title })
        manifest.flush()

        return BackupImportSummary(
            imported = imported,
            skipped = conversion.skippedTitles,
            failed = failed,
            malformedRows = conversion.malformedRows,
            elapsedMs = System.currentTimeMillis() - startedAt,
        )
    }

    private data class Metadata(val name: String, val size: Long)

    private fun queryMetadata(uri: Uri): Metadata {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "backup.json"
        var size = 0L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }.onFailure { Log.w(TAG, "could not query $uri metadata", it) }
        return Metadata(name, size)
    }

    /**
     * Completed-titles journal, keyed to the backup's name+size, so a re-run after a cancel skips
     * dictionaries that already landed (and a *different* backup is not falsely skipped).
     */
    private class ResumeManifest(
        private val file: File,
        val completed: MutableSet<String>,
    ) {
        private var dirty = false

        fun record(title: String) {
            if (completed.add(title)) dirty = true
        }

        fun removeAll(titles: List<String>) {
            if (titles.any { completed.remove(it) }) dirty = true
        }

        fun flush() {
            if (!dirty) return
            val json = JSONObject().apply {
                put("imported", JSONArray(completed.toList()))
            }
            runCatching { file.writeText(json.toString()) }
            dirty = false
        }

        companion object {
            fun open(stagingDir: File, inspection: BackupInspection): ResumeManifest {
                val file = File(stagingDir, MANIFEST_NAME)
                val completed = mutableSetOf<String>()
                var valid = false
                val existing = runCatching { JSONObject(file.readText()) }.getOrNull()
                if (existing != null &&
                    existing.optString("name") == inspection.displayName &&
                    existing.optLong("size") == inspection.sizeBytes
                ) {
                    valid = true
                    existing.optJSONArray("imported")?.let { array ->
                        for (i in 0 until array.length()) completed += array.optString(i)
                    }
                }
                // Fresh (or mismatched) backup: drop stale staging so we do not mix backups.
                if (!valid) stagingDir.deleteRecursively()
                stagingDir.mkdirs()
                file.writeText(
                    JSONObject().apply {
                        put("name", inspection.displayName)
                        put("size", inspection.sizeBytes)
                        put("imported", JSONArray(completed.toList()))
                    }.toString(),
                )
                return ResumeManifest(file, completed)
            }

            private const val MANIFEST_NAME = "resume.json"
        }
    }

    private companion object {
        const val TAG = "DictBackupImport"
        const val STAGING_NAME = "yomitan_backup"
    }
}
// KUN <--
