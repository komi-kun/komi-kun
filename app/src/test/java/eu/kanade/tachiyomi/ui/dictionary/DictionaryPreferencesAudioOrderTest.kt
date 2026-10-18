// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Pins Feature 2: the order of the imported Yomitan `audio.sources[]` must survive the write-back
// into preferences and the read-back the playback path (and the audio picker) uses. The mapper
// test pins the parse order; this pins the app-side round-trip so nothing sorts or reorders it.
//
// `InMemoryPreferenceStore` does not persist `.set()` on keys it was not seeded with, so a tiny
// map-backed store is used here to exercise the real write-then-read path.
package eu.kanade.tachiyomi.ui.dictionary

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import mihon.core.dictionary.anki.AnkiPreferences
import mihon.core.dictionary.audio.AudioSourceConfig
import mihon.core.dictionary.audio.AudioSourceTypes
import mihon.core.dictionary.yomitan.YomitanImportAnki
import mihon.core.dictionary.yomitan.YomitanImportAudio
import mihon.core.dictionary.yomitan.YomitanImportPopup
import mihon.core.dictionary.yomitan.YomitanSettingsImport
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class DictionaryPreferencesAudioOrderTest {

    private fun sources() = listOf(
        AudioSourceConfig(
            type = AudioSourceTypes.CUSTOM_JSON,
            url = "http://127.0.0.1:8765/localaudio/get/?term={term}&reading={reading}",
        ),
        AudioSourceConfig(type = AudioSourceTypes.JPOD_101),
        AudioSourceConfig(type = AudioSourceTypes.CUSTOM, url = "http://third/?t={term}"),
        AudioSourceConfig(type = AudioSourceTypes.CUSTOM_JSON, url = "http://forvo.example/json?t={term}"),
    )

    @Test
    fun `audio sources round-trip through prefs in the configured order`() {
        val prefs = DictionaryPreferences(MapPreferenceStore())
        val configured = sources()

        prefs.setAudioSources(configured)

        prefs.getAudioSources() shouldBe configured
        prefs.audioPreferences().sources shouldBe configured
    }

    @Test
    fun `the settings applier writes the backup audio order verbatim`() {
        val store = MapPreferenceStore()
        val prefs = DictionaryPreferences(store)
        val configured = sources()

        val settings = YomitanSettingsImport(
            formatVersion = 0,
            optionsVersion = 77,
            activeProfileName = null,
            profiles = emptyList(),
            popup = YomitanImportPopup(),
            anki = YomitanImportAnki(),
            audio = YomitanImportAudio(sources = configured, enableDefaultAudioSources = false),
            aliases = emptyMap(),
            reports = emptyList(),
            warnings = emptyList(),
        )

        YomitanSettingsApplier.apply(settings, prefs, AnkiPreferences(store))

        // First configured source stays first, repeated custom-json types keep their position.
        prefs.getAudioSources() shouldBe configured
        prefs.audioPreferences().sources.map { it.url } shouldBe configured.map { it.url }
    }
}

/** Minimal map-backed [PreferenceStore] that persists writes by key (unlike InMemoryPreferenceStore). */
private class MapPreferenceStore : PreferenceStore {
    private val map = mutableMapOf<String, Any?>()

    private fun <T : Any> pref(key: String, default: T): Preference<T> = object : Preference<T> {
        @Suppress("UNCHECKED_CAST")
        override fun get(): T = (map[key] as? T) ?: default
        override fun set(value: T) {
            map[key] = value
        }
        override fun key(): String = key
        override fun isSet(): Boolean = map.containsKey(key)
        override fun delete() {
            map.remove(key)
        }
        override fun defaultValue(): T = default
        override fun changes(): Flow<T> = flow { emit(get()) }
        override fun stateIn(scope: CoroutineScope): StateFlow<T> =
            changes().stateIn(scope, SharingStarted.Eagerly, get())
    }

    private fun <T, S> serializedPref(
        key: String,
        default: T,
        serializer: (T) -> S,
        deserializer: (S) -> T,
    ): Preference<T> = object : Preference<T> {
        @Suppress("UNCHECKED_CAST")
        override fun get(): T = (map[key] as? S)?.let(deserializer) ?: default
        override fun set(value: T) {
            map[key] = serializer(value)
        }
        override fun key(): String = key
        override fun isSet(): Boolean = map.containsKey(key)
        override fun delete() {
            map.remove(key)
        }
        override fun defaultValue(): T = default
        override fun changes(): Flow<T> = flow { emit(get()) }
        override fun stateIn(scope: CoroutineScope): StateFlow<T> =
            changes().stateIn(scope, SharingStarted.Eagerly, get())
    }

    override fun getString(key: String, defaultValue: String) = pref(key, defaultValue)
    override fun getLong(key: String, defaultValue: Long) = pref(key, defaultValue)
    override fun getInt(key: String, defaultValue: Int) = pref(key, defaultValue)
    override fun getFloat(key: String, defaultValue: Float) = pref(key, defaultValue)
    override fun getBoolean(key: String, defaultValue: Boolean) = pref(key, defaultValue)
    override fun getStringSet(key: String, defaultValue: Set<String>) = pref(key, defaultValue)

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = serializedPref(key, defaultValue, serializer, deserializer)

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = serializedPref(key, defaultValue, serializer, deserializer)

    override fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): Preference<Set<T>> = serializedPref<Set<T>, Set<String>>(
        key,
        defaultValue,
        serializer = { it.map(serializer).toSet() },
        deserializer = { it.mapNotNull(deserializer).toSet() },
    )

    override fun getAll(): Map<String, *> = map
}
// KUN <--
