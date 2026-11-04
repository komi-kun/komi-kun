// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The pre-decode gate and cache-candidate rule for reader upscaling. These two decisions are taken on
// the page-decode path, before any GPL code runs, so they are pinned here: "enabled" alone is not
// enough to start work, and the mode decides which families may be looked up.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.upscale

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.UpscaleMode

class UpscaleRuntimePolicyTest {

    /**
     * Catches a gate that runs upscaling with no model installed: every request would be queued,
     * decoded and then dropped for a missing file, on the page-decode path.
     */
    @Test
    fun `upscaling is active only when it is enabled and a model is installed`() {
        UpscaleRuntimePolicy.isActive(enabled = true, installedIds = setOf(MANGA_1600)) shouldBe true
        UpscaleRuntimePolicy.isActive(enabled = true, installedIds = emptySet()) shouldBe false
        UpscaleRuntimePolicy.isActive(enabled = false, installedIds = setOf(MANGA_1600)) shouldBe false
        UpscaleRuntimePolicy.isActive(enabled = false, installedIds = emptySet()) shouldBe false
    }

    /**
     * Catches a colour-page route in AUTO that would look up (and so could serve) a MangaJaNai entry:
     * the manga buckets are checked only when the mode asks for manga, or as the grayscale half of
     * AUTO — and the illustration model comes first there.
     */
    @Test
    fun `AUTO checks the illustration model first and then the manga buckets`() {
        val candidates = UpscaleRuntimePolicy.eligibleModels(
            pageHeight = null,
            mode = UpscaleMode.AUTO,
            scale = 2,
            installed = ALL,
            illustrationModelId = ILLUSTRATION,
        )

        candidates.map { it.id } shouldBe listOf(ILLUSTRATION, MANGA_1200, MANGA_1600)
    }

    /** Catches a MANGA_ONLY route that still looks up the illustration cache entry. */
    @Test
    fun `MANGA_ONLY checks the manga buckets only`() {
        val candidates = UpscaleRuntimePolicy.eligibleModels(
            pageHeight = null,
            mode = UpscaleMode.MANGA_ONLY,
            scale = 2,
            installed = ALL,
            illustrationModelId = ILLUSTRATION,
        )

        candidates.map { it.id } shouldBe listOf(MANGA_1200, MANGA_1600)
    }

    /** Catches an ILLUSTRATION_ONLY route that still looks up (or serves) a MangaJaNai entry. */
    @Test
    fun `ILLUSTRATION_ONLY checks the selected illustration model only`() {
        val candidates = UpscaleRuntimePolicy.eligibleModels(
            pageHeight = null,
            mode = UpscaleMode.ILLUSTRATION_ONLY,
            scale = 2,
            installed = ALL,
            illustrationModelId = ILLUSTRATION,
        )

        candidates.map { it.id } shouldBe listOf(ILLUSTRATION)
    }

    /** Catches a fallback that names a model it does not have: an unselected/absent one is `null`. */
    @Test
    fun `a model that is not installed is never a candidate`() {
        val onlyManga = listOf(ref(MANGA_1600, 1600))

        UpscaleRuntimePolicy.eligibleModels(
            pageHeight = null,
            mode = UpscaleMode.ILLUSTRATION_ONLY,
            scale = 2,
            installed = onlyManga,
            illustrationModelId = ILLUSTRATION,
        ).shouldBeEmpty()

        UpscaleRuntimePolicy.eligibleModels(
            pageHeight = null,
            mode = UpscaleMode.AUTO,
            scale = 2,
            installed = listOf(ref(ILLUSTRATION, null)),
            illustrationModelId = ILLUSTRATION,
        ).map { it.id } shouldBe listOf(ILLUSTRATION)
    }

