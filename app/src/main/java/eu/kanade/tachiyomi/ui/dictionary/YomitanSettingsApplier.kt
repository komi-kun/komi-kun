// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Writes a parsed [YomitanSettingsImport] into komi-kun preferences: dictionary profiles
// (order/enabled/display modes), per-dictionary display aliases, the global popup/display prefs
// and the active Anki mining profile. Idempotent — profiles are matched by name so re-importing
// updates in place instead of duplicating.
package eu.kanade.tachiyomi.ui.dictionary

import mihon.core.dictionary.anki.AnkiPreferences
import mihon.core.dictionary.model.DictionaryProfile
import mihon.core.dictionary.yomitan.YomitanSettingsImport
import java.util.UUID

object YomitanSettingsApplier {

    /** What the write-back changed, for the confirmation message. */
    data class Applied(
        val createdProfiles: Int,
        val updatedProfiles: Int,
        val activeProfileId: String?,
    )

    fun apply(
        settings: YomitanSettingsImport,
        dictionaryPreferences: DictionaryPreferences,
        ankiPreferences: AnkiPreferences,
    ): Applied {
        settings.aliases.forEach { (name, alias) -> dictionaryPreferences.setDisplayName(name, alias) }

        val profileResult = applyProfiles(settings, dictionaryPreferences)
        applyPopup(settings, dictionaryPreferences)
        applyAnki(settings, ankiPreferences)
        return profileResult
    }

    private fun applyProfiles(
        settings: YomitanSettingsImport,
        dictionaryPreferences: DictionaryPreferences,
    ): Applied {
        val store = dictionaryPreferences.profileStore
        val existing = store.getProfiles()
        val byName = existing.associateBy { it.name }

        var created = 0
        var updated = 0
        val imported = settings.profiles.map { source ->
            val current = byName[source.name]
            if (current == null) created++ else updated++
            DictionaryProfile(
                id = current?.id ?: UUID.randomUUID().toString(),
                name = source.name,
                dictionaryOrder = source.dictionaryOrder,
                enabledDictionaries = source.enabledDictionaries,
                dictionaryDisplayModes = source.dictionaryDisplayModes,
                languageCode = source.languageCode,
                scanResolution = source.scanResolution,
                searchResolution = source.searchResolution,
            )
        }

        val importedNames = imported.mapTo(mutableSetOf()) { it.name }
        val untouched = existing.filterNot { it.name in importedNames }
        store.saveProfiles(imported + untouched)

        val activeSource = settings.profiles.firstOrNull { it.isActive } ?: settings.profiles.firstOrNull()
        val activeId = activeSource?.let { source -> imported.firstOrNull { it.name == source.name }?.id }
        if (activeId != null) store.setActiveProfile(activeId)

        return Applied(createdProfiles = created, updatedProfiles = updated, activeProfileId = activeId)
    }

    private fun applyPopup(settings: YomitanSettingsImport, prefs: DictionaryPreferences) {
        val popup = settings.popup
        popup.width?.let { prefs.popupWidth().set(it) }
        popup.height?.let { prefs.popupHeight().set(it) }
        popup.fontSize?.let { prefs.fontSize().set(it) }
        popup.fontFamily?.let { prefs.fontFamily().set(it) }
        popup.themeMode?.let { prefs.themeMode().set(it) }
        popup.customCss?.let { prefs.customCss().set(it) }
        popup.showPitchDiagram?.let { prefs.showPitchDiagram().set(it) }
        popup.showPitchNumber?.let { prefs.showPitchNumber().set(it) }
        popup.showPitchText?.let { prefs.showPitchText().set(it) }
        popup.showFrequencyAverage?.let { prefs.showFrequencyAverage().set(it) }
        popup.groupTerms?.let { prefs.groupTerms().set(it) }
        popup.compactGlossary?.let { prefs.compactGlossary().set(it) }
    }

    private fun applyAnki(settings: YomitanSettingsImport, ankiPreferences: AnkiPreferences) {
        val anki = settings.anki
        // Make sure a mining profile exists, then write into it and the flat keys it mirrors.
        ankiPreferences.migrateIfEmpty()
        val active = ankiPreferences.profileStore.getActiveProfile()
        val updated = active.copy(
            ankiEnabled = anki.enabled ?: active.ankiEnabled,
            ankiTags = anki.tags ?: active.ankiTags,
            ankiDupCheck = anki.duplicateCheck ?: active.ankiDupCheck,
            ankiDupScope = anki.duplicateScope ?: active.ankiDupScope,
            ankiDupAction = anki.duplicateAction ?: active.ankiDupAction,
        )
        ankiPreferences.profileStore.updateProfile(updated)
        ankiPreferences.writeFlatProfile(updated)
    }
}
// KUN <--
