// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.ocr

/**
 * Which recogniser weights the Paddle engine loads — one axis of the model selection.
 *
 * Both detectors share the same recogniser axis, dictionary, ONNX signature (3x48xW ->
 * [N,T,18710], opset 11) and CTC decode; only the recogniser differs, so switching is a model-file
 * swap and needs no pipeline change.
 *
 * [FINE_TUNED] is the default: it was fine-tuned on Manga109-s dialogue + COO onomatopoeia and is
 * measurably better on manga lettering (held-out books, recogniser only: block CER 28.05% -> 25.45%,
 * SFX line CER 77.56% -> 17.12%). [STOCK] keeps the original PP-OCRv6 small weights for users who
 * prefer, or need to compare, the upstream behaviour.
 *
 * The recogniser does **not** identify a release on its own: the same [recModel] is published by
 * both the dialogue-pinned and the SFX-pinned release. See [ModelRelease] for the
 * detector × recogniser → release map.
 */
enum class RecModelVariant(
    val recModel: String,
) {
    FINE_TUNED("ppocrv6_small_rec_manga_real.onnx"),
    STOCK("ppocrv6_small_rec.onnx"),
    ;

    companion object {
        val DEFAULT: RecModelVariant = FINE_TUNED

        fun from(value: String?): RecModelVariant =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
// KUN <--
