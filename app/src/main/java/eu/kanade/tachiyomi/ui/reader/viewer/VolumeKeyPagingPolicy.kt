// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.ui.reader.viewer

/**
 * Pure decision core for the readers' "volume keys turn pages" feature.
 *
 * Every viewer asks this before it turns a `KEYCODE_VOLUME_UP`/`KEYCODE_VOLUME_DOWN` event into a
 * page turn or scroll. The volume keys may only be consumed for navigation when all of the
 * following hold:
 *
 * - the user asked for it ([volumeKeysEnabled]); and
 * - the reader menu/overlay is not covering the page ([menuVisible]); and
 * - the OCR dictionary overlay is **off** ([ocrOverlayEnabled]).
 *
 * The overlay exemption is deliberate. The overlay is opened to look words up and listen to the
 * dictionary's word audio, so a volume press there must reach the system and change the media
 * volume instead of paging the manga. The rule is intentionally coarse - "overlay on, volume keys
 * never page" - rather than conditional on whether audio has played, so it stays predictable.
 *
 * Keeping the answer here instead of inlining it in each viewer's `handleKeyEvent` lets the rule be
 * unit-tested on the JVM with no Android device.
 */
object VolumeKeyPagingPolicy {
    fun shouldConsumeForPaging(
        volumeKeysEnabled: Boolean,
        menuVisible: Boolean,
        ocrOverlayEnabled: Boolean,
    ): Boolean = volumeKeysEnabled && !menuVisible && !ocrOverlayEnabled
}
