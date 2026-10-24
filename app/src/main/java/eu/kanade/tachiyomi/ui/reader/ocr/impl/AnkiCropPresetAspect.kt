// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The reader-side bridge from the stored Anki crop-preset key to the crop aspect the mining
// screenshot uses.
//
// The settings picker writes a `CropPresets` key (`"4:3"`, `"9:16"`, …, or `"full"`) into
// `pref_anki_crop_preset`; mining needs an `AnkiCropAspect(x, y)`. Resolving it here — through the
// real `CropPresets.aspectByKey` — is what `AnkiScreenshotSettingsTest` (picker vocabulary) cannot
// cover: that only proves the keys are shared, not that the reader's mapping actually produces the
// aspect the user chose. `"full"` (and any key the presets no longer contain) maps to `null`, i.e.
// "no forced aspect, crop the block".
//
// It lives in the GPL `.impl` package because it imports both GPL modules (`:core:ocr`'s
// `CropPresets` and `:core:dictionary`'s `AnkiCropAspect`); neither GPL module may depend on the
// other, and `:app` already depends on both.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.ocr.impl

import mihon.core.dictionary.anki.AnkiCropAspect
import mihon.core.ocr.CropPresets

object AnkiCropPresetAspect {

    /**
     * The aspect for the stored crop-preset [key], or `null` for `"full"`/an unknown key — both mean
     * "do not expand the crop to a ratio".
     */
    fun fromKey(key: String): AnkiCropAspect? =
        CropPresets.aspectByKey(key)?.let { AnkiCropAspect(it.x, it.y) }
}
