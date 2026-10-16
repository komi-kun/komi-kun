// SPDX-License-Identifier: GPL-3.0-or-later
package eu.kanade.presentation.more.settings.screen

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.core.dictionary.model.TYPE_SUBDIRS
import mihon.core.dictionary.model.allDictionaryNames
import mihon.core.dictionary.model.dictionaryTypeDir
import mihon.core.dictionary.repository.DictionaryRepository
import mihon.core.dictionary.ui.invalidateDictionaryTitle
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File

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
        val prefs = remember(context) { context.appGraph.dictionaryPreferences }
        val repository = remember(context) { context.appGraph.dictionaryRepository }
        val scope = rememberCoroutineScope()
        var refresh by remember { mutableIntStateOf(0) }

        var rawProfiles by remember { mutableStateOf(prefs.rawProfiles().get()) }
        var importMessage by remember { mutableStateOf<String?>(null) }

        val dictionaries = remember(refresh) { allDictionaryNames(context) }
        val activeProfile = remember(rawProfiles, refresh) { prefs.profileStore.getActiveProfile() }
        val profileEntries = remember(rawProfiles) { prefs.profileStore.getProfiles().associate { it.id to it.name } }

        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                scope.launch {
                    val imported = withContext(Dispatchers.IO) {
                        uris.mapNotNull { uri -> importDictionary(context, uri, prefs, repository) }
                    }
                    importMessage = imported.lastOrNull()
                    refresh++
                }
            }
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
                                        deleteDictionary(context, name, prefs)
                                        invalidateDictionaryTitle(name)
                                        refresh++
                                    },
                                ),
                            )
                        }
                    }
                    importMessage?.let { message ->
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
                    Preference.PreferenceItem.SliderPreference(
                        preference = prefs.popupWidth(),
                        title = stringResource(KMR.strings.pref_dict_popup_width),
                        valueRange = 200..600,
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        preference = prefs.popupHeight(),
                        title = stringResource(KMR.strings.pref_dict_popup_height),
                        valueRange = 200..800,
                    ),
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

/** Copy an imported dictionary into the type tree and add it to the active profile (02 §6.2). */
private suspend fun importDictionary(
    context: Context,
    uri: Uri,
    prefs: DictionaryPreferences,
    repository: DictionaryRepository,
): String? {
    val tempZip = File(context.cacheDir, "dict_import_${System.currentTimeMillis()}.zip")
    val tempImportDir = File(context.cacheDir, "dict_import_tmp_${System.currentTimeMillis()}")
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempZip.outputStream().use { output -> input.copyTo(output) }
        } ?: return null

        tempImportDir.mkdirs()
        val result = repository.importDictionary(tempZip, tempImportDir)
        if (!result.success) return null

        val importedDir = tempImportDir.listFiles()?.firstOrNull { it.isDirectory } ?: return null
        val title = result.title.ifBlank { importedDir.name }

        val targets = buildList {
            if (result.termCount > 0) add("term")
            if (result.freqCount > 0) add("frequency")
            if (result.pitchCount > 0) add("pitch")
            if (result.kanjiCount > 0) add("kanji")
        }.ifEmpty { TYPE_SUBDIRS }

        for (type in targets) {
            val destDir = dictionaryTypeDir(context, type, title)
            if (destDir.exists()) destDir.deleteRecursively()
            destDir.parentFile?.mkdirs()
            importedDir.copyRecursively(destDir, overwrite = true)
        }

        val profile = prefs.profileStore.getActiveProfile()
        val order = profile.dictionaryOrder.filter { it.isNotBlank() }
        if (title !in order) {
            prefs.profileStore.updateProfile(
                profile.copy(
                    dictionaryOrder = order + title,
                    enabledDictionaries = if (profile.enabledDictionaries.isNotEmpty()) {
                        profile.enabledDictionaries + title
                    } else {
                        profile.enabledDictionaries
                    },
                ),
            )
        }
        invalidateDictionaryTitle(title)
        title
    } catch (e: Throwable) {
        Log.e("DictImport", "import failed", e)
        null
    } finally {
        if (tempZip.exists()) tempZip.delete()
        if (tempImportDir.exists()) tempImportDir.deleteRecursively()
    }
}

/** Remove all type subdirs for [name] from disk and drop it from the active profile order. */
private fun deleteDictionary(context: Context, name: String, prefs: DictionaryPreferences) {
    TYPE_SUBDIRS.forEach { type ->
        val dir = dictionaryTypeDir(context, type, name)
        if (dir.exists()) dir.deleteRecursively()
    }
    val profile = prefs.profileStore.getActiveProfile()
    prefs.profileStore.updateProfile(
        profile.copy(
            dictionaryOrder = profile.dictionaryOrder.filter { it != name },
            enabledDictionaries = profile.enabledDictionaries - name,
        ),
    )
    prefs.setDisplayName(name, null)
}
