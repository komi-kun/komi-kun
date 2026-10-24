// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.anki

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore

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
     * Seeded rather than written: `InMemoryPreferenceStore` hands out a fresh wrapper per `getString`,
     * so a `.set()` here would not be visible to the next read.
     */
    private fun store(flatMode: String?, flatPreset: String?): PreferenceStore = InMemoryPreferenceStore(
        sequenceOf(
            InMemoryPreferenceStore.InMemoryPreference("pref_anki_crop_mode", flatMode, "full"),
            InMemoryPreferenceStore.InMemoryPreference("pref_anki_crop_preset", flatPreset, "full"),
            InMemoryPreferenceStore.InMemoryPreference("pref_anki_mining_profiles", "[${staleProfile.toJsonString()}]", "[]"),
            InMemoryPreferenceStore.InMemoryPreference("pref_anki_mining_active_profile_id", staleProfile.id, ""),
        ),
    )

    @Test
    fun `the mode follows the flat preference, not the stale profile`() {
        val prefs = AnkiPreferences(store(flatMode = "no_screenshot", flatPreset = "4:3"))

        // The premise: what the settings edit leaves behind in the persisted profile.
        prefs.activeProfile().ankiCropMode shouldBe "full"
        prefs.activeProfile().ankiCropPreset shouldBe "full"

        AnkiScreenshotSettings.mode(prefs) shouldBe AnkiScreenshotMode.NONE
        AnkiScreenshotSettings.presetKey(prefs) shouldBe "4:3"
    }

    @Test
    fun `every storage value the settings screen writes is the mode mining reads back`() {
        for (mode in AnkiScreenshotMode.entries) {
            val prefs = AnkiPreferences(store(flatMode = mode.storageValue, flatPreset = "full"))
            AnkiScreenshotSettings.mode(prefs) shouldBe mode
        }
    }

    @Test
    fun `an unset preference keeps the historical whole page capture`() {
        // Never opened the settings screen: capture the page rather than silently dropping the image.
        val prefs = AnkiPreferences(store(flatMode = null, flatPreset = null))

        AnkiScreenshotSettings.mode(prefs) shouldBe AnkiScreenshotMode.FULL
        AnkiScreenshotSettings.presetKey(prefs) shouldBe "full"
    }
}
// KUN <--
