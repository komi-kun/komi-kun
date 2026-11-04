// SPDX-License-Identifier: Apache-2.0
// KUN -->
// The reader-level upscaling gates and cache-candidate rules — pure, GPL-free, and testable without
// an engine, a cache or an Android device.
//
// It lives in the Apache reader package (not in the GPL `:core:upscale`) for the same reason
// `ReaderOcrTogglePolicy` does: the reader seam and `ReaderActivity` are upstream-contributable files,
// so the decisions they take must be expressible without GPL types. Everything here is expressed in
// ids, integers and the Apache `UpscaleMode`; the GPL side maps its catalog onto [UpscaleModelRef].
//
// **What this file does not decide.** Which model a *decoded* page goes to is the router's job
// (`mihon.core.upscale.model.route`), because it needs the page's height and a grayscale verdict.
// This file only answers "which cached entries may serve this page before it is decoded" — a page's
// family is not known yet at that point — and whether the feature is live at all.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.upscale

import tachiyomi.core.common.preference.UpscaleMode

/**
 * One installed model as the reader-level policy sees it: its id, its scale and — for the MangaJaNai
 * family only — the page height ("bucket") it was trained for.
 */
data class UpscaleModelRef(
    val id: String,
    val scale: Int,
    val bucketHeight: Int?,
)

/** The upscaling rules the reader applies before any GPL code runs. */
object UpscaleRuntimePolicy {

    /**
     * Whether upscaling may run at all: the user opted in **and** at least one model is installed.
     *
     * With no model installed there is nothing to upscale with, so "enabled" on its own is not enough
     * — every entry point would otherwise pay for a queue that can never write a page.
     */
    fun isActive(enabled: Boolean, installedIds: Set<String>): Boolean =
        enabled && installedIds.isNotEmpty()

    /**
     * The models whose cached result could serve this page, in check order.
     *
     * A page's height and colour are only known after decoding it, so this cannot be the router's
     * decision; it is the superset the reader may look up:
     *
     *   * [UpscaleMode.MANGA_ONLY] — the manga buckets only (a colour page is served by manga on
     *     purpose: that is what the mode means);
     *   * [UpscaleMode.ILLUSTRATION_ONLY] — the selected illustration model only;
     *   * [UpscaleMode.AUTO] — the selected illustration model, then the manga buckets. A MangaJaNai
     *     entry under an `AUTO` cache signature can only have been written for a page the router
     *     called grayscale (`UpscalePreferences.cacheSignature()` carries the mode, so a mode change
     *     invalidates those entries), which is why checking them here cannot hand a colour page a
     *     line-art upscale.
     *
     * @param pageHeight the page's height in pixels when it is already known, else `null`; with
     *   `null` every installed bucket at [scale] is a candidate, because a page's bucket is fixed by
     *   its own height and only one of them can ever hold an entry for it.
     */
    fun eligibleModels(
        pageHeight: Int?,
        mode: UpscaleMode,
        scale: Int,
        installed: List<UpscaleModelRef>,
        illustrationModelId: String?,
    ): List<UpscaleModelRef> = when (mode) {
        UpscaleMode.MANGA_ONLY -> mangaBuckets(pageHeight, scale, installed)
        UpscaleMode.ILLUSTRATION_ONLY ->
            listOfNotNull(selectedIllustration(scale, installed, illustrationModelId))
        UpscaleMode.AUTO ->
            listOfNotNull(selectedIllustration(scale, installed, illustrationModelId)) +
                mangaBuckets(pageHeight, scale, installed)
    }

    /**
     * The installed MangaJaNai buckets at [scale]: the nearest one to [pageHeight] (the largest at or
     * below it, else the smallest above it), or every installed bucket when the height is unknown.
     *
     * This is the same rule `mihon.core.upscale.model.route` applies; duplicated on purpose so the
     * reader's pre-decode lookup needs no GPL import. The router stays the authority for what is
     * actually upscaled.
     */
    private fun mangaBuckets(
        pageHeight: Int?,
        scale: Int,
        installed: List<UpscaleModelRef>,
    ): List<UpscaleModelRef> {
        val buckets = installed
            .filter { it.bucketHeight != null && it.scale == scale }
            .sortedBy { it.bucketHeight }
        if (buckets.isEmpty()) return emptyList()
        val height = pageHeight ?: return buckets
        val atOrBelow = buckets.lastOrNull { (it.bucketHeight ?: 0) <= height }
        return listOf(atOrBelow ?: buckets.first())
    }

    /**
     * The selected illustration model, when it is installed at [scale]; `null` otherwise.
     */
    private fun selectedIllustration(
        scale: Int,
        installed: List<UpscaleModelRef>,
        illustrationModelId: String?,
    ): UpscaleModelRef? {
        val id = illustrationModelId ?: return null
        return installed.firstOrNull { it.id == id && it.bucketHeight == null && it.scale == scale }
    }

    /**
     * Whether a sheet — one page, or two merged into one double page — may be decoded from the upscale
     * cache.
     *
     * The halves are resolved independently, but a merged sheet is drawn as **one** image at **one**
     * scale: taking a cached 2x half next to a 1x original would show a visible scale step along the
     * seam. So the cached entries are used only when every half that exists has one, and otherwise
     * both halves come from the source and both are queued for upscaling.
     *
     * @param pageCached whether the page's own cached upscale exists.
     * @param hasExtraPage whether this sheet has a second half at all (a merged double page does).
     * @param extraPageCached whether the second half's cached upscale exists; ignored without one.
     */
    fun mergedHalvesFromCache(
        pageCached: Boolean,
        hasExtraPage: Boolean,
        extraPageCached: Boolean,
    ): Boolean = pageCached && (!hasExtraPage || extraPageCached)
}