    /** Catches a scale that is mixed up: a 4x model must never serve a 2x request. */
    @Test
    fun `only models at the requested scale are candidates`() {
        UpscaleRuntimePolicy.eligibleModels(
            pageHeight = null,
            mode = UpscaleMode.MANGA_ONLY,
            scale = 4,
            installed = ALL,
            illustrationModelId = ILLUSTRATION,
        ).shouldBeEmpty()

        UpscaleRuntimePolicy.eligibleModels(
            pageHeight = null,
            mode = UpscaleMode.MANGA_ONLY,
            scale = 2,
            installed = listOf(ref(MANGA_1600, 1600), UpscaleModelRef(MANGA_1600_X4, 4, 1600)),
            illustrationModelId = ILLUSTRATION,
        ).map { it.id } shouldBe listOf(MANGA_1600)
    }

    /**
     * Catches the nearest-bucket rule being inverted: a page gets the largest bucket at or below its
     * height, and only a page taller than every installed bucket gets the smallest one above it.
     */
    @Test
    fun `a known page height picks the nearest manga bucket`() {
        fun bucketFor(height: Int) = UpscaleRuntimePolicy.eligibleModels(
            pageHeight = height,
            mode = UpscaleMode.MANGA_ONLY,
            scale = 2,
            installed = ALL,
            illustrationModelId = ILLUSTRATION,
        ).map { it.id }

        bucketFor(1100) shouldBe listOf(MANGA_1200) // nothing at or below it: smallest above
        bucketFor(1200) shouldBe listOf(MANGA_1200)
        bucketFor(1500) shouldBe listOf(MANGA_1200) // 1600 is above, 1200 is at or below
        bucketFor(1600) shouldBe listOf(MANGA_1600)
        bucketFor(4000) shouldBe listOf(MANGA_1600)
    }

    /**
     * Catches a merged double page that draws one cached 2x half next to one 1x original: the sheet is
     * one image at one scale, so the cache may only be used when *both* halves have an entry — and a
     * single page (no second half) must still be served from its own entry, or the whole feature
     * silently stops for the paged reader.
     */
    @Test
    fun `a merged sheet uses the cache only when both halves have an upscale`() {
        // Both halves cached: the sheet decodes from the cache.
        UpscaleRuntimePolicy.mergedHalvesFromCache(
            pageCached = true,
            hasExtraPage = true,
            extraPageCached = true,
        ) shouldBe true

        // One half cached, the other not (either way round): both halves come from the source.
        UpscaleRuntimePolicy.mergedHalvesFromCache(
            pageCached = true,
            hasExtraPage = true,
            extraPageCached = false,
        ) shouldBe false
        UpscaleRuntimePolicy.mergedHalvesFromCache(
            pageCached = false,
            hasExtraPage = true,
            extraPageCached = true,
        ) shouldBe false

        // Nothing cached.
        UpscaleRuntimePolicy.mergedHalvesFromCache(
            pageCached = false,
            hasExtraPage = true,
            extraPageCached = false,
        ) shouldBe false

        // A single page has only one half, so its own entry is enough.
        UpscaleRuntimePolicy.mergedHalvesFromCache(
            pageCached = true,
            hasExtraPage = false,
            extraPageCached = false,
        ) shouldBe true
        UpscaleRuntimePolicy.mergedHalvesFromCache(
            pageCached = false,
            hasExtraPage = false,
            extraPageCached = false,
        ) shouldBe false
    }

    private companion object {
        fun ref(id: String, bucketHeight: Int?): UpscaleModelRef =
            UpscaleModelRef(id = id, scale = 2, bucketHeight = bucketHeight)
        const val MANGA_1200 = "mangajanai_2x_1200p"
        const val MANGA_1600 = "mangajanai_2x_1600p"
        const val MANGA_1600_X4 = "mangajanai_4x_1600p"
        const val ILLUSTRATION = "illustrationjanai_2x_v3detail_span_s"

        val ALL = listOf(ref(MANGA_1600, 1600), ref(MANGA_1200, 1200), ref(ILLUSTRATION, null))
    }
}
