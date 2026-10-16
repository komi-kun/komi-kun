// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Anki preference accessor (adapted from Chimahon's `DictionaryPreferences` Anki keys). Unlike
// Chimahon, the fork keeps Anki profiles separate from dictionary profiles, so the mining-profile
// list uses its own keys (`pref_anki_mining_profiles`) — `pref_anki_profiles` now stores the
// dictionary profiles (`DictionaryPreferences.rawProfiles()`).
package mihon.core.dictionary.anki

import tachiyomi.core.common.preference.PreferenceStore

/**
 * Reads/writes the flat Anki preference keys and owns the [AnkiProfileStore]. Android-free
 * (only depends on `PreferenceStore`) so it can be constructed from any `Context`'s default store.
 */
class AnkiPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun enabled() = preferenceStore.getBoolean("pref_anki_enabled", false)
    fun deck() = preferenceStore.getString("pref_anki_deck", "")
    fun model() = preferenceStore.getString("pref_anki_model", LapisPreset.MODEL_NAME)
    fun fieldMap() = preferenceStore.getString("pref_anki_field_map", LapisPreset.defaultFieldMapJson)
    fun defaultTags() = preferenceStore.getString("pref_anki_default_tags", AnkiProfile.DEFAULT_TAG)
    fun duplicateCheck() = preferenceStore.getBoolean("pref_anki_duplicate_check", true)
    fun duplicateScope() = preferenceStore.getString("pref_anki_duplicate_scope", "deck")
    fun duplicateAction() = preferenceStore.getString("pref_anki_duplicate_action", "prevent")
    fun cropMode() = preferenceStore.getString("pref_anki_crop_mode", "full")
    fun cropPreset() = preferenceStore.getString("pref_anki_crop_preset", "full")
    fun syncOnCreate() = preferenceStore.getBoolean("pref_anki_sync_on_create", false)

    private fun rawProfiles() = preferenceStore.getString("pref_anki_mining_profiles", "[]")
    private fun rawActiveProfileId() = preferenceStore.getString("pref_anki_mining_active_profile_id", "")

    val profileStore: AnkiProfileStore by lazy {
        AnkiProfileStore(
            readProfiles = { rawProfiles().get() },
            writeProfiles = { rawProfiles().set(it) },
            readActiveId = { rawActiveProfileId().get() },
            writeActiveId = { rawActiveProfileId().set(it) },
        )
    }

    /** One-time migration: build a "Default" profile from the legacy flat keys when none exist. */
    fun migrateIfEmpty(defaultName: String = "Default") {
        profileStore.migrateIfEmpty(
            defaultName = defaultName,
            legacyValues = AnkiProfileStore.LegacyAnkiValues(
                deck = deck().get(),
                model = model().get(),
                fieldMap = fieldMap().get(),
                tags = defaultTags().get(),
                dupCheck = duplicateCheck().get(),
                dupScope = duplicateScope().get(),
                dupAction = duplicateAction().get(),
                cropMode = cropMode().get(),
            ),
        )
    }

    fun profiles(): List<AnkiProfile> = profileStore.getProfiles()

    fun activeId(): String = profileStore.getActiveId()

    /**
     * The profile the miner should use: the persisted active profile when any exists, otherwise
     * the flat-key view (so the very first run, before the settings screen has migrated, still
     * picks up whatever the user configured).
     */
    fun activeProfile(): AnkiProfile {
        val profiles = profileStore.getProfiles()
        return if (profiles.isNotEmpty()) profileStore.getActiveProfile() else readFlatProfile("", "Default")
    }

    /** Persist the flat keys into the active profile (used after a settings edit). */
    fun persistActiveFromFlat(): AnkiProfile {
        val active = profileStore.getActiveProfile()
        val updated = readFlatProfile(active.id, active.name)
        profileStore.updateProfile(updated)
        return updated
    }

    /** Copy the flat keys into [profile] (used by the settings UI when editing the active profile). */
    fun readFlatProfile(id: String, name: String): AnkiProfile = AnkiProfile(
        id = id,
        name = name,
        ankiEnabled = enabled().get(),
        ankiDeck = deck().get(),
        ankiModel = model().get(),
        ankiFieldMap = fieldMap().get(),
        ankiTags = defaultTags().get(),
        ankiDupCheck = duplicateCheck().get(),
        ankiDupScope = duplicateScope().get(),
        ankiDupAction = duplicateAction().get(),
        ankiCropMode = cropMode().get(),
        ankiCropPreset = cropPreset().get(),
        ankiSyncOnCreate = syncOnCreate().get(),
    )

    /** Write [profile] back to the flat keys. */
    fun writeFlatProfile(profile: AnkiProfile) {
        enabled().set(profile.ankiEnabled)
        deck().set(profile.ankiDeck)
        model().set(profile.ankiModel)
        fieldMap().set(profile.ankiFieldMap)
        defaultTags().set(profile.ankiTags)
        duplicateCheck().set(profile.ankiDupCheck)
        duplicateScope().set(profile.ankiDupScope)
        duplicateAction().set(profile.ankiDupAction)
        cropMode().set(profile.ankiCropMode)
        cropPreset().set(profile.ankiCropPreset)
        syncOnCreate().set(profile.ankiSyncOnCreate)
    }
}
// KUN <--
