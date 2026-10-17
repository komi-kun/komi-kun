// SPDX-License-Identifier: GPL-3.0-or-later
package eu.kanade.tachiyomi.ui.dictionary

import mihon.core.dictionary.audio.AudioFallbackSound
import mihon.core.dictionary.audio.AudioPreferences
import mihon.core.dictionary.audio.AudioSourceConfig
import mihon.core.dictionary.model.DictionaryProfileStore
import mihon.core.dictionary.repository.DictionaryProfileResolver
import org.json.JSONObject
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Dictionary preferences (02 §4/§5.3), provided from the shared [PreferenceStore] by
 * `DictionaryHostBindings` (contract §3.6c).
 *
 * This is the manga-only adaptation of Chimahon's `DictionaryPreferences`: anime/novel/screen-OCR
 * and word-audio keys are dropped. It also owns the [DictionaryProfileStore]/[DictionaryProfileResolver]
 * singletons and the `pref_dict_profile_*` override keys.
 */
class DictionaryPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun popupWidth() = preferenceStore.getInt("pref_dictionary_popup_width", 300)
    fun popupHeight() = preferenceStore.getInt("pref_dictionary_popup_height", 360)
    fun popupMode() = preferenceStore.getString("pref_dictionary_popup_mode", "floating")
    fun fontSize() = preferenceStore.getInt("pref_dictionary_font_size", 16)
    fun fontFamily() = preferenceStore.getString("pref_dictionary_font_family", "")

    fun showFrequencyHarmonic() = preferenceStore.getBoolean("pref_dict_show_frequency_harmonic", false)
    fun showFrequencyAverage() = preferenceStore.getBoolean("pref_dict_show_frequency_average", false)
    fun groupTerms() = preferenceStore.getBoolean("pref_dict_group_terms", true)
    fun compactGlossary() = preferenceStore.getBoolean("pref_dict_compact_glossary", false)
    fun showPitchDiagram() = preferenceStore.getBoolean("pref_dict_show_pitch_diagram", true)
    fun showPitchNumber() = preferenceStore.getBoolean("pref_dict_show_pitch_number", true)
    fun showPitchText() = preferenceStore.getBoolean("pref_dict_show_pitch_text", true)
    fun groupPitches() = preferenceStore.getBoolean("pref_dict_group_pitches", false)
    fun showNavigationButtons() = preferenceStore.getBoolean("pref_dict_show_navigation_buttons", true)
    fun autoKanaConversion() = preferenceStore.getBoolean("pref_dict_auto_kana_conversion", true)

    fun recursiveLookupMode() = preferenceStore.getString("pref_dict_recursive_lookup_mode", "tabs")
    fun popupSwipeToDismiss() = preferenceStore.getBoolean("pref_dictionary_popup_swipe_to_dismiss", true)
    fun popupSwipeThreshold() = preferenceStore.getInt("pref_dictionary_popup_swipe_threshold", 56)

    fun themeMode(): Preference<String> {
        // One-time migration from the old boolean pref.
        val oldPref = preferenceStore.getBoolean("pref_dictionary_theme_dark_amoled", false)
        if (oldPref.isSet()) {
            val oldValue = oldPref.get()
            oldPref.delete()
            val newPref = preferenceStore.getString("pref_dictionary_theme_mode", "system")
            newPref.set(if (oldValue) "pure_black" else "system")
            return newPref
        }
        return preferenceStore.getString("pref_dictionary_theme_mode", "system")
    }

    fun customColor() = preferenceStore.getInt("pref_dictionary_custom_color", 0)
    fun eInkMode() = preferenceStore.getBoolean(Preference.appStateKey("pref_dictionary_eink_mode"), false)
    fun paginatedScrolling() = preferenceStore.getBoolean("pref_dictionary_paginated_scrolling", false)
    fun paginatedScrollStepSize() = preferenceStore.getInt("pref_dictionary_paginated_scroll_step_size", 90)
    fun scrollBehavior() = preferenceStore.getString("pref_dictionary_scroll_behavior", SCROLL_SMOOTH)
    fun volumeKeyNavigation() = preferenceStore.getBoolean("pref_dictionary_volume_key_navigation", true)
    fun customCss() = preferenceStore.getString("pref_dictionary_custom_css", "")

    // -------------------------------------------------------------------------
    // Word audio (Yomitan `options.audio`) — model/types live in :core:dictionary
    // -------------------------------------------------------------------------

    fun audioEnabled() = preferenceStore.getBoolean("pref_dict_audio_enabled", true)
    fun audioAutoPlay() = preferenceStore.getBoolean("pref_dict_audio_autoplay", false)
    fun audioVolume() = preferenceStore.getInt("pref_dict_audio_volume", 100)
    fun audioEnableDefaultSources() = preferenceStore.getBoolean("pref_dict_audio_default_sources", true)
    fun audioFallbackSound() = preferenceStore.getString("pref_dict_audio_fallback_sound", AudioFallbackSound.CLICK)
    fun audioSourcesJson() = preferenceStore.getString("pref_dict_audio_sources", "[]")

    /** The configured `audio.sources[]`. Malformed entries are dropped, never fatal. */
    fun getAudioSources(): List<AudioSourceConfig> {
        val raw = audioSourcesJson().get()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val type = obj.optString("type")
                if (type.isBlank()) return@mapNotNull null
                AudioSourceConfig(
                    type = type,
                    url = obj.optString("url"),
                    voice = obj.optString("voice"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun setAudioSources(sources: List<AudioSourceConfig>) {
        val array = org.json.JSONArray()
        sources.forEach { source ->
            array.put(
                org.json.JSONObject().apply {
                    put("type", source.type)
                    put("url", source.url)
                    put("voice", source.voice)
                },
            )
        }
        audioSourcesJson().set(array.toString())
    }

    /** Snapshot of the live audio configuration for the WebView bridge and playback. */
    fun audioPreferences(): AudioPreferences = AudioPreferences(
        enabled = audioEnabled().get(),
        autoPlay = audioAutoPlay().get(),
        volumePercent = audioVolume().get(),
        sources = getAudioSources(),
        enableDefaultAudioSources = audioEnableDefaultSources().get(),
        fallbackSoundType = audioFallbackSound().get(),
    )

    // -------------------------------------------------------------------------
    // Profile storage (raw pref keys consumed by DictionaryProfileStore and settings UI)
    // -------------------------------------------------------------------------

    fun rawProfiles() = preferenceStore.getString("pref_anki_profiles", "[]")
    fun rawActiveProfileId() = preferenceStore.getString("pref_active_profile_id", "")

    val profileStore: DictionaryProfileStore by lazy {
        DictionaryProfileStore(
            readProfiles = { rawProfiles().get() },
            writeProfiles = { rawProfiles().set(it) },
            readActiveId = { rawActiveProfileId().get() },
            writeActiveId = { rawActiveProfileId().set(it) },
        )
    }

    val profileResolver: DictionaryProfileResolver by lazy {
        DictionaryProfileResolver(
            profileStore = profileStore,
            readMangaOverride = { mangaId ->
                preferenceStore.getString(DictionaryProfileResolver.mangaOverrideKey(mangaId), "").get()
            },
            readSourceOverride = { sourceId ->
                preferenceStore.getString(DictionaryProfileResolver.sourceOverrideKey(sourceId), "").get()
            },
        )
    }

    fun rawProfileOverride(key: String) = preferenceStore.getString(key, "")

    /** Delete a profile and sweep any manga/source override keys pointing at it (02 §4). */
    fun deleteProfileWithOverrides(profileId: String): Boolean {
        if (!profileStore.deleteProfile(profileId)) return false
        val prefixes = listOf("pref_dict_profile_manga_", "pref_dict_profile_source_")
        preferenceStore.getAll().keys
            .filter { key -> prefixes.any { key.startsWith(it) } }
            .forEach { key ->
                val pref = preferenceStore.getString(key, "")
                if (pref.get() == profileId) pref.delete()
            }
        return true
    }

    // -------------------------------------------------------------------------
    // Display names (dirName → displayName, stored as a JSON map)
    // -------------------------------------------------------------------------

    fun displayNames() = preferenceStore.getString("pref_display_names", "{}")

    fun getDisplayName(dirName: String): String? {
        val json = displayNames().get()
        if (json.isBlank() || json == "{}") return null
        return runCatching { JSONObject(json).optString(dirName, "").ifEmpty { null } }.getOrNull()
    }

    fun setDisplayName(dirName: String, displayName: String?) {
        val obj = runCatching { JSONObject(displayNames().get().ifBlank { "{}" }) }.getOrElse { JSONObject() }
        if (displayName != null) obj.put(dirName, displayName) else obj.remove(dirName)
        displayNames().set(obj.toString())
    }

    // -------------------------------------------------------------------------
    // Dictionary auto-update (02 §6.4)
    // -------------------------------------------------------------------------

    fun autoUpdateEnabled() = preferenceStore.getBoolean("pref_dict_auto_update", false)
    fun autoUpdateInterval() = preferenceStore.getInt("pref_dict_auto_update_interval", 24)
    fun lastDictUpdateCheck() = preferenceStore.getLong("pref_last_dict_update_check", 0L)
    fun dictUpdateCheckState() = preferenceStore.getString("pref_dict_update_check_state", "idle")

    companion object {
        const val FREQ_HARMONIC = "harmonic"
        const val FREQ_AVERAGE = "average"
        const val SCROLL_SMOOTH = "smooth"
        const val SCROLL_INSTANT = "instant"
    }
}
