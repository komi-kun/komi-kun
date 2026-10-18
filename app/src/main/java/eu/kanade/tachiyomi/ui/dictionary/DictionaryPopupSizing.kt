// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package eu.kanade.tachiyomi.ui.dictionary

import kotlin.math.roundToInt

/**
 * Resolves the dictionary popup's size from the user's size preference.
 *
 * The popup used to be a fixed dp rectangle (300x360), which looks reasonable on a phone but
 * stays tiny on a tablet. Instead the size is now expressed as a **percentage of an
 * adaptive, screen-relative baseline**: the baseline is a fraction of the screen (80% of the
 * width, 45% of the height), so a bigger screen yields a bigger default popup, and the
 * [DictionaryPreferences.popupSizePercent] preference scales that baseline up or down for
 * users who want a larger or smaller popup everywhere.
 *
 * Exact dp overrides ([DictionaryPreferences.popupWidth]/[popupHeight], e.g. from a Yomitan
 * settings import) still win over the adaptive baseline when set; the percentage then scales
 * them too.
 */
internal object DictionaryPopupSizing {
    const val DEFAULT_PERCENT = 100
    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 200

    /** Fraction of the screen width/height the popup takes at 100%. */
    private const val WIDTH_FRACTION = 0.80f
    private const val HEIGHT_FRACTION = 0.45f

    /** Keep the popup off the screen edges. */
    private const val EDGE_MARGIN_DP = 16

    data class Size(val widthDp: Int, val heightDp: Int)

    fun resolve(
        screenWidthDp: Int,
        screenHeightDp: Int,
        percent: Int,
        overrideWidthDp: Int,
        overrideHeightDp: Int,
    ): Size {
        val scale = percent.coerceIn(MIN_PERCENT, MAX_PERCENT) / 100f

        val baseWidth = if (overrideWidthDp > 0) overrideWidthDp.toFloat() else screenWidthDp * WIDTH_FRACTION
        val baseHeight = if (overrideHeightDp > 0) overrideHeightDp.toFloat() else screenHeightDp * HEIGHT_FRACTION

        val maxWidth = (screenWidthDp - EDGE_MARGIN_DP).coerceAtLeast(120)
        val maxHeight = (screenHeightDp - EDGE_MARGIN_DP).coerceAtLeast(120)

        val width = (baseWidth * scale).roundToInt().coerceIn(120, maxWidth)
        val height = (baseHeight * scale).roundToInt().coerceIn(120, maxHeight)
        return Size(width, height)
    }
}
// KUN <--
