// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Real Anki mining settings (plan 05 §B5): profiles, deck/model selection, per-field mapping,
// duplicate handling and screenshot mode. Every user-visible label comes from the `i18n-kun`
// module (`KUNMR`); nothing here is a hardcoded literal, so all of it is translatable.
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
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
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

    /** Whether the AnkiDroid deck/model lists are still being queried, loaded, or failed to load. */
    private enum class ListLoadState { LOADING, LOADED, FAILED }

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
        // KUN -->
        // The deck/model lists come from AnkiDroid through a ContentProvider, so they are not
        // available synchronously and a query can fail. Keeping a tri-state stops an in-flight or
        // failed load from looking like "this account has no decks" (an empty list).
        var listLoadState by remember { mutableStateOf(ListLoadState.LOADING) }
        var deleteProfileDialog by remember { mutableStateOf(false) }
        // KUN <--

        LaunchedEffect(refresh) {
            prefs.migrateIfEmpty()
            installed = withContext(Dispatchers.IO) { bridge.isAnkiDroidInstalled() }
            permitted = bridge.hasPermission()
            if (installed && permitted) {
                // KUN -->
                // Only a first (or empty/failed) load shows the loading row; a refresh after a
                // successful load keeps the lists on screen instead of flashing "Loading…".
                if (decks.isEmpty() && models.isEmpty()) {
                    listLoadState = ListLoadState.LOADING
                }
                try {
                    decks = bridge.deckNames()
                    models = bridge.modelNames()
                    val model = prefs.model().get()
                    modelFields = if (model.isNotBlank()) bridge.modelFieldNames(model) else emptyList()
                    listLoadState = ListLoadState.LOADED
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    xLogE("AnkiDroid deck/model query failed", e)
                    listLoadState = ListLoadState.FAILED
                }
                // KUN <--
            } else {
                // Nothing to query: the "not installed" / "permission denied" rows explain it.
                decks = emptyList()
                models = emptyList()
                modelFields = emptyList()
                listLoadState = ListLoadState.LOADED
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
            val modeLabels = fieldModeLabels()
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
                            text = stringResource(KUNMR.strings.pref_anki_field_mode_title),
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

        // KUN -->
        // Deleting a profile is irreversible (it drops the profile's field map and overwrite modes),
        // so it goes through a confirmation that names the profile; `pref_anki_profile_delete_confirm`
        // is the same "%s"-formatted string the dictionary screen pattern uses.
        if (deleteProfileDialog) {
            AlertDialog(
                onDismissRequest = { deleteProfileDialog = false },
                title = { Text(text = stringResource(MR.strings.action_delete)) },
                text = { Text(text = stringResource(KUNMR.strings.pref_anki_profile_delete_confirm, activeProfile.name)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (prefs.profileStore.deleteProfile(activeProfile.id)) {
                                prefs.writeFlatProfile(prefs.profileStore.getActiveProfile())
                                refresh++
                            }
                            deleteProfileDialog = false
                        },
                    ) {
                        Text(
                            text = stringResource(MR.strings.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteProfileDialog = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
        // KUN <--

        return listOf(
            ankiStatusGroup(installed, permitted, prefs),
            profileGroup(
                prefs = prefs,
                activeProfile = activeProfile,
                profileEntries = profileEntries,
                // The store refuses to delete the last profile, so only ask when one can go.
                canDelete = profileEntries.size > 1,
                onDelete = { deleteProfileDialog = true },
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
                listLoadState = listLoadState,
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
        canDelete: Boolean,
        onDelete: () -> Unit,
        onSwitch: (String) -> Unit,
        onChanged: () -> Unit,
    ): Preference.PreferenceGroup = Preference.PreferenceGroup(
        title = stringResource(KUNMR.strings.pref_anki_profiles),
        preferenceItems = buildList {
            // Resolved in the composable scope: `onClick` below is not one.
            val newProfileName = stringResource(
                KUNMR.strings.pref_anki_profile_default_name,
                prefs.profiles().size + 1,
            )
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
                        val profile = AnkiProfile.createDefault(defaultName = newProfileName)
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
                    // The "cannot delete the last profile" hint is true only while there is a single
                    // profile; showing it unconditionally contradicted the working delete.
                    subtitle = if (canDelete) {
                        null
                    } else {
                        stringResource(KUNMR.strings.pref_anki_profile_cannot_delete_last)
                    },
                    onClick = if (canDelete) onDelete else null,
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
        listLoadState: ListLoadState,
        onEditField: (String) -> Unit,
        onChanged: () -> Unit,
    ): Preference.PreferenceGroup {
        val deckEntries = (decks + prefs.deck().get()).filter { it.isNotBlank() }.distinct().associateWith { it }
        val modelEntries = (models + prefs.model().get()).filter { it.isNotBlank() }.distinct().associateWith { it }
        return Preference.PreferenceGroup(
            title = stringResource(KUNMR.strings.pref_anki_settings_summary),
            preferenceItems = buildList {
                // KUN -->
                // An out-of-date/failed AnkiDroid query leaves the two lists empty, which the rows
                // below would render as "Select deck" / "Select note type" — indistinguishable from
                // an AnkiDroid with no decks at all. Surface the load itself instead; once it has
                // loaded, an empty list really is empty.
                if (listLoadState != ListLoadState.LOADED) {
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = if (listLoadState == ListLoadState.LOADING) {
                                stringResource(KUNMR.strings.pref_anki_loading)
                            } else {
                                stringResource(KUNMR.strings.pref_anki_load_failed)
                            },
                        ),
                    )
                }
                // KUN <--
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
                    val modeLabels = fieldModeLabels()
                    modelFields.forEach { field ->
                        val template = fieldMap[field].orEmpty()
                        val mode = overwriteModes[field] ?: "overwrite"
                        // Render the mode's translated label, not its storage key ("[if_empty]").
                        val modeLabel = modeLabels.firstOrNull { it.first.equals(mode, ignoreCase = true) }?.second
                        val subtitle = if (modeLabel != null && !mode.equals("overwrite", ignoreCase = true)) {
                            if (template.isNotBlank()) "$template • $modeLabel" else modeLabel
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
            "deck-root" to stringResource(
                KUNMR.strings.pref_anki_duplicate_scope_deck_children,
                stringResource(KUNMR.strings.pref_anki_deck),
            ),
            "collection" to stringResource(KUNMR.strings.pref_anki_duplicate_scope_collection),
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
            AnkiScreenshotMode.FULL.storageValue to stringResource(KUNMR.strings.pref_anki_screenshot_full),
            AnkiScreenshotMode.CROP.storageValue to stringResource(KUNMR.strings.pref_anki_screenshot_crop),
            AnkiScreenshotMode.NONE.storageValue to stringResource(KUNMR.strings.pref_anki_screenshot_none),
            AnkiScreenshotMode.ANIMATED_SCENE.storageValue to stringResource(KUNMR.strings.pref_anki_screenshot_animated_scene),
        )
        // KUN -->
        // The preset the "Crop to panel" mode expands the block to. Built from the shared
        // `CropPresets` vocabulary so a key the reader cannot map (see
        // `ReaderOcrHostImpl.screenshotProviderFor`) is never offered.
        val presetEntries = AnkiScreenshotPresetOptions.keys.associateWith { key ->
            AnkiScreenshotPresetOptions.presetLabel(key)
                ?: stringResource(KUNMR.strings.pref_anki_crop_preset_full)
        }
        // KUN <--
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
                // KUN -->
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.cropPreset(),
                    entries = presetEntries,
                    title = stringResource(KUNMR.strings.pref_anki_crop_preset),
                    onValueChanged = {
                        onChanged()
                        true
                    },
                ),
                // KUN <--
            ),
        )
    }

    /**
     * The per-field duplicate update modes with their translated labels, in the order the chips
     * show them. Shared by the mapping dialog and the field-row subtitle so the two can never
     * disagree, and so no storage key ("if_empty") ever reaches the UI.
     */
    @Composable
    @ReadOnlyComposable
    private fun fieldModeLabels(): List<Pair<String, String>> = listOf(
        "overwrite" to stringResource(KUNMR.strings.pref_anki_field_mode_overwrite),
        "append" to stringResource(KUNMR.strings.pref_anki_field_mode_append),
        "prepend" to stringResource(KUNMR.strings.pref_anki_field_mode_prepend),
        "if_empty" to stringResource(KUNMR.strings.pref_anki_field_mode_if_empty),
        "skip" to stringResource(KUNMR.strings.pref_anki_field_mode_skip),
    )

    private fun mapToJson(map: Map<String, String>): String =
        map.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${escape(key)}\":\"${escape(value)}\""
        }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
// KUN <--
