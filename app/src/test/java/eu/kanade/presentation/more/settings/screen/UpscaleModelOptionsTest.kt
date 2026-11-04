// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The settings screen's colour-model picker: which catalog entries it offers, in which order, and
// which model a scale really runs.
//
// The cases here are the ones that fail silently on a device: a tier pointing at the wrong (slower)
// checkpoint ships a 103 s/page default for a 14 s/page one, and a stored model of the wrong scale
// routes nothing at all — every colour page then stays at its original resolution with no error
// anywhere.
// KUN <--
package eu.kanade.presentation.more.settings.screen

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import mihon.core.upscale.model.ModelArch
import mihon.core.upscale.model.ModelFamily
import mihon.core.upscale.model.UpscaleModelCatalog
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.UpscalePreferences

class UpscaleModelOptionsTest {

    @Test
    fun `the 2x picker offers the three curated tiers first, fastest first`() {
        val choices = UpscaleModelOptions.choices(2)

        choices.take(3).map { it.model.id } shouldContainExactly listOf(
            "illustrationjanai_2x_v3detail_span_s",
            "illustrationjanai_2x_v3detail_fdat_m",
            "illustrationjanai_2x_v1_esrgan",
        )
        choices.take(3).map { it.tier } shouldContainExactly listOf(
            UpscaleIllustrationTier.FAST,
            UpscaleIllustrationTier.BALANCED,
            UpscaleIllustrationTier.BEST,
        )
        // The advertised arch is what the label says; a swapped checkpoint fails here.
        choices.take(3).map { it.model.arch } shouldContainExactly listOf(
            ModelArch.SPAN_S,
            ModelArch.FDAT_M,
            ModelArch.ESRGAN,
        )
    }

    @Test
    fun `the fastest pick is the stored default, so a fresh install does not run the slow one`() {
        // `UpscalePreferences` (Apache, :core:common) defaults to this id and cannot import this class;
        // if the two drift, the default silently becomes whichever tier happens to be first.
        UpscaleModelOptions.defaultFor(2).id shouldBe UpscalePreferences.DEFAULT_ILLUSTRATION_MODEL_ID
        UpscaleModelOptions.choices(2).first().tier shouldBe UpscaleIllustrationTier.FAST
        UpscaleModelOptions.choices(2).first().model.id shouldBe
            UpscalePreferences.DEFAULT_ILLUSTRATION_MODEL_ID
    }

    @Test
    fun `every published 2x colour model is offered exactly once`() {
        val offered = UpscaleModelOptions.choices(2).map { it.model.id }

        // The curated three lead the list on purpose; membership is what must match the catalog in
        // both directions (a model the app cannot pick is a wasted download, an entry not published
        // is a 404).
        offered shouldContainExactlyInAnyOrder
            UpscaleModelCatalog.forFamilyAndScale(ModelFamily.ILLUSTRATION_JANAI, 2).map { it.id }
        offered.toSet().size shouldBe offered.size
        // Only the curated three carry a tier; the rest of the family is still selectable.
        UpscaleModelOptions.choices(2).count { it.tier != null } shouldBe 3
    }

    @Test
    fun `the 4x picker lists the 4x family with no tiers`() {
        val choices = UpscaleModelOptions.choices(4)

        choices.map { it.model.id } shouldContainExactly
            UpscaleModelCatalog.forFamilyAndScale(ModelFamily.ILLUSTRATION_JANAI, 4).map { it.id }
        choices.all { it.model.scale == 4 && it.tier == null } shouldBe true
        choices.map { it.model.id } shouldContainExactly listOf(
            "illustrationjanai_4x_v2standard_fdat_m",
            "illustrationjanai_4x_v3detail_fdat_m",
        )
    }

    @Test
    fun `selecting 4x with a 2x model stored runs the 4x default instead`() {
        // The defect this catches: the picker keeps showing a 2x model at 4x, the router filters it
        // out, and colour pages are silently left alone.
        val resolved = UpscaleModelOptions.resolve(4, UpscalePreferences.DEFAULT_ILLUSTRATION_MODEL_ID)

        resolved.scale shouldBe 4
        resolved.family shouldBe ModelFamily.ILLUSTRATION_JANAI
        resolved.id shouldBe UpscaleModelOptions.defaultFor(4).id

        UpscaleModelOptions.resolve(2, "illustrationjanai_4x_v3detail_fdat_m").id shouldBe
            UpscalePreferences.DEFAULT_ILLUSTRATION_MODEL_ID
    }

    @Test
    fun `an unknown or missing id resolves to that scale's default`() {
        UpscaleModelOptions.resolve(4, null).id shouldBe UpscaleModelOptions.defaultFor(4).id
        UpscaleModelOptions.resolve(2, "no_such_model").id shouldBe
            UpscaleModelOptions.defaultFor(2).id
        // A manga bucket id is not a colour model, whichever scale it names.
        UpscaleModelOptions.resolve(2, "mangajanai_2x_1600p").id shouldBe
            UpscaleModelOptions.defaultFor(2).id
    }

    @Test
    fun `a valid stored colour model is kept as it is`() {
        UpscaleModelOptions.resolve(2, "illustrationjanai_2x_v3detail_fdat_m").id shouldBe
            "illustrationjanai_2x_v3detail_fdat_m"
        UpscaleModelOptions.tierOf(
            UpscaleModelCatalog.find("illustrationjanai_2x_v3detail_fdat_m")!!,
        ) shouldBe UpscaleIllustrationTier.BALANCED
        // A V3 denoise entry is published but not curated.
        UpscaleModelOptions.tierOf(
            UpscaleModelCatalog.find("illustrationjanai_2x_v3denoise_span_s")!!,
        ) shouldBe null
    }
}
