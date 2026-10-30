// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.ocr

/**
 * Which detector weights the Paddle engine loads — one axis of the model selection.
 *
 * [DIALOGUE] is the stock PP-OCRv6 small detector: it finds speech bubbles and their individual
 * lines reliably. [SFX] is the curved-SFX fine-tune: it recovers onomatopoeia the stock detector
 * misses (held-out SFX crop recall 32.07% → 69.91%, art false positives 5.35 → 1.35 per page) but
 * it measurably *hurts* dialogue — in device testing it lost lines 1 and 3 of a four-line bubble and
 * merged two adjacent undivided bubbles. Dialogue therefore stays the default and the SFX detector
 * is opt-in.
 *
 * **Both variants load the same file name** ([detModel]); only the bytes differ, per release. The
 * engine cannot tell them apart by path and the downloader cannot treat "the file exists" as "the
 * selected detector is installed" — see `ModelRelease` and `ModelDownloader`'s installed-release
 * marker.
 */
enum class DetModelVariant(
    val detModel: String,
) {
    DIALOGUE("ppocrv6_small_det.onnx"),
    // The fine-tune keeps the stock file name so the JNI `detParamAbsPath` needs no switch.
    SFX("ppocrv6_small_det.onnx"),
    ;

    companion object {
        /** Dialogue-first: an unset (or unknown) detector never resolves to the SFX fine-tune. */
        val DEFAULT: DetModelVariant = DIALOGUE

        fun from(value: String?): DetModelVariant =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
// KUN <--
