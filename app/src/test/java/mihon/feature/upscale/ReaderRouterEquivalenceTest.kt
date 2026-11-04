// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The reader's pre-decode bucket rule (`UpscaleRuntimePolicy`, Apache) and the router's post-decode
// one (`mihon.core.upscale.model.route`, GPL) implement the same nearest-bucket rule twice: once so
// the reader can look up a cached entry before it knows the page's height, once so the engine decides
// what to run. Only `:app` sees both.
//
// A divergence is silent and total: the reader would look up (and so serve) a bucket the router never
// writes, or the router would write one the reader never looks up, and upscaling simply stops
// producing cache hits with nothing failing. This test cross-checks them over every page height.
//
// Not covered: the illustration half of the rule, which the reader resolves from a preference id and
// the router from the catalog (`UpscaleRouterTest` pins that side), and the colour/grayscale verdict,
// which only the router takes.
// KUN <--
package mihon.feature.upscale

import eu.kanade.tachiyomi.ui.reader.upscale.UpscaleModelRef
import eu.kanade.tachiyomi.ui.reader.upscale.UpscaleRuntimePolicy
import mihon.core.upscale.model.ModelFamily
import mihon.core.upscale.model.UpscaleModelCatalog
import mihon.core.upscale.model.route
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import tachiyomi.core.common.preference.UpscaleMode

class ReaderRouterEquivalenceTest {

    /**
     * Catches the two rules drifting apart on any page height: the reader would then look up a
     * MangaJaNai bucket the router never writes for that page (no cache hit ever again) or vice versa,
     * with no test failing and upscaling silently doing nothing for the whole manga family.
     */
    @Test
    fun `the reader's bucket rule and the router agree for every page height`() {
        for (scale in UpscaleModelCatalog.scales) {
            val installed = UpscaleModelCatalog.forFamilyAndScale(ModelFamily.MANGA_JANAI, scale)
            if (installed.isEmpty()) fail("the catalog publishes no MangaJaNai model at ${scale}x")
            val refs = installed.map { UpscaleModelRef(id = it.id, scale = it.scale, bucketHeight = it.bucketHeight) }
            val ids = installed.map { it.id }.toSet()

            for (height in 1..4096) {
                val readerBucket = UpscaleRuntimePolicy.eligibleModels(
                    pageHeight = height,
                    mode = UpscaleMode.MANGA_ONLY,
                    scale = scale,
                    installed = refs,
                    illustrationModelId = null,
                ).singleOrNull()?.id
                val routerBucket = route(
                    isGrayscale = true,
                    pageHeight = height,
                    mode = UpscaleMode.MANGA_ONLY,
                    scale = scale,
                    installedIds = ids,
                    illustrationModelId = null,
                )?.id

                if (readerBucket != routerBucket) {
                    fail(
                        "${scale}x page height $height: the reader looks up $readerBucket but the " +
                            "router wrote $routerBucket",
                    )
                }
            }
        }
    }
}
