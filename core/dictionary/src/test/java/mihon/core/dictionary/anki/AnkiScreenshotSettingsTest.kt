// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.anki

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * Pins *where* the live screenshot settings are read from.
 *
 * The settings screen's `ListPreference` persists the profile **before** it writes the chosen value
 * (`PreferenceItem.kt`: `if (item.internalOnValueChanged(newValue)) item.internalSet(newValue)`), so
 * right after choosing "No screenshot" the persisted profile still says `full`. Reading the profile
 * would keep capturing screenshots the user just turned off; the flat keys are the live source.
 */
class AnkiScreenshotSettingsTest {

    private val staleProfile = AnkiProfile(id = "p1", name = "Default", ankiCropMode = "full", ankiCropPreset = "full")

    /**
     * A store with a persisted (stale) mining profile — the state a settings edit leaves behind. The
     * flat crop keys are *written* by each test, exactly like the settings screen writes them.
     */
    private fun store() = InMemoryPreferenceStore(
        sequenceOf(
            InMemoryPreferenceStore.InMemoryPreference("pref_anki_mining_profiles", "[${staleProfile.toJsonString()}]", "[]"),
            InMemoryPreferenceStore.InMemoryPreference("pref_anki_mining_active_profile_id", staleProfile.id, ""),
        ),
    )

    @Test
    fun `the mode follows the flat preference, not the stale profile`() {
        val prefs = AnkiPreferences(store())
        prefs.cropMode().set("no_screenshot")
        prefs.cropPreset().set("4:3")

        // The premise: what the settings edit leaves behind in the persisted profile.
        prefs.activeProfile().ankiCropMode shouldBe "full"
        prefs.activeProfile().ankiCropPreset shouldBe "full"

        AnkiScreenshotSettings.mode(prefs) shouldBe AnkiScreenshotMode.NONE
        AnkiScreenshotSettings.presetKey(prefs) shouldBe "4:3"
    }

    @Test
    fun `every storage value the settings screen writes is the mode mining reads back`() {
        for (mode in AnkiScreenshotMode.entries) {
            val prefs = AnkiPreferences(store())
            prefs.cropMode().set(mode.storageValue)
            AnkiScreenshotSettings.mode(prefs) shouldBe mode
        }
    }

    @Test
    fun `every crop preset the settings picker offers is readable back unchanged`() {
        // The picker's vocabulary is `CropPresets` keys plus "full"; the reader maps the key to an
        // aspect, so an unknown key would silently mean "no crop" instead of the chosen preset.
        for (key in ANKI_CROP_PRESET_KEYS) {
            val prefs = AnkiPreferences(store())
            prefs.cropPreset().set(key)
            AnkiScreenshotSettings.presetKey(prefs) shouldBe key
        }
    }

    @Test
    fun `an unset preference keeps the historical whole page capture`() {
        // Never opened the settings screen: capture the page rather than silently dropping the image.
        val prefs = AnkiPreferences(store())

        AnkiScreenshotSettings.mode(prefs) shouldBe AnkiScreenshotMode.FULL
        AnkiScreenshotSettings.presetKey(prefs) shouldBe "full"
    }

    private companion object {
        /** The storage values the settings picker offers: no forced aspect, then the preset keys. */
        val ANKI_CROP_PRESET_KEYS = listOf("full", "1:1", "4:3", "3:2", "16:9", "9:16", "4:5", "3:4")
    }
}
// KUN <--
