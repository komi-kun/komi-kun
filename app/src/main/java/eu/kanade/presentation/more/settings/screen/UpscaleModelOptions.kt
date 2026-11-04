// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The reader-upscaling settings screen's model choices — pure data, no Compose, so the mapping the
// screen renders can be tested without a device.
//
// **Why the tiers are ids and not architectures.** The catalog carries six IllustrationJaNai 2x
// models and three of them are `FDAT_M` (V2 standard, V3 denoise, V3 detail), so "the FDAT one" is not
// a thing an arch lookup can answer. The three picks the screen offers are therefore named by their
// catalog id, and `UpscaleModelOptionsTest` pins each pick to the arch it is advertised with — a typo
// or a re-export that swapped two entries fails there instead of silently shipping a slower default.
//
// The rest of the family is still selectable (the picker lists every entry of the current scale after
// the curated three), because every catalog model is published in `upscale-models-v1` and a user who
// downloads one must be able to pick it.
// KUN <--
package eu.kanade.presentation.more.settings.screen

import mihon.core.upscale.model.ModelFamily
import mihon.core.upscale.model.UpscaleModel
import mihon.core.upscale.model.UpscaleModelCatalog

/** The three curated IllustrationJaNai 2x picks, fastest first. */
enum class UpscaleIllustrationTier {
    /** V3 detail SPAN — the default: ~14 s/page measured. */
    FAST,

    /** V3 detail FDAT — better texture, ~103 s/page measured. */
    BALANCED,

    /** V1 ESRGAN — the largest file, no measurement behind it. */
    BEST,
}

/** One row of the colour-model picker: a catalog model and the tier it stands for, if any. */
data class UpscaleIllustrationChoice(
    val model: UpscaleModel,
    val tier: UpscaleIllustrationTier?,
)

/**
 * Maps the catalog onto the picker the settings screen shows.
 *
 * Every lookup is per-scale, because a MangaJaNai bucket and an IllustrationJaNai model are trained
 * for one factor: a 2x colour model cannot serve a 4x request, and the router (`route` in
 * `:core:upscale`) filters by scale. Selecting 4x with a 2x model stored would therefore leave every
 * colour page at its original resolution with nothing on screen saying so — [resolve] and
 * [defaultFor] are what the screen uses to keep the stored id valid for the current scale.
 */
object UpscaleModelOptions {

    /** The scale the three curated tiers are published at. */
    const val TIER_SCALE: Int = 2

    /**
     * The curated picks. FAST is also `UpscalePreferences.DEFAULT_ILLUSTRATION_MODEL_ID` (pinned by
     * the catalog test in `:core:upscale`), so the default really is the one this screen calls Fast.
     */
    private val tierModels: Map<UpscaleIllustrationTier, String> = mapOf(
        UpscaleIllustrationTier.FAST to "illustrationjanai_2x_v3detail_span_s",
        UpscaleIllustrationTier.BALANCED to "illustrationjanai_2x_v3detail_fdat_m",
        UpscaleIllustrationTier.BEST to "illustrationjanai_2x_v1_esrgan",
    )

    /**
     * The model a scale starts on when nothing valid is stored: the Fast 2x pick at 2x, and the V3
     * detail 4x export at 4x (the 4x family's only V3 model).
     */
    private val scaleDefaults: Map<Int, String> = mapOf(
        2 to "illustrationjanai_2x_v3detail_span_s",
        4 to "illustrationjanai_4x_v3detail_fdat_m",
    )

    /**
     * The colour-model picker for [scale]: the curated tiers first (at [TIER_SCALE] only), then every
     * other catalog model of that scale, so nothing published is unreachable.
     */
    fun choices(scale: Int): List<UpscaleIllustrationChoice> {
        val models = UpscaleModelCatalog.forFamilyAndScale(ModelFamily.ILLUSTRATION_JANAI, scale)
        val curated = if (scale == TIER_SCALE) {
            UpscaleIllustrationTier.entries.mapNotNull { tier ->
                models.firstOrNull { it.id == tierModels[tier] }
                    ?.let { UpscaleIllustrationChoice(it, tier) }
            }
        } else {
            emptyList()
        }
        val curatedIds = curated.mapTo(HashSet()) { it.model.id }
        return curated + models
            .filterNot { it.id in curatedIds }
            .map { UpscaleIllustrationChoice(it, null) }
    }

    /** The tier [model] is curated as, or `null` for a catalog entry that is not one of the three. */
    fun tierOf(model: UpscaleModel): UpscaleIllustrationTier? =
        choices(model.scale).firstOrNull { it.model.id == model.id }?.tier

    /** The model a fresh selection of [scale] uses. */
    fun defaultFor(scale: Int): UpscaleModel = requireNotNull(
        UpscaleModelCatalog.find(scaleDefaults.getValue(scale)),
    ) { "no default colour model for scale $scale" }

    /**
     * The IllustrationJaNai model [scale] actually runs for a stored [id]: the stored one when it is a
     * colour model of that scale, the scale's default otherwise (a stale, unknown or other-scale id).
     */
    fun resolve(scale: Int, id: String?): UpscaleModel {
        val stored = id?.let(UpscaleModelCatalog::find)
        val usable = stored != null &&
            stored.family == ModelFamily.ILLUSTRATION_JANAI &&
            stored.scale == scale
        return if (usable) stored else defaultFor(scale)
    }
}
