// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The Anki screenshot crop-preset vocabulary the settings picker offers, pinned against what the
// reader can actually resolve (`CropPresets`) and against the preference's default.
//
// The failure this catches: the picker offering a key the reader cannot map (`ReaderOcrHostImpl`
// resolves it through `CropPresets.aspectByKey`), which would silently crop "the block" instead of
// the chosen ratio, or a default that is not in the picker at all (no row selected).
// KUN <--
package eu.kanade.presentation.more.settings.screen

import io.kotest.matchers.shouldBe
import mihon.core.dictionary.anki.AnkiPreferences
import mihon.core.ocr.CropPresets
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class AnkiScreenshotPresetOptionsTest {

    @Test
    fun `the picker offers no fixed ratio plus every shared crop preset`() {
        AnkiScreenshotPresetOptions.keys shouldBe listOf(
            "full",
            "1:1",
            "4:3",
            "3:2",
            "16:9",
            "9:16",
            "4:5",
            "3:4",
        )
    }

    @Test
    fun `every offered preset resolves to the aspect the reader uses`() {
        // The reader maps the stored key to an aspect via `CropPresets.aspectByKey`; each picker entry
        // must resolve to the numbers it advertises, and "full" must resolve to nothing (no aspect
        // clamp) rather than to a stray preset.
        AnkiScreenshotPresetOptions.keys.forEach { key ->
            if (key == AnkiScreenshotPresetOptions.FULL) {
                CropPresets.aspectByKey(key) shouldBe null
                AnkiScreenshotPresetOptions.presetLabel(key) shouldBe null
            } else {
                val preset = requireNotNull(CropPresets.aspectByKey(key)) { "unknown preset key: $key" }
                preset.label shouldBe key
                AnkiScreenshotPresetOptions.presetLabel(key) shouldBe key
            }
        }
        // Spot-check the arithmetic the reader relies on (x:y, not the label string).
        CropPresets.aspectByKey("4:3")!!.let { it.x to it.y } shouldBe (4 to 3)
        CropPresets.aspectByKey("9:16")!!.let { it.x to it.y } shouldBe (9 to 16)
    }

    @Test
    fun `the preference default is one of the offered presets`() {
        // A default outside the vocabulary would leave the picker with no selected entry.
        AnkiPreferences(InMemoryPreferenceStore()).cropPreset().get() shouldBe
            AnkiScreenshotPresetOptions.DEFAULT
        AnkiScreenshotPresetOptions.DEFAULT shouldBe AnkiScreenshotPresetOptions.FULL
        (AnkiScreenshotPresetOptions.DEFAULT in AnkiScreenshotPresetOptions.keys) shouldBe true
    }
}
// KUN <--
