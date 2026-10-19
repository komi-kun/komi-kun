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
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.core.ocr.OcrEngineType
import mihon.core.ocr.OcrPreferences
import mihon.core.ocr.cache.OcrCacheManager
import mihon.core.ocr.paddle.ModelDownloadState
import mihon.core.ocr.paddle.VlDownloadState
import tachiyomi.core.common.ocr.ReaderOcrSource
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * OCR settings (contract §3.4, 03 §5.6). Owned by W3; W2 registers it in
 * `SettingsMainScreen`/`SettingsSearchScreen`.
 *
 * Only existing `KMR` keys are used. The settings/services are built from the app graph / default
 * `PreferenceStore` here rather than a ViewModel, since the screen is a stateless `SearchableSettings`.
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
    override fun getTitleRes(): StringResource = KMR.strings.pref_category_ocr

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
        val vlDownloadState by modelDownloader.vlState.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var refreshModelsCounter by remember { mutableStateOf(0) }

        // Runtime model-download status (contract §1.7). `isPaddleDownloaded` is a cheap synchronous
        // gate; the state flow adds live progress and makes a first-time failure visible instead of
        // silent (the download itself is triggered opt-in on Paddle selection below).
        val modelsReady = remember(downloadState, refreshModelsCounter) {
            (downloadState == ModelDownloadState.Completed || modelDownloader.isPaddleDownloaded) &&
                modelDownloader.modelsDir.exists()
        }
        val ppModelSize = remember(downloadState, refreshModelsCounter) {
            modelDownloader.getModelsSizeReadable()
        }
        val modelStatus = when (val state = downloadState) {
            // Surface the actual reason instead of a constant "Error" (F8).
            is ModelDownloadState.Failed -> stringResource(KMR.strings.ocr_status_error_reason, state.message)
            is ModelDownloadState.Downloading ->
                stringResource(KMR.strings.ocr_running) + " (${state.downloaded}/${state.total})"
            ModelDownloadState.Completed -> stringResource(KMR.strings.ocr_ready)
            ModelDownloadState.Idle ->
                if (modelsReady) stringResource(KMR.strings.ocr_ready) else stringResource(KMR.strings.ocr_not_ready)
        }

        val isVlDownloaded = remember(vlDownloadState, refreshModelsCounter) {
            modelDownloader.isVlDownloaded
        }
        val vlModelSize = remember(vlDownloadState, refreshModelsCounter) {
            modelDownloader.getVlModelSizeReadable()
        }
        val vlStatus = when (val state = vlDownloadState) {
            is VlDownloadState.Downloading -> {
                val downloaded = state.bytesDownloaded / (1024.0 * 1024.0)
                val total = state.totalBytes / (1024.0 * 1024.0)
                val pct = if (state.totalBytes > 0) ((state.bytesDownloaded * 100) / state.totalBytes).toInt() else 0
                stringResource(
                    KMR.strings.ocr_paddlevl_status_downloading,
                    "%.1f MB".format(downloaded),
                    if (state.totalBytes > 0) "%.1f MB".format(total) else "...",
                    pct,
                )
            }
            is VlDownloadState.Failed -> stringResource(KMR.strings.ocr_status_error_reason, state.message)
            VlDownloadState.Completed, VlDownloadState.Idle -> {
                if (isVlDownloaded) {
                    stringResource(KMR.strings.ocr_paddlevl_status_ready, vlModelSize ?: "")
                } else {
                    stringResource(KMR.strings.ocr_paddlevl_status_not_downloaded)
                }
            }
        }

        var cacheSizeText by remember { mutableStateOf<String?>(null) }
        var showClearDialog by remember { mutableStateOf(false) }
        var showDeletePpDialog by remember { mutableStateOf(false) }
        var showDeleteVlDialog by remember { mutableStateOf(false) }
        var isClearing by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            cacheSizeText = withIOContext { cacheManager.readableSize() }
        }

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text(text = stringResource(KMR.strings.pref_ocr_clear_cache)) },
                text = { Text(text = stringResource(KMR.strings.confirm_clear_ocr_cache)) },
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
                        Text(text = stringResource(KMR.strings.action_clear_ocr_cache))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        if (showDeletePpDialog) {
            AlertDialog(
                onDismissRequest = { showDeletePpDialog = false },
                title = { Text(text = stringResource(KMR.strings.pref_ocr_delete_models)) },
                text = { Text(text = stringResource(KMR.strings.confirm_delete_ocr_models)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeletePpDialog = false
                            modelDownloader.deleteModels()
                            refreshModelsCounter++
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeletePpDialog = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        if (showDeleteVlDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteVlDialog = false },
                title = { Text(text = stringResource(KMR.strings.pref_ocr_paddlevl_delete)) },
                text = { Text(text = stringResource(KMR.strings.confirm_delete_paddlevl_model)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteVlDialog = false
                            modelDownloader.deleteVlModel()
                            preferences.paddleVlModelPath.set("")
                            refreshModelsCounter++
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteVlDialog = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        return listOf(
            // Source policy.
            Preference.PreferenceItem.ListPreference(
                preference = preferences.defaultSource,
                entries = mapOf(
                    ReaderOcrSource.AUTOMATIC.name to stringResource(KMR.strings.ocr_source_automatic),
                    ReaderOcrSource.MOKURO.name to stringResource(KMR.strings.ocr_source_mokuro),
                    ReaderOcrSource.PADDLE.name to stringResource(KMR.strings.ocr_source_paddle),
                ),
                title = stringResource(KMR.strings.pref_category_ocr),
                onValueChanged = { value ->
                    if (value == ReaderOcrSource.PADDLE.name) {
                        // Runtime, user-opt-in, checksum-verified model download (contract §1.7).
                        modelDownloader.triggerPaddleDownload()
                    }
                    true
                },
            ),

            // Engine selection: PP-OCRv6 Small (On-Device) vs PaddleOCR-VL-1.6 (VLM).
            Preference.PreferenceItem.ListPreference(
                preference = preferences.ocrEngineType,
                entries = mapOf(
                    OcrEngineType.PP_OCRV6_SMALL.id to stringResource(KMR.strings.pref_ocr_model_ppocrv6),
                    OcrEngineType.PADDLEOCR_VL.id to stringResource(KMR.strings.pref_ocr_model_paddlevl),
                ),
                title = stringResource(KMR.strings.pref_ocr_engine_model),
                subtitle = "%s",
            ),

            // PaddleOCR-VL settings.
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_ocr_paddlevl_settings),
                preferenceItems = buildList {
                    add(
                        Preference.PreferenceItem.ListPreference(
                            preference = preferences.paddleVlExecutionMode,
                            entries = mapOf(
                                OcrPreferences.MODE_ON_DEVICE to stringResource(KMR.strings.pref_ocr_paddlevl_mode_on_device),
                                OcrPreferences.MODE_SERVER to stringResource(KMR.strings.pref_ocr_paddlevl_mode_server),
                            ),
                            title = stringResource(KMR.strings.pref_ocr_paddlevl_mode),
                            subtitle = "%s",
                        ),
                    )
                    add(
                        Preference.PreferenceItem.EditTextPreference(
                            preference = preferences.paddleVlDownloadUrl,
                            title = stringResource(KMR.strings.pref_ocr_paddlevl_download_url),
                            subtitle = stringResource(KMR.strings.pref_ocr_paddlevl_download_url_summary),
                        ),
                    )
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(KMR.strings.pref_ocr_paddlevl_status),
                            subtitle = vlStatus,
                            onClick = if (vlDownloadState is VlDownloadState.Downloading) {
                                null
                            } else {
                                {
                                    modelDownloader.triggerVlDownload(preferences.paddleVlDownloadUrl.get()) { file ->
                                        preferences.paddleVlModelPath.set(file.absolutePath)
                                        refreshModelsCounter++
                                    }
                                }
                            },
                        ),
                    )
                    if (isVlDownloaded) {
                        add(
                            Preference.PreferenceItem.TextPreference(
                                title = stringResource(KMR.strings.pref_ocr_paddlevl_delete),
                                subtitle = stringResource(KMR.strings.pref_ocr_paddlevl_delete_summary),
                                onClick = { showDeleteVlDialog = true },
                            ),
                        )
                    } else {
                        add(
                            Preference.PreferenceItem.TextPreference(
                                title = stringResource(KMR.strings.pref_ocr_paddlevl_download),
                                subtitle = stringResource(KMR.strings.pref_ocr_paddlevl_download_summary),
                                onClick = if (vlDownloadState is VlDownloadState.Downloading) {
                                    null
                                } else {
                                    {
                                        modelDownloader.triggerVlDownload(preferences.paddleVlDownloadUrl.get()) { file ->
                                            preferences.paddleVlModelPath.set(file.absolutePath)
                                            refreshModelsCounter++
                                        }
                                    }
                                },
                            ),
                        )
                    }
                    add(
                        Preference.PreferenceItem.EditTextPreference(
                            preference = preferences.paddleVlModelPath,
                            title = stringResource(KMR.strings.pref_ocr_paddlevl_model_path),
                            subtitle = stringResource(KMR.strings.pref_ocr_paddlevl_model_path_summary),
                        ),
                    )
                    add(
                        Preference.PreferenceItem.EditTextPreference(
                            preference = preferences.paddleVlEndpoint,
                            title = stringResource(KMR.strings.pref_ocr_paddlevl_endpoint),
                            subtitle = stringResource(KMR.strings.pref_ocr_paddlevl_endpoint_summary),
                        ),
                    )
                    add(
                        Preference.PreferenceItem.SwitchPreference(
                            preference = preferences.paddleVlFallback,
                            title = stringResource(KMR.strings.pref_ocr_paddlevl_fallback),
                            subtitle = stringResource(KMR.strings.pref_ocr_paddlevl_fallback_summary),
                        ),
                    )
                },
            ),

            // PP-OCRv6 model status and management.
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_ocr_model_status),
                preferenceItems = buildList {
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(KMR.strings.pref_ocr_model_status),
                            subtitle = if (modelsReady && ppModelSize != null) {
                                "$modelStatus ($ppModelSize)"
                            } else {
                                modelStatus
                            },
                            onClick = if (downloadState is ModelDownloadState.Downloading) {
                                null
                            } else {
                                { modelDownloader.triggerPaddleDownload() }
                            },
                        ),
                    )
                    if (modelsReady) {
                        add(
                            Preference.PreferenceItem.TextPreference(
                                title = stringResource(KMR.strings.pref_ocr_delete_models),
                                subtitle = stringResource(KMR.strings.pref_ocr_delete_models_summary),
                                onClick = { showDeletePpDialog = true },
                            ),
                        )
                    } else {
                        add(
                            Preference.PreferenceItem.TextPreference(
                                title = stringResource(KMR.strings.pref_ocr_download_models),
                                subtitle = stringResource(KMR.strings.pref_ocr_download_models_summary),
                                onClick = if (downloadState is ModelDownloadState.Downloading) {
                                    null
                                } else {
                                    { modelDownloader.triggerPaddleDownload() }
                                },
                            ),
                        )
                    }
                },
            ),

            // Overlay master switch: the same key the reader gates on (fix B3).
            Preference.PreferenceItem.SwitchPreference(
                preference = readerPreferences.ocrOverlayEnabled,
                title = stringResource(KMR.strings.pref_ocr_overlay_enabled),
            ),

            // Overlay behaviour.
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_category_ocr_boxes),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = readerPreferences.ocrOutlineVisible,
                        title = stringResource(KMR.strings.pref_show_ocr_outlines),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = readerPreferences.ocrTextOverlayVisible,
                        title = stringResource(KMR.strings.pref_show_ocr_text),
                        subtitle = stringResource(KMR.strings.pref_show_ocr_text_summary),
                    ),
                    // Overlay text size: the calibrated default matches the printed lettering, and
                    // manga fonts vary enough that the reader needs a nudge (KUN).
                    Preference.PreferenceItem.SliderPreference(
                        preference = readerPreferences.ocrTextScale,
                        title = stringResource(KMR.strings.pref_ocr_text_scale),
                        subtitle = stringResource(KMR.strings.pref_ocr_text_scale_summary),
                        valueRange = 50..150,
                        valueText = { "$it%" },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = readerPreferences.ocrTwoFingerGestureEnabled,
                        title = stringResource(KMR.strings.pref_ocr_two_finger_gesture),
                        subtitle = stringResource(KMR.strings.pref_ocr_two_finger_gesture_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.autoOnDownload,
                        title = stringResource(KMR.strings.pref_ocr_auto_on_download),
                        subtitle = stringResource(KMR.strings.pref_ocr_auto_on_download_summary),
                    ),
                ),
            ),

            // KUN -->
            // Engine tuning. The three detection sliders map onto the native `nativeProcess`
            // thresholds (no artefact change); the recognition floor is applied on the per-line
            // score the engine already emits. Defaults reproduce the previous hard-coded values.
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_ocr_detection),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SliderPreference(
                        preference = preferences.detBoxThreshold,
                        title = stringResource(KMR.strings.pref_ocr_det_box_threshold),
                        subtitle = stringResource(KMR.strings.pref_ocr_det_box_threshold_summary),
                        valueRange = 5..95,
                        valueText = { "$it%" },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        preference = preferences.detPixelThreshold,
                        title = stringResource(KMR.strings.pref_ocr_det_pixel_threshold),
                        subtitle = stringResource(KMR.strings.pref_ocr_det_pixel_threshold_summary),
                        valueRange = 5..95,
                        valueText = { "$it%" },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        preference = preferences.detUnclipRatio,
                        title = stringResource(KMR.strings.pref_ocr_det_unclip_ratio),
                        subtitle = stringResource(KMR.strings.pref_ocr_det_unclip_ratio_summary),
                        valueRange = 100..300,
                        valueText = { "%.2f".format(it / 100f) },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        preference = preferences.recConfidenceFloor,
                        title = stringResource(KMR.strings.pref_ocr_rec_confidence_floor),
                        subtitle = stringResource(KMR.strings.pref_ocr_rec_confidence_floor_summary),
                        valueRange = 0..95,
                        valueText = { if (it == 0) "off" else "$it%" },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KMR.strings.pref_ocr_detection_apply_hint),
                    ),
                ),
            ),
            // KUN <--

            // Cache.
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KMR.strings.pref_ocr_cache_size),
                subtitle = cacheSizeText ?: "...",
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KMR.strings.pref_ocr_clear_cache),
                onClick = { showClearDialog = true },
            ),
        )
    }
}
// KUN <--
