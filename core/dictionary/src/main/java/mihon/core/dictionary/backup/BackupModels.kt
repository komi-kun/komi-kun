// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.backup

import java.io.File

/** A Yomitan dictionary staged from a Dexie backup, containing the reconstructed bank files. */
data class StagedDictionary(
    /** The real dictionary title, as written into `index.json` and used by the engine. */
    val title: String,
    /** A filesystem-safe name for the on-disk staging dir and ZIP file. */
    val safeName: String,
    /** The reconstructed Yomitan ZIP, ready for the existing import path. */
    val zip: File,
)

/** Coarse progress emitted while streaming a backup. */
sealed interface BackupConversionProgress {
    /** Reading the `data.tables` manifest, before any rows are attributed. */
    data object Sniffing : BackupConversionProgress

    /** Streaming rows. [totalRows] is 0 when the manifest carried no row counts. */
    data class Parsing(
        val rowsRead: Long,
        val totalRows: Long,
        val table: String,
    ) : BackupConversionProgress

    /** Building and handing one dictionary's ZIP to [DexieBackupConverter.convert]'s sink. */
    data class Finalizing(
        val index: Int,
        val total: Int,
        val title: String,
    ) : BackupConversionProgress
}

/** Aggregate outcome of a conversion pass. */
data class BackupConversionResult(
    val staged: List<StagedDictionary>,
    val skippedTitles: List<String>,
    val rowsRead: Long,
    val totalRows: Long,
    val malformedRows: Long,
)
// KUN <--
