// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Pins Feature 2: the order of the imported Yomitan `audio.sources[]` must survive the write-back
// into preferences and the read-back the playback path (and the audio picker) uses. The mapper
// test pins the parse order; this pins the app-side round-trip so nothing sorts or reorders it.
//
// `InMemoryPreferenceStore` writes through by key, so the real write-then-read path runs here
// against the shared fake instead of a bespoke store.
package eu.kanade.tachiyomi.ui.dictionary

import io.kotest.matchers.shouldBe
import mihon.core.dictionary.anki.AnkiPreferences
import mihon.core.dictionary.audio.AudioSourceConfig
import mihon.core.dictionary.audio.AudioSourceTypes
import mihon.core.dictionary.yomitan.YomitanImportAnki
import mihon.core.dictionary.yomitan.YomitanImportAudio
import mihon.core.dictionary.yomitan.YomitanImportPopup
import mihon.core.dictionary.yomitan.YomitanSettingsImport
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

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
        val prefs = DictionaryPreferences(InMemoryPreferenceStore())
        val configured = sources()

        prefs.setAudioSources(configured)

        prefs.getAudioSources() shouldBe configured
        prefs.audioPreferences().sources shouldBe configured
    }

    @Test
    fun `the settings applier writes the backup audio order verbatim`() {
        val store = InMemoryPreferenceStore()
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

// KUN <--
