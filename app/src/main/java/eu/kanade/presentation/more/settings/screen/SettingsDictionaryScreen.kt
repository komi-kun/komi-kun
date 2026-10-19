// SPDX-License-Identifier: GPL-3.0-or-later
package eu.kanade.presentation.more.settings.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.dictionary.DictionaryAudioSettingsScreen
import eu.kanade.tachiyomi.ui.dictionary.DictionaryBackupImportScreen
import eu.kanade.tachiyomi.ui.dictionary.DictionaryImportCoordinator
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPopupSizing
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.dictionary.YomitanSettingsApplier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.core.dictionary.anki.AnkiPreferences
import mihon.core.dictionary.model.allDictionaryNames
import mihon.core.dictionary.yomitan.YomitanImportResult
import mihon.core.dictionary.yomitan.YomitanSettingsImport
import mihon.core.dictionary.yomitan.YomitanSettingsMapper
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Dictionary settings (02 §5.1): imported-dictionary management (SAF import/list/enable/delete),
 * per-profile selection, and the popup chrome group reused by [SettingsDictionaryPopupScreen].
 *
 * Manga-only adaptation of Chimahon's `SettingsDictionaryScreen`: anime/novel/word-audio groups and
 * the kanji-stroke-font auto-download are dropped.
 */
object SettingsDictionaryScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_category_dictionaries_and_audio

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val prefs = remember(context) { context.appGraph.dictionaryPreferences }
        val repository = remember(context) { context.appGraph.dictionaryRepository }
        val scope = rememberCoroutineScope()
        var refresh by remember { mutableIntStateOf(0) }

        var rawProfiles by remember { mutableStateOf(prefs.rawProfiles().get()) }
        var importMessage by remember { mutableStateOf<String?>(null) }
        var settingsMessage by remember { mutableStateOf<String?>(null) }
        var pendingSettings by remember { mutableStateOf<YomitanSettingsImport?>(null) }

        val settingsErrorText = stringResource(KMR.strings.pref_import_yomitan_settings_error)
        val settingsDoneText = stringResource(KMR.strings.pref_import_yomitan_settings_done)

        val dictionaries = remember(refresh) { allDictionaryNames(context) }
        val activeProfile = remember(rawProfiles, refresh) { prefs.profileStore.getActiveProfile() }
        val profileEntries = remember(rawProfiles) { prefs.profileStore.getProfiles().associate { it.id to it.name } }

        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                scope.launch {
                    val imported = withContext(Dispatchers.IO) {
                        uris.mapNotNull { uri ->
                            DictionaryImportCoordinator.importFromUri(context, uri, prefs, repository)?.title
                        }
                    }
                    importMessage = imported.lastOrNull()
                    refresh++
                }
            }
        }

        val settingsLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val text = context.contentResolver.openInputStream(uri)
                                ?.bufferedReader()
                                ?.use { it.readText() }
                            if (text.isNullOrBlank()) null else YomitanSettingsMapper.map(text)
                        }.getOrNull()
                    }
                    when (result) {
                        null -> settingsMessage = settingsErrorText
                        is YomitanImportResult.Invalid -> settingsMessage = "$settingsErrorText: ${result.reason}"
                        is YomitanImportResult.Imported -> pendingSettings = result.settings
                    }
                }
            }
        }

        // Preview/confirm dialog: the user sees exactly what will change before anything is written.
        pendingSettings?.let { pending ->
            AlertDialog(
                onDismissRequest = { pendingSettings = null },
                title = { Text(text = stringResource(KMR.strings.pref_import_yomitan_settings_title)) },
                text = { YomitanSettingsSummary(pending) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    YomitanSettingsApplier.apply(
                                        pending,
                                        prefs,
                                        AnkiPreferences(AndroidPreferenceStore(context)),
                                    )
                                }
                                pendingSettings = null
                                settingsMessage = settingsDoneText
                                rawProfiles = prefs.rawProfiles().get()
                                refresh++
                            }
                        },
                    ) { Text(text = stringResource(MR.strings.action_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingSettings = null }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_dictionary_profiles),
                preferenceItems = listOf(
                    Preference.PreferenceItem.BasicListPreference(
                        value = activeProfile.id,
                        entries = profileEntries,
                        title = stringResource(KMR.strings.pref_dictionary_profiles),
                        subtitleProvider = { id, entries -> entries[id].orEmpty() },
                        onValueChanged = { id ->
                            prefs.profileStore.setActiveProfile(id)
                            rawProfiles = prefs.rawProfiles().get()
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_dict_imported_list),
                preferenceItems = buildList {
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(KMR.strings.pref_import_dictionary),
                            subtitle = stringResource(KMR.strings.pref_import_dictionary_summ),
                            onClick = {
                                importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                            },
                        ),
                    )
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(KMR.strings.pref_import_yomitan_settings),
                            subtitle = stringResource(KMR.strings.pref_import_yomitan_settings_summ),
                            onClick = {
                                settingsLauncher.launch(
                                    arrayOf("application/json", "text/json", "text/plain", "*/*"),
                                )
                            },
                        ),
                    )
                    // KUN -->
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(KMR.strings.pref_import_yomitan_backup),
                            subtitle = stringResource(KMR.strings.pref_import_yomitan_backup_summ),
                            onClick = { navigator.push(DictionaryBackupImportScreen()) },
                        ),
                    )
                    // KUN <--
                    if (dictionaries.isEmpty()) {
                        add(
                            Preference.PreferenceItem.TextPreference(
                                title = stringResource(KMR.strings.pref_dict_none_imported),
                            ),
                        )
                    } else {
                        dictionaries.forEach { name ->
                            add(
                                Preference.PreferenceItem.TextPreference(
                                    title = name,
                                    subtitle = if (activeProfile.enabledDictionaries.isEmpty() || name in activeProfile.enabledDictionaries) {
                                        stringResource(KMR.strings.pref_dict_enabled_in_profile)
                                    } else {
                                        null
                                    },
                                ),
                            )
                            add(
                                Preference.PreferenceItem.TextPreference(
                                    title = stringResource(KMR.strings.pref_dict_delete),
                                    subtitle = name,
                                    onClick = {
                                        DictionaryImportCoordinator.delete(context, name, prefs)
                                        refresh++
                                    },
                                ),
                            )
                        }
                    }
                    importMessage?.let { message ->
                        add(Preference.PreferenceItem.TextPreference(title = message))
                    }
                    settingsMessage?.let { message ->
                        add(Preference.PreferenceItem.TextPreference(title = message))
                    }
                },
            ),
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_dict_auto_kana_conversion),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.autoKanaConversion(),
                        title = stringResource(KMR.strings.pref_dict_auto_kana_conversion),
                        subtitle = stringResource(KMR.strings.pref_dict_auto_kana_conversion_summary),
                    ),
                ),
            ),
            // KUN -->
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_category_dictionary_audio),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.audioAutoPlay(),
                        title = stringResource(KMR.strings.pref_dict_audio_autoplay),
                        subtitle = stringResource(KMR.strings.pref_dict_audio_autoplay_summary),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KMR.strings.pref_category_dictionary_audio),
                        subtitle = stringResource(KMR.strings.pref_dictionary_audio_summary),
                        onClick = { navigator.push(DictionaryAudioSettingsScreen()) },
                    ),
                ),
            ),
            // KUN <--
        )
    }

    /** Popup chrome group reused by [SettingsDictionaryPopupScreen] (02 §5.1). */
    @Composable
    fun popupPreferences(): List<Preference> {
        val context = LocalContext.current
        val prefs = remember(context) { context.appGraph.dictionaryPreferences }
        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_dict_popup_size),
                preferenceItems = listOf(
                    // KUN --> One "size" knob (a percentage of an adaptive, screen-relative
                    // baseline) instead of raw width/height dp, so it is a single change and the
                    // default grows with the screen (a tablet gets a bigger popup for free).
                    Preference.PreferenceItem.SliderPreference(
                        preference = prefs.popupSizePercent(),
                        title = stringResource(KMR.strings.pref_dict_popup_size_scale),
                        subtitle = stringResource(KMR.strings.pref_dict_popup_size_scale_summary),
                        valueRange = DictionaryPopupSizing.MIN_PERCENT..DictionaryPopupSizing.MAX_PERCENT,
                        valueText = { "$it%" },
                    ),
                    // KUN <--
                    Preference.PreferenceItem.SliderPreference(
                        preference = prefs.fontSize(),
                        title = stringResource(KMR.strings.pref_dict_popup_font_size),
                        valueRange = 10..32,
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_dict_appearance),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.groupTerms(),
                        title = stringResource(KMR.strings.pref_dict_group_terms),
                        subtitle = stringResource(KMR.strings.pref_dict_group_terms_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.groupPitches(),
                        title = stringResource(KMR.strings.pref_dict_group_pitches),
                        subtitle = stringResource(KMR.strings.pref_dict_group_pitches_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.showFrequencyAverage(),
                        title = stringResource(KMR.strings.pref_dict_show_frequency_average),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.showPitchDiagram(),
                        title = stringResource(KMR.strings.pref_dict_pitch_diagram),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.showPitchNumber(),
                        title = stringResource(KMR.strings.pref_dict_pitch_number),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.showPitchText(),
                        title = stringResource(KMR.strings.pref_dict_pitch_text),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_dict_content),
                preferenceItems = listOf(
                    // KUN -->
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.audioAutoPlay(),
                        title = stringResource(KMR.strings.pref_dict_audio_autoplay),
                        subtitle = stringResource(KMR.strings.pref_dict_audio_autoplay_summary),
                    ),
                    // KUN <--
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.popupSwipeToDismiss(),
                        title = stringResource(KMR.strings.pref_dict_popup_swipe_to_dismiss),
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        preference = prefs.popupSwipeThreshold(),
                        title = stringResource(KMR.strings.pref_dict_popup_swipe_threshold),
                        valueRange = 24..200,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.showNavigationButtons(),
                        title = stringResource(KMR.strings.pref_dict_show_navigation_buttons),
                        subtitle = stringResource(KMR.strings.pref_dict_show_navigation_buttons_summary),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KMR.strings.pref_dict_custom_css),
                        subtitle = stringResource(KMR.strings.pref_dict_custom_css_summary),
                    ),
                ),
            ),
        )
    }

    /** Anki mining group — phase 6 stub (02 §5.1). Wired in the Anki wave. */
    @Composable
    fun ankiPreferences(): List<Preference> = listOf(
        Preference.PreferenceGroup(
            title = stringResource(KMR.strings.pref_category_anki),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.pref_anki_settings_summary),
                    subtitle = stringResource(KMR.strings.anki_not_configured),
                ),
            ),
        ),
    )
}

/** Preview of a Yomitan settings import: warnings, then the applied and skipped keys. */
@Composable
private fun YomitanSettingsSummary(settings: YomitanSettingsImport) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        settings.warnings.forEach { warning ->
            Text(text = warning, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = stringResource(KMR.strings.pref_import_yomitan_settings_applied),
            style = MaterialTheme.typography.titleSmall,
        )
        settings.appliedReports.forEach { report ->
            Text(text = "${report.key} → ${report.detail}", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = stringResource(KMR.strings.pref_import_yomitan_settings_skipped),
            style = MaterialTheme.typography.titleSmall,
        )
        settings.skippedReports.forEach { report ->
            Text(text = "${report.key} (${report.detail})", style = MaterialTheme.typography.bodySmall)
        }
    }
}
