// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The model catalog: every model the app can install, by family and scale.
//
// File names are the zip member names the MangaJaNai / IllustrationJaNai 3.x ONNX packs actually ship
// (both families publish their checkpoints as fp16 ONNX exports named
// `<scale>x_<family>_<variant>_<arch>_<steps>k[_fp16].onnx`), so `fileName` is exactly the release
// asset name `tools/upscale-models/` republishes — no renaming in between. The MangaJaNai family is
// the published V1 ESRGAN set: one model per target page height (bucket), for 2x and 4x — 7 buckets,
// 14 files. Those names carry no `_fp16` suffix even though they come from the fp16 pack.
//
// Deliberately NO hashes: the model release manifest (`upscale-models-v1`'s `manifest.json`, built by
// `tools/upscale-models/fetch_and_publish.py`) carries the SHA-256 of each file, so re-exporting a
// checkpoint never needs a code change.
// KUN <--
package mihon.core.upscale.model

/** Every model the app knows about, and the lookups the router and settings need. */
object UpscaleModelCatalog {

    /** The default MangaJaNai bucket (px). Also the value the preference defaults to. */
    const val DEFAULT_MANGA_BUCKET: Int = 1600

    /**
     * The default IllustrationJaNai model — 2x V3 detail FDAT_M (fp16), the balanced colour-page
     * default. `UpscalePreferences` defaults to the same id; `UpscaleModelCatalogTest` pins them
     * together so the two defaults cannot drift.
     */
    const val DEFAULT_ILLUSTRATION_MODEL_ID: String = "illustrationjanai_2x_v3detail_span_s"

    /** Every supported scale factor, ascending. */
    val scales: List<Int> = listOf(2, 4)

    /** Every MangaJaNai bucket height, ascending. */
    val mangaBuckets: List<Int> = listOf(1200, 1300, 1400, 1500, 1600, 1920, 2048)

    /**
     * Training step counts as the published file names state them, per scale then bucket. Kept as
     * data (not appended to a template) because the 2x and 4x runs stopped at different steps.
     */
    private val mangaStepCounts: Map<Int, Map<Int, String>> = mapOf(
        2 to mapOf(
            1200 to "70k",
            1300 to "75k",
            1400 to "70k",
            1500 to "90k",
            1600 to "90k",
            1920 to "70k",
            2048 to "95k",
        ),
        4 to mapOf(
            1200 to "70k",
            1300 to "75k",
            1400 to "105k",
            1500 to "105k",
            1600 to "70k",
            1920 to "105k",
            2048 to "70k",
        ),
    )

    private val mangaModels: List<UpscaleModel> = mangaStepCounts.flatMap { (scale, steps) ->
        steps.map { (bucket, stepCount) ->
            UpscaleModel(
                id = "mangajanai_${scale}x_${bucket}p",
                fileName = "${scale}x_MangaJaNai_${bucket}p_V1_ESRGAN_$stepCount.onnx",
                family = ModelFamily.MANGA_JANAI,
                scale = scale,
                bucketHeight = bucket,
                arch = ModelArch.ESRGAN,
                displayName = "MangaJaNai ${scale}x ${bucket}p",
                approxBytes = esrganApproxBytes(scale),
            )
        }
    }

