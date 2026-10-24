// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The reader-side crop-preset mapping `ReaderOcrHostImpl.screenshotProviderFor` uses: the stored key
// must become the aspect the user chose, not "no aspect" and not a transposed one.
//
// `AnkiScreenshotPresetOptionsTest` only pins the *vocabulary* (the picker and `CropPresets` agree on
// the keys). This is the other half: `"4:3"` really turns into `AnkiCropAspect(4, 3)` when the reader
// builds the card screenshot, and `"full"` really means "no forced aspect". Swapping x/y or dropping
// the lookup would leave the vocabulary test green while every mined card was cropped wrong.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.ocr.impl

import eu.kanade.presentation.more.settings.screen.AnkiScreenshotPresetOptions
import io.kotest.matchers.shouldBe
import mihon.core.dictionary.anki.AnkiCropAspect
import org.junit.jupiter.api.Test

class AnkiCropPresetAspectTest {

    @Test
    fun `a chosen preset becomes the aspect with its x and y in that order`() {
        AnkiCropPresetAspect.fromKey("4:3") shouldBe AnkiCropAspect(4, 3)
        // Landscape vs portrait presets must not be transposed: `9:16` is the narrow one.
        AnkiCropPresetAspect.fromKey("9:16") shouldBe AnkiCropAspect(9, 16)
        AnkiCropPresetAspect.fromKey("1:1") shouldBe AnkiCropAspect(1, 1)
    }

    @Test
    fun `full means no forced aspect`() {
        // The default preference value: the crop follows the block (`AnkiScreenshotCrop.DEFAULT_MARGIN`).
        AnkiCropPresetAspect.fromKey(AnkiScreenshotPresetOptions.FULL) shouldBe null
    }

    @Test
    fun `an unknown or blank key means no forced aspect instead of a stray ratio`() {
        AnkiCropPresetAspect.fromKey("") shouldBe null
        AnkiCropPresetAspect.fromKey("42:1") shouldBe null
        AnkiCropPresetAspect.fromKey("4x3") shouldBe null
    }

    @Test
    fun `every key the picker offers maps to the ratio it is labelled with`() {
        // Same source of truth as the settings picker, so a preset added to `CropPresets` without a
        // reader-side mapping cannot pass.
        AnkiScreenshotPresetOptions.keys.forEach { key ->
            val parts = key.split(':')
            val expected = if (parts.size == 2) {
                parts[0].toIntOrNull()?.let { x -> parts[1].toIntOrNull()?.let { y -> x to y } }
            } else {
                null // "full"
            }

            AnkiCropPresetAspect.fromKey(key)?.let { it.x to it.y } shouldBe expected
        }
    }
}
