// SPDX-License-Identifier: Apache-2.0
// KUN -->
// The vocabulary of the Anki screenshot crop-preset preference (`pref_anki_crop_preset`).
//
// The settings picker writes one of these storage values and `AnkiScreenshotSettings.presetKey`
// hands it to the reader, which maps it to an aspect through `CropPresets.aspectByKey` (see
// `ReaderOcrHostImpl.screenshotProviderFor`). Keeping the vocabulary here — rather than inline in the
// settings screen — is what lets a test pin it against `CropPresets`: a key the reader does not know
// would silently degrade to "no forced aspect" instead of the chosen crop.
// KUN <--
package eu.kanade.presentation.more.settings.screen

import mihon.core.ocr.CropPresets

object AnkiScreenshotPresetOptions {

    /** No forced aspect: the panel crop follows the block (`AnkiScreenshotCrop.DEFAULT_MARGIN`). */
    const val FULL = "full"

    /** Storage value the preference defaults to (`AnkiPreferences.cropPreset`). */
    const val DEFAULT = FULL

    /** Every storage value the picker offers, in display order: [FULL] first, then the presets. */
    val keys: List<String> = listOf(FULL) + CropPresets.ASPECT_RATIO_PRESETS.map { it.key }

    /** The label for [key]: the preset's own label, or `null` for [FULL] (the UI supplies its own). */
    fun presetLabel(key: String): String? = CropPresets.aspectByKey(key)?.label
}
