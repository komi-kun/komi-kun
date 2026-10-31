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
import eu.kanade.tachiyomi.ui.dictionary.DictionaryImportFailure
import eu.kanade.tachiyomi.ui.dictionary.DictionaryImportOutcome
import eu.kanade.tachiyomi.ui.dictionary.DictionaryManagementScreen
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPopupSizing
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.dictionary.ImportedDictionary
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
import tachiyomi.i18n.kun.KUNMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

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
    override fun getTitleRes() = KUNMR.strings.pref_category_dictionaries_and_audio

    // KUN -->
    /**
     * The combined dictionaries/OCR switch plus the "everything below is hidden" note. Every entry
     * point of the dictionary feature (this screen, popup chrome, Anki) starts with it, so the
     * feature can always be turned back on from a screen that is still reachable while it is off.
     */
    @Composable
    private fun masterPreferences(): List<Preference> {
        val context = LocalContext.current
        val dictionaryOcrPreferences = remember(context) { context.appGraph.dictionaryOcrPreferences }
        val featureEnabled by dictionaryOcrPreferences.enabled.collectAsState()
        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = dictionaryOcrPreferences.enabled,
                title = stringResource(KUNMR.strings.pref_dictionary_ocr_enabled),
                subtitle = stringResource(KUNMR.strings.pref_dictionary_ocr_enabled_summary),
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KUNMR.strings.pref_dictionary_ocr_disabled_hint),
                visible = !featureEnabled,
            ),
        )
    }
    // KUN <--

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val prefs = remember(context) { context.appGraph.dictionaryPreferences }
        // KUN -->
        val uiPreferences = remember(context) { context.appGraph.uiPreferences }
        // KUN <--
        // KUN -->
        // Deferred, not resolved in composition: `dictionaryRepository` constructs the GPL engine,
        // whose initialiser `dlopen`s `libhoshidicts_jni`. This screen — and Settings search, which
        // composes every enabled screen's preferences — must not pay for that while the feature is
        // off, so the only reader is the import callback below, which the gated rows alone reach.
        val repository by remember(context) { lazy { context.appGraph.dictionaryRepository } }
        // KUN <--
        val scope = rememberCoroutineScope()
        var refresh by remember { mutableIntStateOf(0) }

        var rawProfiles by remember { mutableStateOf(prefs.rawProfiles().get()) }
        var imported by remember { mutableStateOf<ImportedDictionary?>(null) }
        var importFailure by remember { mutableStateOf<DictionaryImportFailure?>(null) }
        var settingsMessage by remember { mutableStateOf<String?>(null) }
        var pendingSettings by remember { mutableStateOf<YomitanSettingsImport?>(null) }

        // KUN -->
        // The combined dictionary/OCR opt-in. This screen stays reachable while the feature is off —
        // it owns one of the two master switches — but the import/manage/audio actions below are
        // hidden until the master is on, so the screen is not a backdoor into the feature.
        val dictionaryOcrPreferences = remember(context) { context.appGraph.dictionaryOcrPreferences }
        val featureEnabled by dictionaryOcrPreferences.enabled.collectAsState()
        // KUN <--

        val settingsErrorText = stringResource(KUNMR.strings.pref_import_yomitan_settings_error)
        val settingsDoneText = stringResource(KUNMR.strings.pref_import_yomitan_settings_done)

        val dictionaries = remember(refresh, featureEnabled) {
            // KUN -->
            // A storage listing (external files, SAF tree) is part of the on-path screen only: while
            // the feature is off the only rows are the master switch and its hint.
            if (featureEnabled) allDictionaryNames(context) else emptyList()
            // KUN <--
        }
        val activeProfile = remember(rawProfiles, refresh) { prefs.profileStore.getActiveProfile() }
        val profileEntries = remember(rawProfiles) { prefs.profileStore.getProfiles().associate { it.id to it.name } }

        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                scope.launch {
                    val outcomes = withContext(Dispatchers.IO) {
                        uris.map { uri ->
                            DictionaryImportCoordinator.importFromUri(context, uri, prefs, repository)
                        }
                    }
                    imported = outcomes.filterIsInstance<DictionaryImportOutcome.Imported>()
                        .lastOrNull()
                        ?.dictionary
                    importFailure = outcomes.filterIsInstance<DictionaryImportOutcome.Failed>()
                        .firstOrNull()
                        ?.reason
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
                title = { Text(text = stringResource(KUNMR.strings.pref_import_yomitan_settings_title)) },
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
                                        context,
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

        // KUN -->
        // The combined dictionaries/OCR switch always comes first; every group below (profiles,
        // import, manage, audio) is gated on it, so this screen is not a backdoor into the feature.
        return masterPreferences() + listOf(
            // KUN -->
            Preference.PreferenceGroup(
                title = stringResource(SYMR.strings.pref_category_navbar),
                visible = featureEnabled,
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = uiPreferences.showNavDictionary,
                        title = stringResource(KUNMR.strings.pref_show_dictionary_button),
                        subtitle = stringResource(KUNMR.strings.pref_show_dictionary_button_summary),
                    ),
                ),
            ),
            // KUN <--
            Preference.PreferenceGroup(
                title = stringResource(KUNMR.strings.pref_dictionary_profiles),
                // KUN -->
                visible = featureEnabled,
                // KUN <--
                preferenceItems = listOf(
                    Preference.PreferenceItem.BasicListPreference(
                        value = activeProfile.id,
                        entries = profileEntries,
                        title = stringResource(KUNMR.strings.pref_dictionary_profiles),
                        subtitleProvider = { id, entries -> entries[id].orEmpty() },
                        onValueChanged = { id ->
                            prefs.profileStore.setActiveProfile(id)
                            rawProfiles = prefs.rawProfiles().get()
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(KUNMR.strings.pref_dict_imported_list),
                // KUN -->
                visible = featureEnabled,
                // KUN <--
                preferenceItems = buildList {
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(KUNMR.strings.pref_import_dictionary),
                            subtitle = stringResource(KUNMR.strings.pref_import_dictionary_summ),
                            onClick = {
                                importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                            },
                        ),
                    )
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(KUNMR.strings.pref_import_yomitan_settings),
                            subtitle = stringResource(KUNMR.strings.pref_import_yomitan_settings_summ),
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
                            title = stringResource(KUNMR.strings.pref_import_yomitan_backup),
                            subtitle = stringResource(KUNMR.strings.pref_import_yomitan_backup_summ),
                            onClick = { navigator.push(DictionaryBackupImportScreen()) },
                        ),
                    )
                    // KUN <--
                    // KUN -->
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(KUNMR.strings.pref_manage_dictionaries),
                            subtitle = if (dictionaries.isEmpty()) {
                                stringResource(KUNMR.strings.pref_dict_none_imported)
                            } else {
                                stringResource(KUNMR.strings.pref_manage_dictionaries_summary, dictionaries.size)
                            },
                            onClick = { navigator.push(DictionaryManagementScreen()) },
                        ),
                    )
                    // KUN <--
                    // KUN -->
                    imported?.let { dictionary ->
                        add(
                            Preference.PreferenceItem.TextPreference(
                                title = stringResource(
                                    KUNMR.strings.pref_import_dictionary_success,
                                    dictionary.termCount,
                                    dictionary.freqCount + dictionary.pitchCount + dictionary.kanjiCount,
                                    dictionary.mediaCount,
                                ),
                                subtitle = dictionary.title,
                                // Tapping the row clears it, as on the management screen
                                // (`clearImportMessage`): the confirmation is transient.
                                onClick = { imported = null },
                            ),
                        )
                    }
                    // KUN <--
                    // A failed import says why, in the same transient row as the success message.
                    importFailure?.let { failure ->
                        add(Preference.PreferenceItem.TextPreference(title = stringResource(failure.message)))
                    }
                    settingsMessage?.let { message ->
                        add(Preference.PreferenceItem.TextPreference(title = message))
                    }
                },
            ),
            Preference.PreferenceGroup(
                title = stringResource(KUNMR.strings.pref_dict_auto_kana_conversion),
                // KUN -->
                visible = featureEnabled,
                // KUN <--
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.autoKanaConversion(),
                        title = stringResource(KUNMR.strings.pref_dict_auto_kana_conversion),
                        subtitle = stringResource(KUNMR.strings.pref_dict_auto_kana_conversion_summary),
                    ),
                ),
            ),
            // KUN -->
            Preference.PreferenceGroup(
                title = stringResource(KUNMR.strings.pref_category_dictionary_audio),
                visible = featureEnabled,
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.audioAutoPlay(),
                        title = stringResource(KUNMR.strings.pref_dict_audio_autoplay),
                        subtitle = stringResource(KUNMR.strings.pref_dict_audio_autoplay_summary),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KUNMR.strings.pref_category_dictionary_audio),
                        subtitle = stringResource(KUNMR.strings.pref_dictionary_audio_summary),
                        onClick = { navigator.push(DictionaryAudioSettingsScreen()) },
                    ),
                ),
            ),
            // KUN <--
        )
        // KUN <--
    }

    /** Popup chrome group reused by [SettingsDictionaryPopupScreen] (02 §5.1). */
    @Composable
    fun popupPreferences(): List<Preference> {
        val context = LocalContext.current
        val prefs = remember(context) { context.appGraph.dictionaryPreferences }
        // KUN -->
        // The reset row below is only offered while a custom stylesheet exists, so it follows the
        // stored value rather than a value read once at composition.
        val customCss by prefs.customCss().collectAsState()
        // KUN <--
        // KUN -->
        // Secondary popup chrome follows the master switch, but does not repeat it: the screen itself
        // is hidden while the feature is off (`SettingsDictionaryPopupScreen.isEnabled`), and the
        // switch stays on the two ungated screens (Dictionary / OCR), so re-enable can never be
        // stranded. The chrome groups below are gated on it as well.
        val dictionaryOcrPreferences = remember(context) { context.appGraph.dictionaryOcrPreferences }
        val featureEnabled by dictionaryOcrPreferences.enabled.collectAsState()
        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(KUNMR.strings.pref_dict_popup_size),
                // KUN -->
                visible = featureEnabled,
                // KUN <--
                preferenceItems = listOf(
                    // KUN --> One "size" knob (a percentage of an adaptive, screen-relative
                    // baseline) instead of raw width/height dp, so it is a single change and the
                    // default grows with the screen (a tablet gets a bigger popup for free).
                    Preference.PreferenceItem.SliderPreference(
                        preference = prefs.popupSizePercent(),
                        title = stringResource(KUNMR.strings.pref_dict_popup_size_scale),
                        subtitle = stringResource(KUNMR.strings.pref_dict_popup_size_scale_summary),
                        valueRange = DictionaryPopupSizing.MIN_PERCENT..DictionaryPopupSizing.MAX_PERCENT,
                        valueText = { "$it%" },
                    ),
                    // KUN <--
                    Preference.PreferenceItem.SliderPreference(
                        preference = prefs.fontSize(),
                        title = stringResource(KUNMR.strings.pref_dict_popup_font_size),
                        valueRange = 10..32,
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(KUNMR.strings.pref_dict_appearance),
                // KUN -->
                visible = featureEnabled,
                // KUN <--
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.groupTerms(),
                        title = stringResource(KUNMR.strings.pref_dict_group_terms),
                        subtitle = stringResource(KUNMR.strings.pref_dict_group_terms_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.groupPitches(),
                        title = stringResource(KUNMR.strings.pref_dict_group_pitches),
                        subtitle = stringResource(KUNMR.strings.pref_dict_group_pitches_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.showFrequencyAverage(),
                        title = stringResource(KUNMR.strings.pref_dict_show_frequency_average),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.showPitchDiagram(),
                        title = stringResource(KUNMR.strings.pref_dict_pitch_diagram),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.showPitchNumber(),
                        title = stringResource(KUNMR.strings.pref_dict_pitch_number),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.showPitchText(),
                        title = stringResource(KUNMR.strings.pref_dict_pitch_text),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(KUNMR.strings.pref_dict_content),
                // KUN -->
                visible = featureEnabled,
                // KUN <--
                preferenceItems = listOf(
                    // KUN -->
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.audioAutoPlay(),
                        title = stringResource(KUNMR.strings.pref_dict_audio_autoplay),
                        subtitle = stringResource(KUNMR.strings.pref_dict_audio_autoplay_summary),
                    ),
                    // KUN <--
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.popupSwipeToDismiss(),
                        title = stringResource(KUNMR.strings.pref_dict_popup_swipe_to_dismiss),
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        preference = prefs.popupSwipeThreshold(),
                        title = stringResource(KUNMR.strings.pref_dict_popup_swipe_threshold),
                        valueRange = 24..200,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.showNavigationButtons(),
                        title = stringResource(KUNMR.strings.pref_dict_show_navigation_buttons),
                        subtitle = stringResource(KUNMR.strings.pref_dict_show_navigation_buttons_summary),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KUNMR.strings.pref_dict_custom_css),
                        subtitle = stringResource(KUNMR.strings.pref_dict_custom_css_summary),
                    ),
                    // KUN -->
                    // One tap to rule the custom CSS out: it is injected verbatim as a global
                    // `<style>` (bypassing the sanitiser), so a rule like `#entries{display:none}`
                    // hides every entry while the lookup itself succeeds. Shown only while there is
                    // something to reset, next to the row that describes it.
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KUNMR.strings.pref_dict_custom_css_reset),
                        subtitle = stringResource(KUNMR.strings.pref_dict_custom_css_reset_summary),
                        visible = customCss.isNotEmpty(),
                        onClick = { prefs.customCss().set("") },
                    ),
                    // KUN <--
                ),
            ),
        )
        // KUN <--
    }
}

/** Preview of a Yomitan settings import: warnings, then the applied and skipped keys. */
@Composable
private fun YomitanSettingsSummary(settings: YomitanSettingsImport) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        settings.warnings.forEach { warning ->
            Text(text = warning, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = stringResource(KUNMR.strings.pref_import_yomitan_settings_applied),
            style = MaterialTheme.typography.titleSmall,
        )
        settings.appliedReports.forEach { report ->
            Text(text = "${report.key} → ${report.detail}", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = stringResource(KUNMR.strings.pref_import_yomitan_settings_skipped),
            style = MaterialTheme.typography.titleSmall,
        )
        settings.skippedReports.forEach { report ->
            Text(text = "${report.key} (${report.detail})", style = MaterialTheme.typography.bodySmall)
        }
    }
}
