// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// OCR preferences owned by the OCR track. App/reader code reaches these through the Metro graph
// (`OcrBindings`); the keys are komi-kun's own `KMR` preference keys. The per-manga override falls
// back to the global default when unset (03 §5.3).
// KUN <--
package mihon.core.ocr

import mihon.core.ocr.paddle.PaddleOcrOptions
import tachiyomi.core.common.ocr.ReaderOcrSource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * **Queue/source policy and engine-tuning** preferences for the OCR feature (fix B3 — single source
 * of truth).
 *
 * This class owns only the preferences that the GPL `:core:ocr` services must read: the
 * per-manga/global OCR source policy (resolved by the OCR manager's `sourceFor`), the queue
 * knobs ([autoOnDownload] consumed by the downloader, [parallelOcrLimit] consumed by the queue
 * runner) and the detector/recogniser thresholds the Paddle engine reads per page
 * ([detectionOptions]). It is persisted through [PreferenceStore] so it is reachable from both
 * `:core:ocr` and the app settings screen without either importing app/domain types.
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

    // KUN -->
    // ------------------------------------------------------------------ engine tuning
    // The three detector knobs map 1:1 onto the JNI `nativeProcess` arguments and the native
    // `Options` (see `komi-kun-ocr-models` `src/ocr_engine.h`): they need no artefact change.
    // They are stored as whole-number percentages so they can use the shared `SliderPreference`.
    // The recognition floor is applied in Kotlin on the per-line score the engine already emits,
    // so it also needs no native change.

    /** Detection binarisation threshold (native `det_db_thresh`), percent of the probability map. */
    val detPixelThreshold: Preference<Int> = preferenceStore.getInt("pref_ocr_det_pixel_threshold", DEFAULT_DET_PIXEL)

    /** Detection box score threshold (native `det_db_box_thresh`), percent. */
    val detBoxThreshold: Preference<Int> = preferenceStore.getInt("pref_ocr_det_box_threshold", DEFAULT_DET_BOX)

    /** Detection box expansion (native `det_db_unclip_ratio`), percent (140 = ratio 1.40). */
    val detUnclipRatio: Preference<Int> = preferenceStore.getInt("pref_ocr_det_unclip_ratio", DEFAULT_DET_UNCLIP)

    /** Drop recognised lines whose engine confidence is below this percent (0 = keep everything). */
    val recConfidenceFloor: Preference<Int> = preferenceStore.getInt("pref_ocr_rec_confidence_floor", DEFAULT_REC_FLOOR)

    /**
     * Recogniser weights: the manga fine-tune (default) or the stock PP-OCRv6 model. Stored as the
     * enum name so the value stays readable in the preference store.
     */
    val recModelVariant: Preference<String> = preferenceStore.getString(
        "pref_ocr_rec_model",
        RecModelVariant.DEFAULT.name,
    )

    fun resolveRecModel(): RecModelVariant = RecModelVariant.from(recModelVariant.get())

    /** File name the engine and downloader must use for the selected variant. */
    fun recModelFile(): String = resolveRecModel().recModel

    /**
     * The effective engine options for a page. [numThreads] is the device-derived thread count the
     * engine normally computes itself; the thresholds come from the user preferences above, so
     * changing them takes effect for every page processed afterwards.
     */
    fun detectionOptions(numThreads: Int): PaddleOcrOptions = PaddleOcrOptions(
        thresh = detPixelThreshold.get().coerceIn(1, 99) / 100f,
        boxThresh = detBoxThreshold.get().coerceIn(1, 99) / 100f,
        unclipRatio = detUnclipRatio.get().coerceIn(100, 300) / 100f,
        recConfidenceFloor = recConfidenceFloor.get().coerceIn(0, 99) / 100f,
        numThreads = numThreads,
    )

    /**
     * A suffix that distinguishes OCR results produced with non-default thresholds, so changing a
     * threshold re-runs OCR instead of serving a stale cached page. Empty at the defaults; the
     * OCR manager combines it with its engine-generation key.
     */
    fun cacheSignature(): String {
        val pixel = detPixelThreshold.get()
        val box = detBoxThreshold.get()
        val unclip = detUnclipRatio.get()
        val floor = recConfidenceFloor.get()
        // Different weights produce different text: never serve a cached page across a variant switch.
        val model = resolveRecModel().let { if (it == RecModelVariant.DEFAULT) "" else "m${it.name}" }
        if (model.isEmpty() && pixel == DEFAULT_DET_PIXEL && box == DEFAULT_DET_BOX &&
            unclip == DEFAULT_DET_UNCLIP && floor == DEFAULT_REC_FLOOR
        ) {
            return ""
        }
        return "${model}t${pixel}b${box}u${unclip}r$floor"
    }

    companion object {
        // Defaults match the ocrtest "Full Page Preset" for detection (0.15 pixel threshold,
        // 0.25 box score, 1.40 unclip), and a 35% recognition floor for Japanese dialogue
        // (protects stylised dialogue and bleed while structural noise filtering drops garbage
        // SFX reads).
        const val DEFAULT_DET_PIXEL = 15
        const val DEFAULT_DET_BOX = 25
        const val DEFAULT_DET_UNCLIP = 140
        const val DEFAULT_REC_FLOOR = 35
    }
    // KUN <--
}
