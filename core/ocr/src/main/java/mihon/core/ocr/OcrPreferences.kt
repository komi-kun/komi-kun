// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// OCR preferences owned by the OCR track. App/reader code reaches these through the Metro graph
// (`OcrBindings`); the keys are komi-kun's own `KMR` preference keys. The per-manga override falls
// back to the global default when unset (03 §5.3).
// KUN <--
package mihon.core.ocr

import tachiyomi.core.common.ocr.ReaderOcrSource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * **Queue/source policy** preferences for the OCR feature (fix B3 — single source of truth).
 *
 * This class owns only the preferences that the GPL `:core:ocr` services must read: the
 * per-manga/global OCR source policy (resolved by the OCR manager's `sourceFor`) and the queue
 * knobs ([autoOnDownload] consumed by the downloader, [parallelOcrLimit] consumed by the queue
 * runner). It is persisted through [PreferenceStore] so it is reachable from both `:core:ocr` and
 * the app settings screen without either importing app/domain types.
 *
 * The **reader-facing display toggles** (overlay enabled, outline visibility, two-finger gesture)
 * deliberately do **not** live here: the reader gates on them from GPL-free files
 * (`ReaderActivity`/`ReaderPreferences`, contract §1.3), and those files cannot import
 * `mihon.core.ocr`. They are owned by `ReaderPreferences` instead — see the OCR region there.
 */
class OcrPreferences(
    private val preferenceStore: PreferenceStore,
) {
    /** Global default OCR source; a per-manga override wins over this. */
    val defaultSource: Preference<String> = preferenceStore.getString(
        "pref_ocr_source",
        ReaderOcrSource.AUTOMATIC.name,
    )

    /** Per-manga OCR source override; blank means "use [defaultSource]". */
    fun source(mangaId: Long): Preference<String> = preferenceStore.getString(
        "pref_ocr_source_$mangaId",
        "",
    )

    /** Resolves the effective source for [mangaId], defaulting to [ReaderOcrSource.AUTOMATIC]. */
    fun resolveSource(mangaId: Long): ReaderOcrSource {
        val value = source(mangaId).get().ifBlank { defaultSource.get() }
        return ReaderOcrSource.entries.firstOrNull { it.name == value } ?: ReaderOcrSource.AUTOMATIC
    }

    /** Queue OCR automatically once a chapter finishes downloading. */
    val autoOnDownload: Preference<Boolean> = preferenceStore.getBoolean("pref_ocr_auto_on_download", false)

    /** How many chapters the OCR queue processes concurrently. */
    val parallelOcrLimit: Preference<Int> = preferenceStore.getInt("pref_ocr_parallel_limit", 1)
}