    private val illustrationModels: List<UpscaleModel> = listOf(
        illustration(
            id = "illustrationjanai_2x_v1_esrgan",
            fileName = "2x_IllustrationJaNai_V1_ESRGAN_120k.onnx",
            scale = 2,
            arch = ModelArch.ESRGAN,
            displayName = "IllustrationJaNai 2x V1",
        ),
        illustration(
            id = "illustrationjanai_2x_v2standard_fdat_m_unshuffle",
            fileName = "2x_IllustrationJaNai_V2standard_FDAT_M_unshuffle_40k.onnx",
            scale = 2,
            arch = ModelArch.FDAT_M,
            displayName = "IllustrationJaNai 2x V2 standard",
        ),
        illustration(
            id = "illustrationjanai_2x_v3denoise_span_s",
            fileName = "2x_IllustrationJaNai_V3denoise_SPAN_S_30k_fp16.onnx",
            scale = 2,
            arch = ModelArch.SPAN_S,
            displayName = "IllustrationJaNai 2x V3 denoise (SPAN)",
        ),
        illustration(
            id = "illustrationjanai_2x_v3denoise_fdat_m",
            fileName = "2x_IllustrationJaNai_V3denoise_FDAT_M_unshuffle_30k_fp16.onnx",
            scale = 2,
            arch = ModelArch.FDAT_M,
            displayName = "IllustrationJaNai 2x V3 denoise (FDAT)",
        ),
        illustration(
            id = "illustrationjanai_2x_v3detail_span_s",
            fileName = "2x_IllustrationJaNai_V3detail_SPAN_S_40k_fp16.onnx",
            scale = 2,
            arch = ModelArch.SPAN_S,
            displayName = "IllustrationJaNai 2x V3 detail (SPAN)",
        ),
        illustration(
            id = "illustrationjanai_2x_v3detail_fdat_m",
            fileName = "2x_IllustrationJaNai_V3detail_FDAT_M_unshuffle_40k_fp16.onnx",
            scale = 2,
            arch = ModelArch.FDAT_M,
            displayName = "IllustrationJaNai 2x V3 detail (FDAT)",
        ),
        illustration(
            id = "illustrationjanai_4x_v2standard_fdat_m",
            fileName = "4x_IllustrationJaNai_V2standard_FDAT_M_52k.onnx",
            scale = 4,
            arch = ModelArch.FDAT_M,
            displayName = "IllustrationJaNai 4x V2 standard",
        ),
        illustration(
            id = "illustrationjanai_4x_v3detail_fdat_m",
            fileName = "4x_IllustrationJaNai_V3detail_FDAT_M_40k_fp16.onnx",
            scale = 4,
            arch = ModelArch.FDAT_M,
            displayName = "IllustrationJaNai 4x V3 detail",
        ),
    )

    /** Every model, MangaJaNai first (buckets ascending), then IllustrationJaNai. */
    val models: List<UpscaleModel> = mangaModels + illustrationModels

    private val modelsById: Map<String, UpscaleModel> = models.associateBy { it.id }

    /** The model with [id], or `null` when the id is unknown (a stale preference, say). */
    fun find(id: String): UpscaleModel? = modelsById[id]

    /** Every model of [family]. */
    fun forFamily(family: ModelFamily): List<UpscaleModel> = models.filter { it.family == family }

    /** Every model of [family] at [scale], buckets ascending. */
    fun forFamilyAndScale(family: ModelFamily, scale: Int): List<UpscaleModel> =
        models.filter { it.family == family && it.scale == scale }
            .sortedBy { it.bucketHeight ?: 0 }

    /**
     * The published size of an IllustrationJaNai file, keyed by arch and scale. These are the exact
     * byte counts `upscale-models-v1`'s `manifest.json` carries (and `UpscaleModelManifestTest` pins
     * them), because the settings screen shows them as the model's download size.
     */
    private fun illustration(
        id: String,
        fileName: String,
        scale: Int,
        arch: ModelArch,
        displayName: String,
    ): UpscaleModel = UpscaleModel(
        id = id,
        fileName = fileName,
        family = ModelFamily.ILLUSTRATION_JANAI,
        scale = scale,
        bucketHeight = null,
        arch = arch,
        displayName = displayName,
        approxBytes = when (arch) {
            ModelArch.ESRGAN -> 33_874_232L
            ModelArch.FDAT_M -> if (scale >= 4) 9_130_610L else 9_158_595L
            ModelArch.SPAN_S -> 839_921L
        },
    )

    /** The published size of a MangaJaNai file: the 2x and 4x exports are each one flat size. */
    private fun esrganApproxBytes(scale: Int): Long = if (scale >= 4) 33_859_540L else 33_874_232L
}
