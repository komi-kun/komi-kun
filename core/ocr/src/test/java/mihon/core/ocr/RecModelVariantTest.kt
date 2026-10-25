// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The recogniser variant pins a *release tag*, and the tag is the only thing that says which bytes
// `ModelDownloader` will fetch: `paddle-manifest.json` is not consulted for models (the build-time
// fetch skips entries without an `abi`). A stale tag is therefore a silent runtime failure - the
// manifest request 404s and the model never downloads. These tests pin the mapping so a bump that
// forgets one of the two variants cannot pass.
// KUN <--
package mihon.core.ocr

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class RecModelVariantTest {

    @Test
    fun `each variant pins the release that hosts its recogniser`() {
        RecModelVariant.FINE_TUNED.recModel shouldBe "ppocrv6_small_rec_manga_real.onnx"
        RecModelVariant.FINE_TUNED.release shouldBe "ocr-manga-v3"
        RecModelVariant.STOCK.recModel shouldBe "ppocrv6_small_rec.onnx"
        RecModelVariant.STOCK.release shouldBe "ocr-models-v4"
    }

    @Test
    fun `the download root is derived from the release tag`() {
        RecModelVariant.FINE_TUNED.baseUrl shouldBe
            "https://github.com/komi-kun/komi-kun-ocr-models/releases/download/ocr-manga-v3"
        RecModelVariant.STOCK.baseUrl shouldBe
            "https://github.com/komi-kun/komi-kun-ocr-models/releases/download/ocr-models-v4"
    }

    @Test
    fun `the variants never share a release or a recogniser file`() {
        // Each release hosts exactly one recogniser, so collapsing the two tags onto one release
        // would 404 whichever variant it does not host; sharing a file name would make switching
        // variants a no-op because the downloader keys its cache on the name.
        RecModelVariant.FINE_TUNED.release shouldNotBe RecModelVariant.STOCK.release
        RecModelVariant.FINE_TUNED.recModel shouldNotBe RecModelVariant.STOCK.recModel
    }

    @Test
    fun `the default is the fine-tuned recogniser and unknown stored values fall back to it`() {
        RecModelVariant.DEFAULT shouldBe RecModelVariant.FINE_TUNED
        RecModelVariant.from(null) shouldBe RecModelVariant.FINE_TUNED
        RecModelVariant.from("STOCK") shouldBe RecModelVariant.STOCK
        RecModelVariant.from("ocr-manga-v1") shouldBe RecModelVariant.FINE_TUNED
    }
}
