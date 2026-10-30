// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Writes a parsed [YomitanSettingsImport] into komi-kun preferences: dictionary profiles
// (order/enabled/display modes), per-dictionary display aliases, the global popup/display prefs,
// the active Anki mining profile and the word-audio group (sources/enabled/auto-play/volume). Idempotent — profiles are matched by name so re-importing
// updates in place instead of duplicating.
package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import mihon.core.dictionary.anki.AnkiPreferences
import mihon.core.dictionary.model.DictionaryProfile
import mihon.core.dictionary.model.allDictionaryNames
import mihon.core.dictionary.model.matchDictionaryName
import mihon.core.dictionary.model.resolveDictionaryOrder
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
        context: Context? = null,
    ): Applied {
        val allNames = context?.let { allDictionaryNames(it) }.orEmpty()
        settings.aliases.forEach { (name, alias) ->
            dictionaryPreferences.setDisplayName(name, alias)
            if (context != null && allNames.isNotEmpty()) {
                matchDictionaryName(name, allNames, context)?.let { matchedDirName ->
                    if (matchedDirName != name) {
                        dictionaryPreferences.setDisplayName(matchedDirName, alias)
                    }
                }
            }
        }

        val profileResult = applyProfiles(settings, dictionaryPreferences, context)
        applyPopup(settings, dictionaryPreferences)
        applyAnki(settings, ankiPreferences)
        applyAudio(settings, dictionaryPreferences)
        return profileResult
    }

    private fun applyProfiles(
        settings: YomitanSettingsImport,
        dictionaryPreferences: DictionaryPreferences,
        context: Context? = null,
    ): Applied {
        val store = dictionaryPreferences.profileStore
        val existing = store.getProfiles()
        val byName = existing.associateBy { it.name }
        val allNames = context?.let { allDictionaryNames(it) }.orEmpty()

        var created = 0
        var updated = 0
        val imported = settings.profiles.map { source ->
            val current = byName[source.name]
            if (current == null) created++ else updated++

            val resolvedOrder = if (allNames.isNotEmpty() && context != null) {
                resolveDictionaryOrder(allNames, source.dictionaryOrder, context)
            } else {
                source.dictionaryOrder
            }
            val resolvedEnabled = if (allNames.isNotEmpty() && context != null && source.enabledDictionaries.isNotEmpty()) {
                val mapped = source.enabledDictionaries.mapNotNull { matchDictionaryName(it, allNames, context) }.toSet()
                if (mapped.isNotEmpty()) mapped else source.enabledDictionaries
            } else {
                source.enabledDictionaries
            }
            val resolvedDisplayModes = if (allNames.isNotEmpty() && context != null && source.dictionaryDisplayModes.isNotEmpty()) {
                val modes = LinkedHashMap(source.dictionaryDisplayModes)
                source.dictionaryDisplayModes.forEach { (dictName, mode) ->
                    matchDictionaryName(dictName, allNames, context)?.let { matched ->
                        modes[matched] = mode
                    }
                }
                modes
            } else {
                source.dictionaryDisplayModes
            }

            DictionaryProfile(
                id = current?.id ?: UUID.randomUUID().toString(),
                name = source.name,
                dictionaryOrder = resolvedOrder,
                enabledDictionaries = resolvedEnabled,
                // Yomitan has no equivalent of this komi-kun-only mode, so an in-place update keeps
                // the user's choice; a genuinely new profile gets the default.
                dictionaryCollapseMode = current?.dictionaryCollapseMode
                    ?: DictionaryProfile.DICTIONARY_COLLAPSE_EXPAND_ALL,
                dictionaryDisplayModes = resolvedDisplayModes,
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

    private fun applyAudio(settings: YomitanSettingsImport, prefs: DictionaryPreferences) {
        val audio = settings.audio
        audio.enabled?.let { prefs.audioEnabled().set(it) }
        audio.autoPlay?.let { prefs.audioAutoPlay().set(it) }
        audio.volumePercent?.let { prefs.audioVolume().set(it.coerceIn(0, 100)) }
        audio.enableDefaultAudioSources?.let { prefs.audioEnableDefaultSources().set(it) }
        audio.fallbackSoundType?.let { prefs.audioFallbackSound().set(it) }
        // Sources (Custom URL / Custom URL JSON, incl. the user's Local Audio Server) are replaced
        // wholesale, mirroring Yomitan's ordered `audio.sources[]`.
        if (audio.sources.isNotEmpty()) prefs.setAudioSources(audio.sources)
    }
}
// KUN <--
