// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Tests for the user-tunable Paddle thresholds: defaults must reproduce the values that were
// hard-coded before the setting existed, and a non-default value must (a) reach the engine options
// and (b) change the cache signature so cached pages are not reused.
// KUN <--
package mihon.core.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class OcrPreferencesTest {

    private fun preferences() = OcrPreferences(InMemoryPreferenceStore())

    @Test
    fun `defaults match the previous hard-coded engine values`() {
        val options = preferences().detectionOptions(numThreads = 4)

        options.thresh shouldBe 0.15f
        options.boxThresh shouldBe 0.25f
        options.unclipRatio shouldBe 1.40f
        options.recConfidenceFloor shouldBe 0f
        options.numThreads shouldBe 4
    }

    @Test
    fun `defaults keep the cache variant unchanged`() {
        preferences().cacheSignature() shouldBe ""
    }

    @Test
    fun `a raised box threshold reaches the engine and invalidates the cache`() {
        val preferences = preferences()
        preferences.detBoxThreshold.set(45)

        preferences.detectionOptions(numThreads = 2).boxThresh shouldBe 0.45f
        preferences.cacheSignature() shouldBe "t15b45u140r0"
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
