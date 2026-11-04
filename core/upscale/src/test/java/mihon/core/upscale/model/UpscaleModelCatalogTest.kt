// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.upscale.model

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.UpscalePreferences

class UpscaleModelCatalogTest {

    @Test
    fun `ids and file names are unique`() {
        val models = UpscaleModelCatalog.models

        models.map { it.id }.toSet() shouldHaveSize models.size
        models.map { it.fileName }.toSet() shouldHaveSize models.size
    }

    @Test
    fun `the catalog carries the manga buckets for both scales`() {
        for (scale in UpscaleModelCatalog.scales) {
            val manga = UpscaleModelCatalog.forFamilyAndScale(ModelFamily.MANGA_JANAI, scale)
            manga.map { it.bucketHeight } shouldBe UpscaleModelCatalog.mangaBuckets
            manga.all { it.scale == scale && it.arch == ModelArch.ESRGAN } shouldBe true
        }
        val expectedTotal = MANGA_MODELS_PER_SCALE * UpscaleModelCatalog.scales.size +
            ILLUSTRATION_MODELS
        UpscaleModelCatalog.models shouldHaveSize expectedTotal
    }

    @Test
    fun `the preference defaults name real catalog models`() {
        // The Apache preference cannot import this GPL module, so the two ids are duplicated as
        // constants; this pins them together. A drifted id silently means colour pages never resolve.
        UpscaleModelCatalog.DEFAULT_ILLUSTRATION_MODEL_ID shouldBe
            UpscalePreferences.DEFAULT_ILLUSTRATION_MODEL_ID
        UpscaleModelCatalog.DEFAULT_MANGA_BUCKET shouldBe UpscalePreferences.DEFAULT_MANGA_BUCKET

        val model = UpscaleModelCatalog.find(UpscalePreferences.DEFAULT_ILLUSTRATION_MODEL_ID)
        model shouldNotBe null
        model!!.family shouldBe ModelFamily.ILLUSTRATION_JANAI
        model.scale shouldBe UpscalePreferences.DEFAULT_SCALE
        UpscaleModelCatalog.mangaBuckets.contains(UpscalePreferences.DEFAULT_MANGA_BUCKET) shouldBe
            true
    }

    private companion object {
        const val MANGA_MODELS_PER_SCALE = 7
        const val ILLUSTRATION_MODELS = 8
    }
}
