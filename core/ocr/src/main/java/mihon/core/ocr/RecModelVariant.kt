// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.ocr

/**
 * Which recogniser weights the Paddle engine loads.
 *
 * Both variants share the same detector, dictionary, ONNX signature (3x48xW ->
 * [N,T,18710], opset 11) and CTC decode; only the recogniser differs, so
 * switching is a model-file swap and needs no pipeline change.
 *
 * [FINE_TUNED] is the default: it was fine-tuned on Manga109-s dialogue + COO
 * onomatopoeia and is measurably better on manga lettering (held-out books,
 * recogniser only: block CER 28.05% -> 25.45%, SFX line CER 77.56% -> 17.12%).
 * [STOCK] keeps the original PP-OCRv6 small weights for users who prefer, or
 * need to compare, the upstream behaviour.
 *
 * Both releases also pin the same curved-SFX detector fine-tune (held-out test:
 * SFX H 32.78% -> 65.03%, SFX crop recall 32.07% -> 69.91%, art false positives
 * 5.35 -> 1.35 per page, dialogue block coverage 92.10% -> 94.94%).
 */
enum class RecModelVariant(
    val recModel: String,
    val release: String,
) {
    FINE_TUNED("ppocrv6_small_rec_manga_real.onnx", "ocr-manga-v3"),
    STOCK("ppocrv6_small_rec.onnx", "ocr-models-v4"),
    ;

    /** Release download root; the manifest and model files are resolved under it. */
    val baseUrl: String
        get() = "https://github.com/komi-kun/komi-kun-ocr-models/releases/download/$release"

    companion object {
        val DEFAULT: RecModelVariant = FINE_TUNED

        fun from(value: String?): RecModelVariant =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
// KUN <--
