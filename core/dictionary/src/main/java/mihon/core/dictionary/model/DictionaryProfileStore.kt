// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.model

import org.json.JSONArray

/**
 * Manages a list of [DictionaryProfile]s backed by two plain string preference values supplied
 * by the caller (02 §4):
 *
 *   - [readProfiles]/[writeProfiles] – the raw JSON array string
 *   - [readActiveId]/[writeActiveId] – the ID string of the currently active profile
 *
 * Persistence is delegated back to the caller so this class has zero Android/PreferenceStore
 * dependencies and can live in `:core:dictionary`.
 */
class DictionaryProfileStore(
    private val readProfiles: () -> String,
    private val writeProfiles: (String) -> Unit,
    private val readActiveId: () -> String,
    private val writeActiveId: (String) -> Unit,
) {

    fun getProfiles(): List<DictionaryProfile> {
        val raw = readProfiles().trim()
        if (raw.isBlank() || raw == "[]") return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { DictionaryProfile.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun getActiveProfile(): DictionaryProfile {
        val profiles = getProfiles()
        if (profiles.isEmpty()) return createFallbackDefault()
        val activeId = readActiveId()
        return profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    }

    fun getActiveId(): String = readActiveId()

    fun saveProfiles(profiles: List<DictionaryProfile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(it.toJson()) }
        writeProfiles(arr.toString())
    }

    fun setActiveProfile(id: String) {
        writeActiveId(id)
    }

    fun addProfile(profile: DictionaryProfile) {
        saveProfiles(getProfiles() + profile)
        writeActiveId(profile.id)
    }

    fun updateProfile(profile: DictionaryProfile) {
        val profiles = getProfiles()
        if (profiles.isEmpty()) {
            saveProfiles(listOf(profile))
            writeActiveId(profile.id)
            return
        }
        saveProfiles(profiles.map { if (it.id == profile.id) profile else it })
    }

    /** Delete [id]; returns false (and does nothing) when it is the last profile. */
    fun deleteProfile(id: String): Boolean {
        val profiles = getProfiles()
        if (profiles.size <= 1) return false
        val updated = profiles.filter { it.id != id }
        saveProfiles(updated)
        if (readActiveId() == id) {
            writeActiveId(updated.first().id)
        }
        return true
    }

    /** One-time migration: build a "Default" profile from the on-disk dictionary list when empty. */
    fun migrateIfEmpty(defaultName: String, allDictNames: List<String>) {
        if (getProfiles().isNotEmpty()) return
        val profile = DictionaryProfile.createDefault(
            defaultName = defaultName,
            dictionaryOrder = allDictNames,
        )
        saveProfiles(listOf(profile))
        writeActiveId(profile.id)
    }

    private fun createFallbackDefault(): DictionaryProfile = DictionaryProfile.createDefault()
}
