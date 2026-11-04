// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Which model (if any) upscales this page.
//
// The one rule that must never bend: a colour page never resolves to a MangaJaNai model. MangaJaNai
// is trained on line art; running it on a colour page destroys the colour work and looks worse than
// the original. So a colour page either gets the IllustrationJaNai model the user selected (when it
// is installed) or nothing at all — `null` means "leave the page alone", never "fall back to manga".
//
// The manga side is a bucket match: MangaJaNai ships one model per target page height, so a page is
// served by the nearest installed bucket, preferring the largest bucket at or below the page height
// (that model has seen pages this big) and only then the smallest one above it.
// KUN <--
package mihon.core.upscale.model

import tachiyomi.core.common.preference.UpscaleMode

/**
 * Picks the model that should upscale one page, or `null` to leave it at its original resolution.
 *
 * @param isGrayscale the page's [isGrayscale] verdict.
 * @param pageHeight the page's height in pixels (the MangaJaNai bucket unit).
 * @param mode the user's routing preference.
 * @param scale the requested factor (2 or 4).
 * @param installedIds the ids of the models actually installed.
 * @param illustrationModelId the IllustrationJaNai model the user selected; `null` when none is.
 */
fun route(
    isGrayscale: Boolean,
    pageHeight: Int,
    mode: UpscaleMode,
    scale: Int,
    installedIds: Set<String>,
    illustrationModelId: String?,
): UpscaleModel? = when (mode) {
    UpscaleMode.AUTO -> if (isGrayscale) {
        mangaBucketFor(pageHeight, scale, installedIds)
    } else {
        selectedIllustration(scale, illustrationModelId, installedIds)
    }

    UpscaleMode.MANGA_ONLY -> mangaBucketFor(pageHeight, scale, installedIds)

    UpscaleMode.ILLUSTRATION_ONLY ->
        selectedIllustration(scale, illustrationModelId, installedIds)
}

/**
 * The nearest installed MangaJaNai bucket at [scale]: the largest bucket at or below [pageHeight],
 * else the smallest above it, else `null`. (Those two passes partition the installed set, so there is
 * no third fallback to write.)
 */
private fun mangaBucketFor(
    pageHeight: Int,
    scale: Int,
    installedIds: Set<String>,
): UpscaleModel? {
    val candidates = UpscaleModelCatalog.forFamilyAndScale(ModelFamily.MANGA_JANAI, scale)
        .filter { it.id in installedIds }
    if (candidates.isEmpty()) return null
    val atOrBelow = candidates
        .filter { (it.bucketHeight ?: 0) <= pageHeight }
        .maxByOrNull { it.bucketHeight ?: 0 }
    if (atOrBelow != null) return atOrBelow
    return candidates.minByOrNull { it.bucketHeight ?: 0 }
}

/**
 * The selected IllustrationJaNai model, **only** when it exists in the catalog, belongs to the
 * illustration family, is at [scale] and is installed. Every other case is `null`: a colour page is
 * never handed to another family's model.
 */
private fun selectedIllustration(
    scale: Int,
    illustrationModelId: String?,
    installedIds: Set<String>,
): UpscaleModel? {
    val id = illustrationModelId ?: return null
    val model = UpscaleModelCatalog.find(id) ?: return null
    if (model.family != ModelFamily.ILLUSTRATION_JANAI) return null
    if (model.scale != scale) return null
    if (model.id !in installedIds) return null
    return model
}
