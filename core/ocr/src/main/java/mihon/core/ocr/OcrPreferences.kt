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
     * Whether the user opted in to the engine-tuning sliders. **Off by default**, so a fresh install
     * (or an upgrade that never touched the sliders) runs the built-in defaults and the four sliders
     * are hidden. While it is off the stored slider values are ignored, not erased: they stay in the
     * store untouched, so turning this back on restores whatever the user had tuned.
     */
    val advancedTuning: Preference<Boolean> = preferenceStore.getBoolean("pref_ocr_advanced_tuning", false)

    /**
     * The tuning the engine must actually use: the stored slider values when [advancedTuning] is on,
     * the built-in defaults for the selected detector otherwise.
     *
     * This is the single place the opt-in is enforced. The engine and the cache signature read the
     * tuning through here, never through the individual slider preferences, so a stored but inactive
     * value cannot change a detection result or a cache key.
     */
    fun effectiveTuning(): OcrEngineTuning = if (advancedTuning.get()) {
        OcrEngineTuning(
            detPixelThreshold = detPixelThreshold.get(),
            detBoxThreshold = detBoxThreshold.get(),
            detUnclipRatio = detUnclipRatio.get(),
            recConfidenceFloor = recConfidenceFloor.get(),
        )
    } else {
        OcrEngineTuning.defaultsFor(resolveDetModel())
    }

    /**
     * Writes all four tuning preferences back to their built-in defaults (the settings screen's
     * "Reset to defaults" button). The stored values are written, not deleted, so this behaves the
     * same whether or not [advancedTuning] is on, and the sliders update immediately.
     */
    fun resetTuningToDefaults() {
        val defaults = OcrEngineTuning.defaultsFor(resolveDetModel())
        detPixelThreshold.set(defaults.detPixelThreshold)
        detBoxThreshold.set(defaults.detBoxThreshold)
        detUnclipRatio.set(defaults.detUnclipRatio)
        recConfidenceFloor.set(defaults.recConfidenceFloor)
    }

    /**
     * Recogniser weights: the manga fine-tune (default) or the stock PP-OCRv6 model. Stored as the
     * enum name so the value stays readable in the preference store.
     *
     * **The key and its default are unchanged** by the detector axis below, so a user who explicitly
     * picked [RecModelVariant.FINE_TUNED] or [RecModelVariant.STOCK] keeps that recogniser; only the
     * detector they never chose moves to the dialogue default.
     */
    val recModelVariant: Preference<String> = preferenceStore.getString(
        "pref_ocr_rec_model",
        RecModelVariant.DEFAULT.name,
    )

    fun resolveRecModel(): RecModelVariant = RecModelVariant.from(recModelVariant.get())

    /**
     * Detector weights: speech bubbles only (default) or speech bubbles + sound effects. Stored as
     * the enum name, like the recogniser.
     *
     * A **new** key, so every install that never chose a detector resolves to
     * [DetModelVariant.DEFAULT] (dialogue): older builds only had the axis-less releases and there
     * always ran the SFX detector, and nobody may silently keep it. The combined opt-in switch
     * (`DictionaryOcrPreferences`) is a different key and is not touched by this.
     */
    val detModelVariant: Preference<String> = preferenceStore.getString(
        "pref_ocr_det_model",
        DetModelVariant.DEFAULT.name,
    )

    fun resolveDetModel(): DetModelVariant = DetModelVariant.from(detModelVariant.get())

    /** The release that publishes exactly the selected detector × recogniser pair. */
    fun resolveModelRelease(): ModelRelease = ModelRelease.of(resolveDetModel(), resolveRecModel())

    /** File name the engine and downloader must use for the selected recogniser. */
    fun recModelFile(): String = resolveRecModel().recModel

    /** File name the engine and downloader must use for the selected detector (shared name). */
    fun detModelFile(): String = resolveDetModel().detModel

    // KUN -->
    // There is deliberately no `modelKey()` here any more: the engine must key its reload on what is
    // *installed* (`installedModelIdentity` in `paddle/ModelDownloader.kt`), not on the selection.
    // The detector file name is identical in every release, so a selection-derived key claimed a
    // release for bytes that need not be that release's.
    // KUN <--

    /**
     * The effective engine options for a page. [numThreads] is the device-derived thread count the
     * engine normally computes itself; the thresholds come from [effectiveTuning], so changing them
     * takes effect for every page processed afterwards, and an advanced-tuning opt-out falls back to
     * the built-in defaults whatever the sliders still store.
     */
    fun detectionOptions(numThreads: Int): PaddleOcrOptions {
        val tuning = effectiveTuning()
        return PaddleOcrOptions(
            thresh = tuning.detPixelThreshold.coerceIn(1, 99) / 100f,
            boxThresh = tuning.detBoxThreshold.coerceIn(1, 99) / 100f,
            unclipRatio = tuning.detUnclipRatio.coerceIn(100, 300) / 100f,
            recConfidenceFloor = tuning.recConfidenceFloor.coerceIn(0, 99) / 100f,
            numThreads = numThreads,
        )
    }

    /**
     * A suffix that distinguishes OCR results produced with different weights or non-default
     * thresholds, so changing either re-runs OCR instead of serving a stale cached page. The OCR
     * manager combines it with its engine-generation key.
     *
     * **The release tag is unconditional, so this is never empty.** It used to be `""` at the
     * defaults, and the pre-detector-axis default (`ocr-manga-v2`: the stock detector plus the
     * fine-tuned recogniser — the SFX detector was never a default) also produced `""`, so a page
     * OCR'd from one detector's weights was served as if it had been read by the other. Deriving
     * the tag from the resolved release (which encodes *both* axes) makes that collision impossible
     * to reintroduce, whatever future default is chosen.
     */
    fun cacheSignature(): String {
        // The tuning comes from `effectiveTuning()` and not from the sliders, so values stored while
        // advanced tuning is off neither change the key nor make an old cached page unusable.
        val tuning = effectiveTuning()
        return "${resolveModelRelease().release}t${tuning.detPixelThreshold}b${tuning.detBoxThreshold}" +
            "u${tuning.detUnclipRatio}r${tuning.recConfidenceFloor}"
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

// KUN -->
/**
 * The four engine-tuning values in the whole-number units the preferences store and the sliders
 * show (percentages; the unclip ratio as `140` = 1.40).
 *
 * The defaults are a *set* keyed by detector variant rather than four loose constants, so a future
 * detector fine-tune can carry its own numbers. **Today every variant shares [SHARED_DEFAULTS]** —
 * no per-model value has been measured, so none is invented here.
 */
data class OcrEngineTuning(
    val detPixelThreshold: Int,
    val detBoxThreshold: Int,
    val detUnclipRatio: Int,
    val recConfidenceFloor: Int,
) {
    companion object {
        private val SHARED_DEFAULTS = OcrEngineTuning(
            detPixelThreshold = OcrPreferences.DEFAULT_DET_PIXEL,
            detBoxThreshold = OcrPreferences.DEFAULT_DET_BOX,
            detUnclipRatio = OcrPreferences.DEFAULT_DET_UNCLIP,
            recConfidenceFloor = OcrPreferences.DEFAULT_REC_FLOOR,
        )

        /** The built-in tuning for [detector]. Every detector variant uses the same numbers today. */
        fun defaultsFor(detector: DetModelVariant): OcrEngineTuning = when (detector) {
            DetModelVariant.DIALOGUE, DetModelVariant.SFX -> SHARED_DEFAULTS
        }
    }
}
// KUN <--
