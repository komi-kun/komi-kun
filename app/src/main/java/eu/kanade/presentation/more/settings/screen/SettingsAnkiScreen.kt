// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Real Anki mining settings (plan 05 §B5): profiles, deck/model selection, per-field mapping,
// duplicate handling and screenshot mode. Uses existing KMR keys; the few enum *value* labels
// that have no key (duplicate scope, screenshot FULL/CROP/NONE) fall back to literals (flagged in
// the W5 report — add KMR keys if translations are wanted).
package eu.kanade.presentation.more.settings.screen

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.app.di.appGraph
import mihon.core.dictionary.anki.AnkiDroidBridge
import mihon.core.dictionary.anki.AnkiMarkerEngine
import mihon.core.dictionary.anki.AnkiPreferences
import mihon.core.dictionary.anki.AnkiProfile
import mihon.core.dictionary.anki.AnkiScreenshotMode
import mihon.core.dictionary.anki.LapisPreset
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.i18n.MR
import tachiyomi.i18n.kun.KUNMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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
    override fun getTitleRes() = KUNMR.strings.pref_category_anki

    // KUN -->
    /**
     * Anki mining is part of the dictionaries feature. While the combined dictionary/OCR opt-in is
     * off the entry is hidden from Settings and from search (`SearchableSettings.isEnabled`), so the
     * screen is never composed — which is what keeps it from migrating its preferences
     * (`AnkiPreferences.migrateIfEmpty`) or talking to AnkiDroid (`AnkiDroidBridge`: install/permission
     * checks, deck and model lists) behind the user's back. The master switch stays reachable from
     * [SettingsDictionaryScreen] and [SettingsOcrScreen].
     */
    override fun isEnabled(): Boolean = Injekt.get<Context>().appGraph.dictionaryOcrPreferences.isEnabled()
    // KUN <--

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
        var mappingDialogMode by remember { mutableStateOf("overwrite") }

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
            val modeLabels = listOf(
                "overwrite" to "Overwrite",
                "append" to "Append",
                "prepend" to "Prepend",
                "if_empty" to "If Empty",
                "skip" to "Skip",
            )
            AlertDialog(
                onDismissRequest = { mappingDialogField = null },
                title = { Text(text = field) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = mappingDialogValue,
                            onValueChange = { mappingDialogValue = it },
                            label = { Text(text = stringResource(KUNMR.strings.pref_anki_field_mapping)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "Duplicate card update mode:",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            modeLabels.forEach { (modeKey, modeLabel) ->
                                val isSelected = mappingDialogMode.equals(modeKey, ignoreCase = true)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { mappingDialogMode = modeKey },
                                    label = { Text(modeLabel, style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val updated = fieldMap.toMutableMap().apply { put(field, mappingDialogValue) }
                            prefs.fieldMap().set(mapToJson(updated))
                            val updatedModes = activeProfile.ankiFieldOverwriteModes.toMutableMap().apply {
                                put(field, mappingDialogMode)
                            }
                            prefs.profileStore.updateProfile(activeProfile.copy(ankiFieldOverwriteModes = updatedModes))
                            prefs.fieldOverwriteModes().set(
                                buildJsonObject {
                                    for ((k, v) in updatedModes) put(k, v)
                                }.toString(),
                            )
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
                overwriteModes = activeProfile.ankiFieldOverwriteModes,
                onEditField = { field ->
                    mappingDialogField = field
                    mappingDialogValue = fieldMap[field].orEmpty()
                    mappingDialogMode = activeProfile.ankiFieldOverwriteModes[field] ?: "overwrite"
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
            title = stringResource(KUNMR.strings.pref_category_anki),
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.enabled(),
                        title = stringResource(KUNMR.strings.pref_anki_enable),
                    ),
                )
                if (!installed) {
                    add(Preference.PreferenceItem.TextPreference(title = stringResource(KUNMR.strings.pref_anki_no_ankidroid)))
                } else if (!permitted) {
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(KUNMR.strings.pref_anki_permission_denied),
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
        title = stringResource(KUNMR.strings.pref_anki_profiles),
        preferenceItems = buildList {
            add(
                Preference.PreferenceItem.BasicListPreference(
                    value = activeProfile.id,
                    entries = profileEntries,
                    title = stringResource(KUNMR.strings.pref_anki_profile_active, activeProfile.name),
                    subtitleProvider = { id, entries -> entries[id].orEmpty() },
                    onValueChanged = onSwitch,
                ),
            )
            add(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KUNMR.strings.pref_anki_profile_new),
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
                    title = stringResource(KUNMR.strings.pref_anki_profile_clone),
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
                    title = stringResource(KUNMR.strings.pref_anki_profile_delete),
                    subtitle = stringResource(KUNMR.strings.pref_anki_profile_cannot_delete_last),
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
        overwriteModes: Map<String, String>,
        onEditField: (String) -> Unit,
        onChanged: () -> Unit,
    ): Preference.PreferenceGroup {
        val deckEntries = (decks + prefs.deck().get()).filter { it.isNotBlank() }.distinct().associateWith { it }
        val modelEntries = (models + prefs.model().get()).filter { it.isNotBlank() }.distinct().associateWith { it }
        return Preference.PreferenceGroup(
            title = stringResource(KUNMR.strings.pref_anki_settings_summary),
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.BasicListPreference(
                        value = prefs.deck().get(),
                        entries = deckEntries.ifEmpty { mapOf("" to stringResource(KUNMR.strings.pref_anki_select_deck)) },
                        title = stringResource(KUNMR.strings.pref_anki_deck),
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
                        entries = modelEntries.ifEmpty { mapOf("" to stringResource(KUNMR.strings.pref_anki_select_model)) },
                        title = stringResource(KUNMR.strings.pref_anki_model),
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
                        title = stringResource(KUNMR.strings.pref_anki_default_tags),
                        onValueChanged = {
                            onChanged()
                            true
                        },
                    ),
                )
                if (modelFields.isNotEmpty()) {
                    modelFields.forEach { field ->
                        val template = fieldMap[field].orEmpty()
                        val mode = overwriteModes[field] ?: "overwrite"
                        val subtitle = if (mode != "overwrite") {
                            if (template.isNotBlank()) "$template • [$mode]" else "[$mode]"
                        } else {
                            template
                        }
                        add(
                            Preference.PreferenceItem.TextPreference(
                                title = field,
                                subtitle = subtitle,
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
            "deck" to stringResource(KUNMR.strings.pref_anki_deck),
            "deck-root" to "${stringResource(KUNMR.strings.pref_anki_deck)} + children",
            "collection" to "Collection",
        )
        val actionEntries = linkedMapOf(
            "prevent" to stringResource(KUNMR.strings.pref_anki_duplicate_prevent),
            "add" to stringResource(KUNMR.strings.pref_anki_duplicate_add),
            "overwrite" to stringResource(KUNMR.strings.pref_anki_duplicate_overwrite),
        )
        return Preference.PreferenceGroup(
            title = stringResource(KUNMR.strings.pref_anki_check_duplicates),
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.duplicateCheck(),
                        title = stringResource(KUNMR.strings.pref_anki_check_duplicates),
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
                        title = stringResource(KUNMR.strings.pref_anki_duplicate_scope),
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
                        title = stringResource(KUNMR.strings.pref_anki_duplicate_action),
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
            AnkiScreenshotMode.ANIMATED_SCENE.storageValue to stringResource(KUNMR.strings.pref_anki_screenshot_animated_scene),
        )
        return Preference.PreferenceGroup(
            title = stringResource(KUNMR.strings.anki_field_screenshot),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.cropMode(),
                    entries = modeEntries,
                    title = stringResource(KUNMR.strings.anki_field_screenshot),
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
