// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.ocr

/**
 * The release that publishes one (detector, recogniser) pair — the model selection is two
 * independent axes, and each of the four combinations is its *own* release tag:
 *
 * | detector \ recogniser | [RecModelVariant.FINE_TUNED] | [RecModelVariant.STOCK] |
 * |-----------------------|------------------------------|------------------------|
 * | [DetModelVariant.DIALOGUE] | `ocr-manga-v4` | `ocr-models-v3` |
 * | [DetModelVariant.SFX]      | `ocr-manga-v3` | `ocr-models-v4` |
 *
 * Every release ships identically named files ([DetModelVariant.detModel] is the *same* name in all
 * four), so the tag — not the file set — is what identifies the selected bytes. That is why the
 * downloader records the installed tag and refuses to treat a leftover file as the right variant.
 *
 * [DEFAULT] is dialogue-first: a user who never touched the detector gets the stock detector and
 * therefore `ocr-manga-v4` (the release that exists for exactly that pair), never the SFX fine-tune.
 */
enum class ModelRelease(
    val det: DetModelVariant,
    val rec: RecModelVariant,
    val release: String,
) {
    DIALOGUE_FINE_TUNED(DetModelVariant.DIALOGUE, RecModelVariant.FINE_TUNED, "ocr-manga-v4"),
    DIALOGUE_STOCK(DetModelVariant.DIALOGUE, RecModelVariant.STOCK, "ocr-models-v3"),
    SFX_FINE_TUNED(DetModelVariant.SFX, RecModelVariant.FINE_TUNED, "ocr-manga-v3"),
    SFX_STOCK(DetModelVariant.SFX, RecModelVariant.STOCK, "ocr-models-v4"),
    ;

    /** Release download root; the manifest and model files are resolved under it. */
    val baseUrl: String
        get() = "https://github.com/komi-kun/komi-kun-ocr-models/releases/download/$release"

    companion object {
        /** The selection with nothing stored: [DetModelVariant.DEFAULT] × [RecModelVariant.DEFAULT]. */
        val DEFAULT: ModelRelease
            get() = of(DetModelVariant.DEFAULT, RecModelVariant.DEFAULT)

        /**
         * The release for [det] × [rec]. Every axis combination is mapped by construction (the
         * `ModelSelectionTest` cartesian-product assertion guards it), so the fallback only protects
         * the app from a crashing read; it is the dialogue-first release.
         */
        fun of(det: DetModelVariant, rec: RecModelVariant): ModelRelease =
            entries.firstOrNull { it.det == det && it.rec == rec } ?: DIALOGUE_FINE_TUNED
    }
}
// KUN <--
