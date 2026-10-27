// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Tests for the user-tunable Paddle thresholds: defaults must reproduce the values that were
// hard-coded before the setting existed, and a non-default value must (a) reach the engine options
// and (b) change the cache signature so cached pages are not reused.
// KUN <--
package mihon.core.ocr

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class OcrPreferencesTest {

    private fun preferences() = OcrPreferences(InMemoryPreferenceStore())

    @Test
    fun `defaults match the expected engine values`() {
        val options = preferences().detectionOptions(numThreads = 4)

        options.thresh shouldBe 0.15f
        options.boxThresh shouldBe 0.25f
        options.unclipRatio shouldBe 1.40f
        options.recConfidenceFloor shouldBe 0.35f
        options.numThreads shouldBe 4
    }

    @Test
    fun `the default selection still has a cache signature of its own`() {
        // Regression: the signature used to be `""` at the defaults, and that `""` carried no
        // detector — so a page OCR'd by the SFX detector was served as dialogue. The release tag is
        // now unconditional, so the two can never meet again: the default is v4 (dialogue), and the
        // old axis-less default was v2 (stock detector + fine-tuned recogniser), never a SFX one.
        val signature = preferences().cacheSignature()

        // The release tag is the leading, unconditional part. Asserted by prefix — not as the exact
        // string — so the threshold separator/format is free to change without failing this test;
        // that the tag is the *dialogue* release already rules out the SFX default's signature.
        signature shouldStartWith "ocr-manga-v4"
        signature shouldNotBe ""
    }

    @Test
    fun `a raised box threshold reaches the engine and invalidates the cache`() {
        val preferences = preferences()
        preferences.detBoxThreshold.set(45)

        preferences.detectionOptions(numThreads = 2).boxThresh shouldBe 0.45f
        preferences.cacheSignature() shouldStartWith "ocr-manga-v4"
        // The property that matters: a page recognised with the old thresholds is not served for the
        // new ones.
        preferences.cacheSignature() shouldNotBe preferences().cacheSignature()
    }

    @Test
    fun `switching the detector invalidates the cached page signature`() {
        val preferences = preferences()
        // The detector changes the *boxes*, so cached pages recognised with the other detector must
        // not be served for the same page.
        preferences.cacheSignature() shouldStartWith "ocr-manga-v4"

        preferences.detModelVariant.set(DetModelVariant.SFX.name)

        // The release tag is what changes (v4 dialogue -> v3 SFX), so the signatures differ.
        preferences.cacheSignature() shouldStartWith "ocr-manga-v3"
        preferences.cacheSignature() shouldNotBe preferences().cacheSignature()
    }

    @Test
    fun `a recognition floor reaches the engine`() {
        val preferences = preferences()
        preferences.recConfidenceFloor.set(60)

        preferences.detectionOptions(numThreads = 4).recConfidenceFloor shouldBe 0.60f
    }

    @Test
    fun `out-of-range stored values are clamped`() {
        val preferences = preferences()
        preferences.detUnclipRatio.set(500)
        preferences.recConfidenceFloor.set(-10)

        val options = preferences.detectionOptions(numThreads = 4)
        options.unclipRatio shouldBe 3.00f
        options.recConfidenceFloor shouldBe 0f
    }
}
