// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package eu.kanade.presentation.more.settings.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.reader.OcrDisplayScale
import eu.kanade.tachiyomi.data.ocr.OcrRuntimePolicy
import eu.kanade.tachiyomi.data.ocr.OcrWorker
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.core.ocr.DetModelVariant
import mihon.core.ocr.OcrDetResolution
import mihon.core.ocr.OcrPreferences
import mihon.core.ocr.RecModelVariant
import mihon.core.ocr.cache.OcrCacheManager
import mihon.core.ocr.paddle.ModelDownloadState
import tachiyomi.core.common.ocr.ReaderOcrSource
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.kun.KUNMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * OCR settings (contract §3.4, 03 §5.6). Owned by W3; W2 registers it in
 * `SettingsMainScreen`/`SettingsSearchScreen`.
 *
 * All user-visible text comes from the `i18n-kun` module (`KUNMR`). The settings/services are built
 * from the app graph / default `PreferenceStore` here rather than a ViewModel, since the screen is a
 * stateless `SearchableSettings`.
 *
 * **Single source of truth (fix B3).** The reader-facing display toggles bind to `ReaderPreferences`
 * (the same keys the GPL-free reader gates/draws from), while the queue/source policy binds to
 * `OcrPreferences` (the same keys `:core:ocr` services read). The two never overlap, so enabling the
 * overlay here actually enables it in the reader. Selecting Paddle triggers the checksum-verified,
 * opt-in model download (contract §1.7).
 */
object SettingsOcrScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes(): StringResource = KUNMR.strings.pref_category_ocr

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val json = remember { context.appGraph.json }
        val preferences = remember { OcrPreferences(AndroidPreferenceStore(context)) }
        // Reader-facing toggles live on ReaderPreferences so the reader (GPL-free, §1.3) reads the
        // same keys this screen writes — no split-brain overlay preference (fix B3).
        val readerPreferences = remember { ReaderPreferences(AndroidPreferenceStore(context)) }
        val cacheManager = remember { OcrCacheManager(context, json) }
        // The graph-scoped singleton (OcrBindings), NOT a screen-local instance: one ModelDownloader
        // owns `state`/`isPaddleDownloaded` and the single-flight gate, so the CTA and the status-row
        // retry below coalesce into the same run (F1).
        val modelDownloader = context.appGraph.modelDownloader
        val downloadState by modelDownloader.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        // KUN -->
        // The combined dictionary/OCR opt-in. This screen stays reachable while the feature is off —
        // it owns one of the two master switches — but everything that can act (model download, cache
        // clearing, engine tuning) is hidden until the master is on, so this screen is not a backdoor.
        val dictionaryOcrPreferences = context.appGraph.dictionaryOcrPreferences
        val featureEnabled by dictionaryOcrPreferences.enabled.collectAsState()
        // Advanced tuning is opt-in: while it is off the engine runs the built-in defaults and the
        // four sliders are hidden. Collected (not just read) so toggling the switch or pressing
        // "Reset to defaults" re-renders the group immediately.
        val advancedTuningEnabled by preferences.advancedTuning.collectAsState()
        // KUN <--

        // Runtime model-download status (contract §1.7). `isPaddleDownloaded` is a cheap synchronous
        // gate; the state flow adds live progress and makes a first-time failure visible instead of
        // silent (the download itself is triggered opt-in on Paddle selection below).
        val modelsReady = remember(downloadState) {
            downloadState == ModelDownloadState.Completed || modelDownloader.isPaddleDownloaded
        }
        val modelStatus = when (val state = downloadState) {
            // Surface the actual reason instead of a constant "Error" (F8).
            is ModelDownloadState.Failed -> stringResource(KUNMR.strings.ocr_status_error_reason, state.message)
            is ModelDownloadState.Downloading ->
                stringResource(KUNMR.strings.ocr_download_progress, state.downloaded, state.total)
            ModelDownloadState.Completed -> stringResource(KUNMR.strings.ocr_ready)
            ModelDownloadState.Idle ->
                if (modelsReady) stringResource(KUNMR.strings.ocr_ready) else stringResource(KUNMR.strings.ocr_not_ready)
        }

        // KUN -->
        // A **completed download is a run start**. The queue defers while the selected release is
        // not installed (`OcrWorker.doWork` returns success and leaves the tasks `PENDING`), and the
        // launch/rotation resume in `MainActivity` is keyed on the switch, not on the download — so
        // without this the first successful download left every queued chapter waiting until the app
        // was restarted. Same guard as that resume (`OcrRuntimePolicy.shouldResumeQueue`), so an
        // empty, error-only or waiting-download queue — and a download that finished on a superseded
        // selection — still starts nothing, and the worker's foreground notification is never raised
        // for a run that would find nothing to do. Only the `Completed` transition acts.
        val queueWorkProbe = remember(context) { context.appGraph.ocrQueueWorkProbe }
        LaunchedEffect(downloadState) {
            if (downloadState != ModelDownloadState.Completed) return@LaunchedEffect
            // Both inputs are I/O: the persisted queue JSON and the installed-release marker.
            val runnableWork = withIOContext { queueWorkProbe.hasRunnableWork() }
            val installedModelsReady = withIOContext { modelDownloader.isPaddleDownloaded }
            if (OcrRuntimePolicy.shouldResumeQueue(featureEnabled, runnableWork, installedModelsReady)) {
                OcrWorker.start(context)
            }
        }
        // KUN <--

        var cacheSizeText by remember { mutableStateOf<String?>(null) }
        var showClearDialog by remember { mutableStateOf(false) }
        var isClearing by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            cacheSizeText = withIOContext { cacheManager.readableSize() }
        }

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text(text = stringResource(KUNMR.strings.pref_ocr_clear_cache)) },
                text = { Text(text = stringResource(KUNMR.strings.confirm_clear_ocr_cache)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearDialog = false
                            if (!isClearing) {
                                isClearing = true
                                scope.launch {
                                    withIOContext { cacheManager.clear() }
                                    cacheSizeText = withIOContext { cacheManager.readableSize() }
                                    isClearing = false
                                }
                            }
                        },
                    ) {
                        Text(text = stringResource(KUNMR.strings.action_clear_ocr_cache))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        val variantSummary = stringResource(KUNMR.strings.pref_ocr_model_variant_summary)
        val detectorSummary = stringResource(KUNMR.strings.pref_ocr_det_model_summary)

        return listOf(
            // KUN -->
            // Master switch first: visible whether the feature is on or off, so this screen can
            // always turn the combined feature back on (it is one of the two entry points).
            Preference.PreferenceItem.SwitchPreference(
                preference = dictionaryOcrPreferences.enabled,
                title = stringResource(KUNMR.strings.pref_dictionary_ocr_enabled),
                subtitle = stringResource(KUNMR.strings.pref_dictionary_ocr_enabled_summary),
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KUNMR.strings.pref_dictionary_ocr_disabled_hint),
                visible = !featureEnabled,
            ),
            // KUN <--

            // Source policy.
            Preference.PreferenceItem.ListPreference(
                preference = preferences.defaultSource,
                entries = mapOf(
                    ReaderOcrSource.AUTOMATIC.name to stringResource(KUNMR.strings.ocr_source_automatic),
                    ReaderOcrSource.MOKURO.name to stringResource(KUNMR.strings.ocr_source_mokuro),
                    ReaderOcrSource.PADDLE.name to stringResource(KUNMR.strings.ocr_source_paddle),
                ),
                title = stringResource(KUNMR.strings.pref_category_ocr),
                // KUN -->
                visible = featureEnabled,
                // KUN <--
                onValueChanged = { value ->
                    if (value == ReaderOcrSource.PADDLE.name) {
                        // Runtime, user-opt-in, checksum-verified model download (contract §1.7).
                        modelDownloader.triggerPaddleDownload()
                    }
                    true
                },
            ),

            // Detector weights: speech bubbles only (default) or bubbles + sound effects. The SFX
            // fine-tune finds onomatopoeia the stock detector misses but can split a bubble's lines
            // and merge adjacent bubbles, so dialogue is the default and SFX is opt-in. Switching
            // releases invalidates the installed-release marker, so the selected weights are
            // downloaded; the engine keys its reload on what is installed on disk (marker + file
            // identity), so no stale detector survives.
            Preference.PreferenceItem.ListPreference(
                preference = preferences.detModelVariant,
                entries = mapOf(
                    DetModelVariant.DIALOGUE.name to stringResource(KUNMR.strings.ocr_det_dialogue),
                    DetModelVariant.SFX.name to stringResource(KUNMR.strings.ocr_det_sfx),
                ),
                title = stringResource(KUNMR.strings.pref_ocr_det_model),
                subtitleProvider = { value, entries -> "${entries[value]} · $detectorSummary" },
                // KUN -->
                visible = featureEnabled,
                // KUN <--
                onValueChanged = {
                    modelDownloader.triggerPaddleDownload()
                    true
                },
            ),

            // Recogniser weights: the manga fine-tune (default) or the stock PP-OCRv6 model.
            // Switching releases invalidates the cached manifest, so trigger a download of the
            // selected variant; the engine swaps weights on the next page and cached OCR results
            // are keyed by the variant, so nothing stale is served.
            Preference.PreferenceItem.ListPreference(
                preference = preferences.recModelVariant,
                entries = mapOf(
                    RecModelVariant.FINE_TUNED.name to stringResource(KUNMR.strings.ocr_model_finetuned),
                    RecModelVariant.STOCK.name to stringResource(KUNMR.strings.ocr_model_stock),
                ),
                title = stringResource(KUNMR.strings.pref_ocr_model_variant),
                subtitleProvider = { value, entries -> "${entries[value]} · $variantSummary" },
                // KUN -->
                visible = featureEnabled,
                // KUN <--
                onValueChanged = {
                    modelDownloader.triggerPaddleDownload()
                    true
                },
            ),

            // Model download status (contract §1.7): a first-time download's progress and any
            // failure are surfaced here. Tapping retries — a failed run releases the single-flight
            // gate, so re-tapping actually re-runs instead of being coalesced away (F8).
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KUNMR.strings.pref_ocr_model_status),
                subtitle = modelStatus,
                // KUN -->
                visible = featureEnabled,
                // KUN <--
                onClick = { modelDownloader.triggerPaddleDownload() },
            ),

            // Overlay master switch: the same key the reader gates on (fix B3). The combined
            // dictionary/OCR switch above is the outer gate; this one only controls the overlay
            // while the feature as a whole is on.
            Preference.PreferenceItem.SwitchPreference(
                preference = readerPreferences.ocrOverlayEnabled,
                title = stringResource(KUNMR.strings.pref_ocr_overlay_enabled),
                // KUN -->
                visible = featureEnabled,
                // KUN <--
            ),

            // Overlay behaviour.
            Preference.PreferenceGroup(
                title = stringResource(KUNMR.strings.pref_category_ocr_boxes),
                // KUN -->
                visible = featureEnabled,
                // KUN <--
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = readerPreferences.ocrOutlineVisible,
                        title = stringResource(KUNMR.strings.pref_show_ocr_outlines),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = readerPreferences.ocrTextOverlayVisible,
                        title = stringResource(KUNMR.strings.pref_show_ocr_text),
                        subtitle = stringResource(KUNMR.strings.pref_show_ocr_text_summary),
                    ),
                    // Overlay text size: the calibrated default matches the printed lettering, and
                    // manga fonts vary enough that the reader needs a nudge (KUN). Range from
                    // `OcrDisplayScale`, the same object `OcrOverlay` converts with, so the slider
                    // cannot offer a value the drawing clamps away.
                    Preference.PreferenceItem.SliderPreference(
                        preference = readerPreferences.ocrTextScale,
                        title = stringResource(KUNMR.strings.pref_ocr_text_scale),
                        subtitle = stringResource(KUNMR.strings.pref_ocr_text_scale_summary),
                        valueRange = OcrDisplayScale.PERCENT_RANGE,
                        valueText = { "$it%" },
                    ),
                    // KUN -->
                    // Passive box size (`ReaderPreferences.ocrBoxScale`, default 100). Same 50–150 %
                    // range and percent readout as the text size right above it. The range and the
                    // percent → factor conversion live in `OcrDisplayScale`, which `OcrOverlay` draws
                    // with, so the slider cannot offer a value the drawing clamps away.
                    Preference.PreferenceItem.SliderPreference(
                        preference = readerPreferences.ocrBoxScale,
                        title = stringResource(KUNMR.strings.pref_dict_ocr_box_scale),
                        subtitle = stringResource(KUNMR.strings.pref_ocr_box_scale_summary),
                        valueRange = OcrDisplayScale.PERCENT_RANGE,
                        valueText = { "$it%" },
                    ),
                    // KUN <--
                    Preference.PreferenceItem.SwitchPreference(
                        preference = readerPreferences.ocrTwoFingerGestureEnabled,
                        title = stringResource(KUNMR.strings.pref_ocr_two_finger_gesture),
                        subtitle = stringResource(KUNMR.strings.pref_ocr_two_finger_gesture_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.autoOnDownload,
                        title = stringResource(KUNMR.strings.pref_ocr_auto_on_download),
                        subtitle = stringResource(KUNMR.strings.pref_ocr_auto_on_download_summary),
                    ),
                ),
            ),

            // KUN -->
            // Engine tuning. The three detection sliders map onto the native `nativeProcess`
            // thresholds (no artefact change); the recognition floor is applied on the per-line
            // score the engine already emits. Defaults reproduce the previous hard-coded values.
            //
            // Behind its own opt-in switch: while `advancedTuning` is off the engine uses the
            // built-in defaults regardless of what the sliders store, and the sliders (plus the
            // reset button) are hidden. The stored values are kept, so turning it back on restores
            // the user's tuning.
            Preference.PreferenceGroup(
                title = stringResource(KUNMR.strings.pref_ocr_detection),
                // KUN -->
                visible = featureEnabled,
                // KUN <--
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.advancedTuning,
                        title = stringResource(KUNMR.strings.pref_ocr_advanced_tuning),
                        subtitle = stringResource(KUNMR.strings.pref_ocr_advanced_tuning_summary),
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        preference = preferences.detBoxThreshold,
                        title = stringResource(KUNMR.strings.pref_ocr_det_box_threshold),
                        subtitle = stringResource(KUNMR.strings.pref_ocr_det_box_threshold_summary),
                        valueRange = 5..95,
                        valueText = { "$it%" },
                        visible = advancedTuningEnabled,
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        preference = preferences.detPixelThreshold,
                        title = stringResource(KUNMR.strings.pref_ocr_det_pixel_threshold),
                        subtitle = stringResource(KUNMR.strings.pref_ocr_det_pixel_threshold_summary),
                        valueRange = 5..95,
                        valueText = { "$it%" },
                        visible = advancedTuningEnabled,
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        preference = preferences.detUnclipRatio,
                        title = stringResource(KUNMR.strings.pref_ocr_det_unclip_ratio),
                        subtitle = stringResource(KUNMR.strings.pref_ocr_det_unclip_ratio_summary),
                        valueRange = 100..300,
                        valueText = { "%.2f".format(it / 100f) },
                        visible = advancedTuningEnabled,
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        preference = preferences.recConfidenceFloor,
                        title = stringResource(KUNMR.strings.pref_ocr_rec_confidence_floor),
                        subtitle = stringResource(KUNMR.strings.pref_ocr_rec_confidence_floor_summary),
                        valueRange = 0..95,
                        valueText = { if (it == 0) "off" else "$it%" },
                        visible = advancedTuningEnabled,
                    ),
                    // KUN -->
                    // Detector resolution: the longest side the detector works on (960 / 1280 /
                    // 1600 / auto by page size). A list, not a slider: the four states are discrete
                    // engine modes, and `Auto` is a rule rather than a number. Part of the advanced
                    // group, so it is hidden and inactive while `advancedTuning` is off — the same
                    // opt-in the four sliders above live behind.
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.detResolution,
                        entries = mapOf(
                            OcrDetResolution.DEFAULT.name to stringResource(KUNMR.strings.pref_ocr_det_resolution_default),
                            OcrDetResolution.HIGH.name to stringResource(KUNMR.strings.pref_ocr_det_resolution_high),
                            OcrDetResolution.VERY_HIGH.name to stringResource(KUNMR.strings.pref_ocr_det_resolution_very_high),
                            OcrDetResolution.AUTO.name to stringResource(KUNMR.strings.pref_ocr_det_resolution_auto),
                        ),
                        title = stringResource(KUNMR.strings.pref_ocr_det_resolution),
                        subtitleProvider = { value, entries ->
                            "${entries[value]} · " + stringResource(KUNMR.strings.pref_ocr_det_resolution_summary)
                        },
                        visible = advancedTuningEnabled,
                    ),
                    // KUN <--
                    // Writes the four sliders back to the built-in defaults. The sliders are bound to
                    // the same `Preference` instances this writes, so they re-render from the new
                    // values without any local state; no confirmation is needed because the values
                    // are one toggle / four drags away again.
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KUNMR.strings.pref_ocr_tuning_reset),
                        visible = advancedTuningEnabled,
                        onClick = { preferences.resetTuningToDefaults() },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KUNMR.strings.pref_ocr_detection_apply_hint),
                    ),
                ),
            ),
            // KUN <--

            // Cache.
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KUNMR.strings.pref_ocr_cache_size),
                subtitle = cacheSizeText ?: "...",
                // KUN -->
                visible = featureEnabled,
                // KUN <--
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KUNMR.strings.pref_ocr_clear_cache),
                // KUN -->
                visible = featureEnabled,
                // KUN <--
                onClick = { showClearDialog = true },
            ),
        )
    }
}
// KUN <--
