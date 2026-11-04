// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.upscale.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.UpscaleMode

class UpscaleRouterTest {

    @Test
    fun `a grayscale page routes to the manga bucket matching its height`() {
        val model = route(
            isGrayscale = true,
            pageHeight = 1600,
            mode = UpscaleMode.AUTO,
            scale = 2,
            installedIds = allManga2x,
            illustrationModelId = null,
        )

        model?.id shouldBe "mangajanai_2x_1600p"
        model?.bucketHeight shouldBe 1600
        model?.family shouldBe ModelFamily.MANGA_JANAI
    }

    @Test
    fun `a grayscale page prefers the nearest bucket below it and only then the one above`() {
        // 1700 is between 1600 and 1920: the model that has seen pages this big wins.
        route(true, 1700, UpscaleMode.AUTO, 2, allManga2x, null)?.bucketHeight shouldBe 1600
        // 2048 is the top bucket.
        route(true, 4000, UpscaleMode.AUTO, 2, allManga2x, null)?.bucketHeight shouldBe 2048
        // 500 is below every bucket: the smallest one above it.
        route(true, 500, UpscaleMode.AUTO, 2, allManga2x, null)?.bucketHeight shouldBe 1200
    }

    @Test
    fun `a colour page routes to the selected illustration model`() {
        val model = route(
            isGrayscale = false,
            pageHeight = 1600,
            mode = UpscaleMode.AUTO,
            scale = 2,
            installedIds = allManga2x + ILLUSTRATION_2X,
            illustrationModelId = ILLUSTRATION_2X,
        )

        model?.id shouldBe ILLUSTRATION_2X
        model?.family shouldBe ModelFamily.ILLUSTRATION_JANAI
        model?.bucketHeight shouldBe null
    }

    @Test
    fun `a colour page with no illustration model installed resolves to nothing, never to manga`() {
        // The regression this policy exists for: every MangaJaNai bucket is installed, the colour
        // model is not — the page must be left alone, not handed to a line-art model.
        val model = route(
            isGrayscale = false,
            pageHeight = 1600,
            mode = UpscaleMode.AUTO,
            scale = 2,
            installedIds = allManga2x,
            illustrationModelId = ILLUSTRATION_2X,
        )

        model shouldBe null
    }

    @Test
    fun `an unknown or mismatched illustration id resolves to nothing`() {
        route(false, 1600, UpscaleMode.AUTO, 2, allManga2x + "nope", "nope") shouldBe null
        // The selected model is installed, but not at the requested scale.
        route(false, 1600, UpscaleMode.AUTO, 4, allManga2x + ILLUSTRATION_2X, ILLUSTRATION_2X) shouldBe null
        // A manga id smuggled in as the "illustration" selection is refused by family.
        route(false, 1600, UpscaleMode.AUTO, 2, allManga2x, "mangajanai_2x_1600p") shouldBe null
    }

    @Test
    fun `MANGA_ONLY uses a manga bucket for a colour page`() {
        val model = route(
            isGrayscale = false,
            pageHeight = 1600,
            mode = UpscaleMode.MANGA_ONLY,
            scale = 2,
            installedIds = allManga2x + ILLUSTRATION_2X,
            illustrationModelId = ILLUSTRATION_2X,
        )

        model?.family shouldBe ModelFamily.MANGA_JANAI
        model?.bucketHeight shouldBe 1600
    }

    @Test
    fun `ILLUSTRATION_ONLY uses the illustration model for a grayscale page`() {
        val model = route(
            isGrayscale = true,
            pageHeight = 1600,
            mode = UpscaleMode.ILLUSTRATION_ONLY,
            scale = 2,
            installedIds = allManga2x + ILLUSTRATION_2X,
            illustrationModelId = ILLUSTRATION_2X,
        )

        model?.id shouldBe ILLUSTRATION_2X
    }

    @Test
    fun `a missing bucket falls back to the nearest installed bucket of the same family`() {
        val installed = setOf("mangajanai_2x_1200p", "mangajanai_2x_1920p")

        route(true, 1600, UpscaleMode.AUTO, 2, installed, null)?.id shouldBe "mangajanai_2x_1200p"
        route(true, 2048, UpscaleMode.AUTO, 2, installed, null)?.id shouldBe "mangajanai_2x_1920p"
        // Nothing of the family is installed at all.
        route(true, 1600, UpscaleMode.AUTO, 2, emptySet(), null) shouldBe null
        // Another scale's models never satisfy a request for this one.
        route(true, 1600, UpscaleMode.AUTO, 4, installed, null) shouldBe null
    }

    @Test
    fun `the 4x catalog routes 4x models`() {
        val grey = route(true, 1600, UpscaleMode.AUTO, 4, allManga4x, null)
        grey?.id shouldBe "mangajanai_4x_1600p"
        grey?.scale shouldBe 4

        val colour = route(
            isGrayscale = false,
            pageHeight = 1600,
            mode = UpscaleMode.AUTO,
            scale = 4,
            installedIds = setOf("illustrationjanai_4x_v3detail_fdat_m"),
            illustrationModelId = "illustrationjanai_4x_v3detail_fdat_m",
        )
        colour?.scale shouldBe 4
        colour?.family shouldBe ModelFamily.ILLUSTRATION_JANAI
    }

    private companion object {
        const val ILLUSTRATION_2X = "illustrationjanai_2x_v3detail_fdat_m"

        val allManga2x: Set<String> =
            UpscaleModelCatalog.forFamilyAndScale(ModelFamily.MANGA_JANAI, 2).map { it.id }.toSet()

        val allManga4x: Set<String> =
            UpscaleModelCatalog.forFamilyAndScale(ModelFamily.MANGA_JANAI, 4).map { it.id }.toSet()
    }
}
