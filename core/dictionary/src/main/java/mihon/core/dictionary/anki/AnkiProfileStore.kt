// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported/adapted from Chimahon's `chimahon/anki/AnkiProfileStore.kt` (GPL-3.0), switched from
// `org.json` to kotlinx.serialization so it is JVM-testable (plan 05 §A3/B7).
package mihon.core.dictionary.anki

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Manages a list of [AnkiProfile]s backed by two plain String preference values supplied by the
 * caller:
 *
 *   - [readProfiles]/[writeProfiles] – the raw JSON array string
 *   - [readActiveId]/[writeActiveId] – the ID string of the currently active profile
 *
 * All persistence is delegated back to the caller so this class has zero Android/PreferenceStore
 * dependencies and can live in `:core:dictionary`.
 */
class AnkiProfileStore(
    private val readProfiles: () -> String,
    private val writeProfiles: (String) -> Unit,
    private val readActiveId: () -> String,
    private val writeActiveId: (String) -> Unit,
) {

    fun getProfiles(): List<AnkiProfile> {
        val raw = readProfiles().trim()
        if (raw.isBlank() || raw == "[]") return emptyList()
        return runCatching {
            val array = AnkiJson.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
            array.map { AnkiProfile.fromJson(it.jsonObject) }
        }.getOrDefault(emptyList())
    }

    fun getActiveProfile(): AnkiProfile {
        val profiles = getProfiles()
        if (profiles.isEmpty()) return createFallbackDefault()
        val activeId = readActiveId()
        return profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    }

    fun getActiveId(): String = readActiveId()

    fun saveProfiles(profiles: List<AnkiProfile>) {
        val array = JsonArray(profiles.map { it.toJson() })
        writeProfiles(AnkiJson.encodeToString(JsonArray.serializer(), array))
    }

    fun setActiveProfile(id: String) {
        writeActiveId(id)
    }

    fun addProfile(profile: AnkiProfile) {
        saveProfiles(getProfiles() + profile)
        // Auto-select the new profile.
        writeActiveId(profile.id)
    }

    fun updateProfile(profile: AnkiProfile) {
        val profiles = getProfiles()
        if (profiles.isEmpty()) {
            saveProfiles(listOf(profile))
            writeActiveId(profile.id)
            return
        }
        saveProfiles(profiles.map { if (it.id == profile.id) profile else it })
    }

    /**
     * Deletes the profile with [id]. Returns false (and does nothing) if it is the last profile, so
     * the caller can show the user an appropriate message.
     */
    fun deleteProfile(id: String): Boolean {
        val profiles = getProfiles()
        if (profiles.size <= 1) return false
        val updated = profiles.filter { it.id != id }
        saveProfiles(updated)
        // If the active profile was deleted, fall back to the first remaining one.
        if (readActiveId() == id) {
            writeActiveId(updated.first().id)
        }
        return true
    }

    /**
     * One-time migration: if the profiles list is empty, build a "Default" profile from the legacy
     * flat-key values passed in by the caller and persist it.
     */
    fun migrateIfEmpty(defaultName: String, legacyValues: LegacyAnkiValues) {
        if (getProfiles().isNotEmpty()) return
        val profile = AnkiProfile.createDefault(
            defaultName = defaultName,
            ankiDeck = legacyValues.deck,
            ankiModel = legacyValues.model,
            ankiFieldMap = legacyValues.fieldMap,
            ankiTags = legacyValues.tags,
            ankiDupCheck = legacyValues.dupCheck,
            ankiDupScope = legacyValues.dupScope,
            ankiDupAction = legacyValues.dupAction,
            ankiCropMode = legacyValues.cropMode,
        )
        saveProfiles(listOf(profile))
        writeActiveId(profile.id)
    }

    private fun createFallbackDefault(): AnkiProfile = AnkiProfile.createDefault()

    /** Value class for passing legacy flat-pref values during migration. */
    data class LegacyAnkiValues(
        val deck: String = "",
        val model: String = "",
        val fieldMap: String = "{}",
        val tags: String = AnkiProfile.DEFAULT_TAG,
        val dupCheck: Boolean = true,
        val dupScope: String = "deck",
        val dupAction: String = "prevent",
        val cropMode: String = "full",
    )
}
// KUN <--
