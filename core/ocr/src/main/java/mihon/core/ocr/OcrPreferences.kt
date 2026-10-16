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
 * Preferences for the OCR feature: the per-manga/global OCR source policy, the overlay toggles and
 * the OCR queue parallelism.
 *
 * Persisted through [PreferenceStore] so it is reachable from both `:core:ocr` services and the
 * app settings screen without either importing app/domain types.
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

    /** Draw the OCR overlay in the reader. */
    val overlayEnabled: Preference<Boolean> = preferenceStore.getBoolean("pref_ocr_overlay_enabled", false)

    /** Allow a two-finger tap to toggle the OCR overlay. */
    val twoFingerGestureEnabled: Preference<Boolean> = preferenceStore.getBoolean("pref_ocr_two_finger_gesture", false)

    /** Queue OCR automatically once a chapter finishes downloading. */
    val autoOnDownload: Preference<Boolean> = preferenceStore.getBoolean("pref_ocr_auto_on_download", false)

    /** How many chapters the OCR queue processes concurrently. */
    val parallelOcrLimit: Preference<Int> = preferenceStore.getInt("pref_ocr_parallel_limit", 1)

    /** Draw bounding outlines around detected text regions. */
    val outlineVisible: Preference<Boolean> = preferenceStore.getBoolean("pref_show_ocr_outlines", true)
}
