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
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.core.ocr.OcrPreferences
import mihon.core.ocr.cache.OcrCacheManager
import mihon.core.ocr.paddle.ModelDownloader
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
        val modelDownloader = remember { ModelDownloader(context, context.appGraph.networkHelper.client, json) }
        val scope = rememberCoroutineScope()

        var cacheSizeText by remember { mutableStateOf<String?>(null) }
        var showClearDialog by remember { mutableStateOf(false) }
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
