// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon's `chimahon/anki/AnkiScreenshotMode.kt` (GPL-3.0).
package mihon.core.dictionary.anki

/** What media, if any, is captured from the reader page into a card's `{screenshot}` field. */
enum class AnkiScreenshotMode(val storageValue: String) {
    FULL("full"),
    CROP("crop"),
    NONE("no_screenshot"),

    /** `// KUN` extension: jump to the page boundary of the tapped scene. */
    ANIMATED_SCENE("animated_scene"),
    ;

    companion object {
        fun fromStorageValue(value: String): AnkiScreenshotMode =
            entries.firstOrNull { it.storageValue == value } ?: FULL
    }
}
// KUN <--
