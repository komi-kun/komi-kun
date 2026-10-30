// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The recogniser variant pins a *file name*, and the file name is what `ModelDownloader` and
// `PaddleOcrEngine` hand to the native engine: a wrong name is a silent runtime failure (the file is
// never downloaded and `nativeInit` fails). These tests pin the recogniser axis; which *release*
// publishes it is the detector × recogniser map tested in `ModelSelectionTest`.
// KUN <--
package mihon.core.ocr

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class RecModelVariantTest {

    @Test
    fun `each variant pins the file its weights are published under`() {
        RecModelVariant.FINE_TUNED.recModel shouldBe "ppocrv6_small_rec_manga_real.onnx"
        RecModelVariant.STOCK.recModel shouldBe "ppocrv6_small_rec.onnx"
    }

    @Test
    fun `the variants never share a recogniser file`() {
        // Both variants are published by two different releases each; sharing a file name would
        // make switching the recogniser a no-op because the downloader keys its cache on the name.
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
