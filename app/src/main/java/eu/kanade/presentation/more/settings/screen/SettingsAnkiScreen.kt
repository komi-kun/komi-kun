// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Real Anki mining settings (plan 05 §B5): profiles, deck/model selection, per-field mapping,
// duplicate handling and screenshot mode. Uses existing KMR keys; the few enum *value* labels
// that have no key (duplicate scope, screenshot FULL/CROP/NONE) fall back to literals (flagged in
// the W5 report — add KMR keys if translations are wanted).
package eu.kanade.presentation.more.settings.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.core.dictionary.anki.AnkiDroidBridge
import mihon.core.dictionary.anki.AnkiMarkerEngine
import mihon.core.dictionary.anki.AnkiPreferences
import mihon.core.dictionary.anki.AnkiProfile
import mihon.core.dictionary.anki.AnkiScreenshotMode
import mihon.core.dictionary.anki.LapisPreset
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Anki mining + profiles (per-profile fields) — phase 6 (05 §B5).
 *
 * The screen is a stateless [SearchableSettings]; it builds [AnkiPreferences] from the app's
 * default `PreferenceStore` (same pattern as [SettingsOcrScreen]) and talks to AnkiDroid through
 * [AnkiDroidBridge] for the deck/model lists.
 */
object SettingsAnkiScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_category_anki

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val prefs = remember(context) { AnkiPreferences(AndroidPreferenceStore(context)) }
        val bridge = remember(context) { AnkiDroidBridge(context) }

        var refresh by remember { mutableIntStateOf(0) }
        var decks by remember { mutableStateOf<List<String>>(emptyList()) }
        var models by remember { mutableStateOf<List<String>>(emptyList()) }
        var modelFields by remember { mutableStateOf<List<String>>(emptyList()) }
        var installed by remember { mutableStateOf(true) }
        var permitted by remember { mutableStateOf(bridge.hasPermission()) }
        var mappingDialogField by remember { mutableStateOf<String?>(null) }
        var mappingDialogValue by remember { mutableStateOf("") }

        LaunchedEffect(refresh) {
            prefs.migrateIfEmpty()
            installed = withContext(Dispatchers.IO) { bridge.isAnkiDroidInstalled() }
            permitted = bridge.hasPermission()
            if (installed && permitted) {
                decks = bridge.deckNames()
                models = bridge.modelNames()
                val model = prefs.model().get()
                modelFields = if (model.isNotBlank()) bridge.modelFieldNames(model) else emptyList()
            }
        }

        val activeProfile = remember(refresh) { prefs.profileStore.getActiveProfile() }
        val profileEntries = remember(refresh) {
            prefs.profiles().associate { it.id to it.name }.ifEmpty { mapOf(activeProfile.id to activeProfile.name) }
        }
        val fieldMap = remember(refresh, activeProfile.ankiFieldMap) {
            AnkiMarkerEngine.parseFieldMap(activeProfile.ankiFieldMap)
        }

        val persistAndRefresh: () -> Unit = {
            prefs.persistActiveFromFlat()
            refresh++
        }

        if (mappingDialogField != null) {
            val field = mappingDialogField!!
            AlertDialog(
                onDismissRequest = { mappingDialogField = null },
                title = { Text(text = field) },
                text = {
                    OutlinedTextField(
                        value = mappingDialogValue,
                        onValueChange = { mappingDialogValue = it },
                        label = { Text(text = stringResource(KMR.strings.pref_anki_field_mapping)) },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val updated = fieldMap.toMutableMap().apply { put(field, mappingDialogValue) }
                            prefs.fieldMap().set(mapToJson(updated))
                            mappingDialogField = null
                            persistAndRefresh()
                        },
                    ) { Text(text = stringResource(MR.strings.action_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { mappingDialogField = null }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        return listOf(
            ankiStatusGroup(installed, permitted, prefs),
            profileGroup(
                prefs = prefs,
                activeProfile = activeProfile,
                profileEntries = profileEntries,
                onSwitch = { id ->
                    prefs.profileStore.setActiveProfile(id)
                    prefs.writeFlatProfile(prefs.profileStore.getActiveProfile())
                    refresh++
                },
                onChanged = { refresh++ },
            ),
            coreGroup(
                prefs = prefs,
                decks = decks,
                models = models,
                modelFields = modelFields,
                fieldMap = fieldMap,
                onEditField = { field ->
                    mappingDialogField = field
                    mappingDialogValue = fieldMap[field].orEmpty()
                },
                onChanged = persistAndRefresh,
            ),
            duplicateGroup(prefs, persistAndRefresh),
            screenshotGroup(prefs, persistAndRefresh),
        )
    }

    @Composable
    private fun ankiStatusGroup(installed: Boolean, permitted: Boolean, prefs: AnkiPreferences): Preference =
        Preference.PreferenceGroup(
            title = stringResource(KMR.strings.pref_category_anki),
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.enabled(),
                        title = stringResource(KMR.strings.pref_anki_enable),
                    ),
                )
                if (!installed) {
                    add(Preference.PreferenceItem.TextPreference(title = stringResource(KMR.strings.pref_anki_no_ankidroid)))
                } else if (!permitted) {
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(KMR.strings.pref_anki_permission_denied),
                        ),
                    )
                }
            },
        )

    @Composable
    private fun profileGroup(
        prefs: AnkiPreferences,
        activeProfile: AnkiProfile,
        profileEntries: Map<String, String>,
        onSwitch: (String) -> Unit,
        onChanged: () -> Unit,
    ): Preference.PreferenceGroup = Preference.PreferenceGroup(
        title = stringResource(KMR.strings.pref_anki_profiles),
        preferenceItems = buildList {
            add(
                Preference.PreferenceItem.BasicListPreference(
                    value = activeProfile.id,
                    entries = profileEntries,
                    title = stringResource(KMR.strings.pref_anki_profile_active, activeProfile.name),
                    subtitleProvider = { id, entries -> entries[id].orEmpty() },
                    onValueChanged = onSwitch,
                ),
            )
            add(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.pref_anki_profile_new),
                    onClick = {
                        val profile = AnkiProfile.createDefault(defaultName = "Profile ${prefs.profiles().size + 1}")
                        prefs.profileStore.addProfile(profile)
                        prefs.writeFlatProfile(profile)
                        onChanged()
                    },
                ),
            )
            add(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.pref_anki_profile_clone),
                    onClick = {
                        val clone = activeProfile.copy(
                            id = java.util.UUID.randomUUID().toString(),
                            name = "${activeProfile.name} 2",
                        )
                        prefs.profileStore.addProfile(clone)
                        prefs.writeFlatProfile(clone)
                        onChanged()
                    },
                ),
            )
            add(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.pref_anki_profile_delete),
                    subtitle = stringResource(KMR.strings.pref_anki_profile_cannot_delete_last),
                    onClick = {
                        if (prefs.profileStore.deleteProfile(activeProfile.id)) {
                            prefs.writeFlatProfile(prefs.profileStore.getActiveProfile())
                            onChanged()
                        }
                    },
                ),
            )
        },
    )

    @Composable
    private fun coreGroup(
        prefs: AnkiPreferences,
        decks: List<String>,
        models: List<String>,
        modelFields: List<String>,
        fieldMap: Map<String, String>,
        onEditField: (String) -> Unit,
        onChanged: () -> Unit,
    ): Preference.PreferenceGroup {
        val deckEntries = (decks + prefs.deck().get()).filter { it.isNotBlank() }.distinct().associateWith { it }
        val modelEntries = (models + prefs.model().get()).filter { it.isNotBlank() }.distinct().associateWith { it }
        return Preference.PreferenceGroup(
            title = stringResource(KMR.strings.pref_anki_settings_summary),
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.BasicListPreference(
                        value = prefs.deck().get(),
                        entries = deckEntries.ifEmpty { mapOf("" to stringResource(KMR.strings.pref_anki_select_deck)) },
                        title = stringResource(KMR.strings.pref_anki_deck),
                        subtitleProvider = { v, e -> e[v] ?: v },
                        onValueChanged = {
                            prefs.deck().set(it)
                            onChanged()
                        },
                    ),
                )
                add(
                    Preference.PreferenceItem.BasicListPreference(
                        value = prefs.model().get(),
                        entries = modelEntries.ifEmpty { mapOf("" to stringResource(KMR.strings.pref_anki_select_model)) },
                        title = stringResource(KMR.strings.pref_anki_model),
                        subtitleProvider = { v, e -> e[v] ?: v },
                        onValueChanged = {
                            prefs.model().set(it)
                            prefs.fieldMap().set(LapisPreset.applyDefaults(it, modelFields, prefs.fieldMap().get()))
                            onChanged()
                        },
                    ),
                )
                add(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.defaultTags(),
                        title = stringResource(KMR.strings.pref_anki_default_tags),
                        onValueChanged = {
                            onChanged()
                            true
                        },
                    ),
                )
                if (modelFields.isNotEmpty()) {
                    modelFields.forEach { field ->
                        add(
                            Preference.PreferenceItem.TextPreference(
                                title = field,
                                subtitle = fieldMap[field] ?: "",
                                onClick = { onEditField(field) },
                            ),
                        )
                    }
                }
            },
        )
    }

    @Composable
    private fun duplicateGroup(prefs: AnkiPreferences, onChanged: () -> Unit): Preference.PreferenceGroup {
        val scopeEntries = linkedMapOf(
            "deck" to stringResource(KMR.strings.pref_anki_deck),
            "deck-root" to "${stringResource(KMR.strings.pref_anki_deck)} + children",
            "collection" to "Collection",
        )
        val actionEntries = linkedMapOf(
            "prevent" to stringResource(KMR.strings.pref_anki_duplicate_prevent),
            "add" to stringResource(KMR.strings.pref_anki_duplicate_add),
            "overwrite" to stringResource(KMR.strings.pref_anki_duplicate_overwrite),
        )
        return Preference.PreferenceGroup(
            title = stringResource(KMR.strings.pref_anki_check_duplicates),
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.duplicateCheck(),
                        title = stringResource(KMR.strings.pref_anki_check_duplicates),
                        onValueChanged = {
                            onChanged()
                            true
                        },
                    ),
                )
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.duplicateScope(),
                        entries = scopeEntries,
                        title = stringResource(KMR.strings.pref_anki_duplicate_scope),
                        onValueChanged = {
                            onChanged()
                            true
                        },
                    ),
                )
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.duplicateAction(),
                        entries = actionEntries,
                        title = stringResource(KMR.strings.pref_anki_duplicate_action),
                        onValueChanged = {
                            onChanged()
                            true
                        },
                    ),
                )
            },
        )
    }

    @Composable
    private fun screenshotGroup(prefs: AnkiPreferences, onChanged: () -> Unit): Preference.PreferenceGroup {
        val modeEntries = linkedMapOf(
            AnkiScreenshotMode.FULL.storageValue to "Full page",
            AnkiScreenshotMode.CROP.storageValue to "Crop to panel",
            AnkiScreenshotMode.NONE.storageValue to "No screenshot",
            AnkiScreenshotMode.ANIMATED_SCENE.storageValue to stringResource(KMR.strings.pref_anki_screenshot_animated_scene),
        )
        return Preference.PreferenceGroup(
            title = stringResource(KMR.strings.anki_field_screenshot),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.cropMode(),
                    entries = modeEntries,
                    title = stringResource(KMR.strings.anki_field_screenshot),
                    onValueChanged = {
                        onChanged()
                        true
                    },
                ),
            ),
        )
    }

    private fun mapToJson(map: Map<String, String>): String =
        map.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${escape(key)}\":\"${escape(value)}\""
        }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
// KUN <--
