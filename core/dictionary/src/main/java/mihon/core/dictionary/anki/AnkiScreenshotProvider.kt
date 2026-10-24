// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.anki

/**
 * Supplies the `{screenshot}` bytes for the card being mined, or null when the card takes no image
 * (screenshot mode `NONE`, or a page that could not be captured).
 *
 * The reader owns the page pixels and provides this; the dictionary UI only forwards it to
 * [AnkiMiningController.mine]. Capturing is `suspend` because decoding a page must never run on the
 * UI thread (plan 05 §B4).
 */
fun interface AnkiScreenshotProvider {
    suspend fun capture(): ByteArray?
}
// KUN <--
