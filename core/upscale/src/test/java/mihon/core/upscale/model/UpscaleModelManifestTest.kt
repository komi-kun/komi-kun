// SPDX-License-Identifier: GPL-3.0-or-later
// The catalog / release drift guard.
//
// `src/test/resources/upscale-manifest.json` is a byte-identical copy of the `manifest.json` asset in
// the release `ModelDownloader.DEFAULT_MANIFEST_URL` pins (`upscale-models-v1` on
// `komi-kun/komi-kun-upscale-models`), produced by `tools/upscale-models/fetch_and_publish.py`.
//
// This test parses the published manifest with the production DTOs, so the two halves of the model
// identity can never drift: a catalog model the release does not publish (a download that resolves to
// HTTP 404), a release asset the catalog does not name (an install the router can never route to) and
// a pinned tag that no longer matches the published baseUrl all fail here, at test time, instead of
// at runtime on a device. Re-publishing the release therefore means copying the new manifest over this
// resource in the same change.
package mihon.core.upscale.model

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class UpscaleModelManifestTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** The published release's `manifest.json`, parsed exactly as [ModelDownloader] parses it. */
    private val manifest: UpscaleModelManifest = json.decodeFromString(
        resource("upscale-manifest.json"),
    )

    @Test
    fun `the checked-in manifest parses as the pinned release`() {
        manifest.release shouldBe "upscale-models-v1"
        manifest.baseUrl shouldBe ModelDownloader.DEFAULT_MANIFEST_URL.substringBeforeLast('/')
        manifest.files shouldNotBe emptyList<UpscaleModelFile>()
    }

    @Test
    fun `every catalog model is published with a verifiable sha256 and a size`() {
        val byId = manifest.files.associateBy { it.id }

        for (model in UpscaleModelCatalog.models) {
            val entry = byId[model.id]
            entry shouldNotBe null
            entry!!.name shouldBe model.fileName
            entry.sha256.matches(SHA256_HEX) shouldBe true
            (entry.size > 0L) shouldBe true
            // The settings screen's size label is this number, not a guess.
            entry.size shouldBe model.approxBytes
        }
    }

    @Test
    fun `the manifest publishes nothing the catalog cannot route`() {
        // The reverse direction: a file the app would download but never select is a wasted asset,
        // and `catalogIdFor` is how the downloader recovers an id from a manifest that omits one.
        manifest.files.map { it.id } shouldContainExactlyInAnyOrder
            UpscaleModelCatalog.models.map { it.id }
        manifest.files.forEach { entry ->
            catalogIdFor(entry.name) shouldBe entry.id
            // Flat release assets: the downloader builds `<baseUrl>/<path>` and nothing else.
            entry.path shouldBe entry.name
        }
    }

    @Test
    fun `the manifest identity fields match the catalog`() {
        for (entry in manifest.files) {
            val model = UpscaleModelCatalog.find(entry.id)
            model shouldNotBe null
            entry.scale shouldBe model!!.scale
            entry.family shouldBe model.family.name
            entry.bucket shouldBe (model.bucketHeight ?: 0)
        }
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "missing test resource $name" }
            .use { it.readBytes().decodeToString() }

    private companion object {
        /** 64 lower-case hex characters — what `sha256Hex` produces and `downloadAndVerify` compares. */
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}
