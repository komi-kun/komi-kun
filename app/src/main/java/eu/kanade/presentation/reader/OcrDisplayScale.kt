// SPDX-License-Identifier: Apache-2.0
// KUN -->
// The OCR overlay's two display scales, as one small policy: the percent range the settings sliders
// offer and the percent → factor conversion the overlay draws with.
//
// They live together (and outside the Compose code) because the pair is easy to get wrong and the
// symptom is ugly rather than loud: a slider that offers more than the drawing accepts is silently
// clamped, and a percentage used as a factor (or a stray 0) collapses the boxes. Pinned by
// `OcrDisplayScaleTest`.
// KUN <--
package eu.kanade.presentation.reader

object OcrDisplayScale {

    /** The percent range both OCR display sliders offer (settings) — and the overlay accepts. */
    val PERCENT_RANGE: IntRange = 50..150

    /** 100% = the calibrated size (tight around the detected block, printed-glyph text size). */
    const val DEFAULT_PERCENT: Int = 100

    /** Below this the outline is invisible; a degenerate 0/negative preference is clamped here. */
    private const val MIN_BOX_SCALE = 0.05f

    /** Below 50% the overlay text stops being legible; above 150% it swallows the page. */
    private const val MIN_TEXT_SCALE = 0.5f
    private const val MAX_TEXT_SCALE = 1.5f

    /**
     * The factor the detected box outline is grown/shrunk by. `100` → `1f` (tight), `150` → `1.5f`,
     * clamped at [MIN_BOX_SCALE] for an unusable preference value.
     */
    fun boxScaleFactor(percent: Int): Float = (percent / 100f).coerceAtLeast(MIN_BOX_SCALE)

    /**
     * The factor the overlay text is scaled by, clamped to the legible range. `100` → `1f`.
     */
    fun textScaleFactor(percent: Int): Float = (percent / 100f).coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
}
