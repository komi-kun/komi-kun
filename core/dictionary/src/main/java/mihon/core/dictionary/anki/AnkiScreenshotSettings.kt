// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.anki

/**
 * Where the *live* screenshot settings are read from.
 *
 * `AnkiPreferences.activeProfile()` returns the persisted profile, which lags a settings edit by one
 * callback: the settings screen's `ListPreference` runs `onValueChanged` — that is,
 * `persistActiveFromFlat()` — **before** it writes the chosen value (`PreferenceItem.kt`:
 * `if (item.internalOnValueChanged(newValue)) item.internalSet(newValue)`). So choosing "No
 * screenshot" leaves `profile.ankiCropMode == "full"` and the next card would still be captured.
 *
 * The flat keys are the source the UI writes and the source `persistActiveFromFlat()` copies into the
 * profile; they are also re-seeded from the profile on a profile switch (`writeFlatProfile`). Reading
 * them here keeps mining in step with what the user just chose, without redesigning the settings.
 */
object AnkiScreenshotSettings {

    /** The screenshot mode mining must use right now. */
    fun mode(preferences: AnkiPreferences): AnkiScreenshotMode =
        AnkiScreenshotMode.fromStorageValue(preferences.cropMode().get())

    /**
     * The crop preset key (`"4:3"`, `"1:1"`, …; `"full"` when unset) mining must use right now.
     * Callers map it to an aspect; an unknown key means "no forced aspect".
     */
    fun presetKey(preferences: AnkiPreferences): String = preferences.cropPreset().get()
}
// KUN <--
