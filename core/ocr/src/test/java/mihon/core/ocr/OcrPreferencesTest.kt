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
        // The sliders only drive the engine once advanced tuning is opted in.
        preferences.advancedTuning.set(true)
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
        preferences.advancedTuning.set(true)
        preferences.recConfidenceFloor.set(60)

        preferences.detectionOptions(numThreads = 4).recConfidenceFloor shouldBe 0.60f
    }

    @Test
    fun `out-of-range stored values are clamped`() {
        val preferences = preferences()
        preferences.advancedTuning.set(true)
        preferences.detUnclipRatio.set(500)
        preferences.recConfidenceFloor.set(-10)

        val options = preferences.detectionOptions(numThreads = 4)
        options.unclipRatio shouldBe 3.00f
        options.recConfidenceFloor shouldBe 0f
    }

    // ---------------------------------------------------------------------------------------
    // Advanced tuning: the four sliders are opt-in and the engine defaults are what runs while
    // they are off. The stored values must survive the opt-out so the sliders can come back.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `advanced tuning is off unless the user turns it on`() {
        val preferences = preferences()

        preferences.advancedTuning.get() shouldBe false
        preferences.advancedTuning.isSet() shouldBe false
    }

    @Test
    fun `with advanced tuning off the engine runs the defaults, not the stored slider values`() {
        val preferences = preferences()
        preferences.detPixelThreshold.set(55)
        preferences.detBoxThreshold.set(45)
        preferences.detUnclipRatio.set(200)
        preferences.recConfidenceFloor.set(70)

        preferences.effectiveTuning() shouldBe OcrEngineTuning(
            detPixelThreshold = 15,
            detBoxThreshold = 25,
            detUnclipRatio = 140,
            recConfidenceFloor = 35,
        )

        val options = preferences.detectionOptions(numThreads = 4)
        options.thresh shouldBe 0.15f
        options.boxThresh shouldBe 0.25f
        options.unclipRatio shouldBe 1.40f
        options.recConfidenceFloor shouldBe 0.35f
    }

    @Test
    fun `turning advanced tuning off leaves the stored values alone and turning it on restores them`() {
        val preferences = preferences()
        preferences.advancedTuning.set(true)
        preferences.detBoxThreshold.set(45)
        preferences.detUnclipRatio.set(200)

        preferences.advancedTuning.set(false)

        // Inactive...
        preferences.effectiveTuning().detBoxThreshold shouldBe 25
        preferences.effectiveTuning().detUnclipRatio shouldBe 140
        // ...but still stored, so the sliders show them again when re-enabled.
        preferences.detBoxThreshold.get() shouldBe 45
        preferences.detUnclipRatio.get() shouldBe 200

        preferences.advancedTuning.set(true)
        preferences.effectiveTuning() shouldBe OcrEngineTuning(
            detPixelThreshold = 15,
            detBoxThreshold = 45,
            detUnclipRatio = 200,
            recConfidenceFloor = 35,
        )
    }

    @Test
    fun `reset writes exactly the built-in defaults`() {
        val preferences = preferences()
        preferences.advancedTuning.set(true)
        preferences.detPixelThreshold.set(55)
        preferences.detBoxThreshold.set(45)
        preferences.detUnclipRatio.set(200)
        preferences.recConfidenceFloor.set(60)

        preferences.resetTuningToDefaults()

        preferences.detPixelThreshold.get() shouldBe 15
        preferences.detBoxThreshold.get() shouldBe 25
        preferences.detUnclipRatio.get() shouldBe 140
        preferences.recConfidenceFloor.get() shouldBe 35
        preferences.effectiveTuning() shouldBe OcrEngineTuning(
            detPixelThreshold = 15,
            detBoxThreshold = 25,
            detUnclipRatio = 140,
            recConfidenceFloor = 35,
        )
    }

    @Test
    fun `stored but inactive slider values change neither the cache signature nor the engine input`() {
        val untouched = preferences()
        val tunedButOff = preferences()
        tunedButOff.detPixelThreshold.set(55)
        tunedButOff.detBoxThreshold.set(45)
        tunedButOff.detUnclipRatio.set(200)
        tunedButOff.recConfidenceFloor.set(70)

        // Same cached pages and same native thresholds as a fresh install: the opt-out is what
        // decides, not what the sliders happen to hold.
        tunedButOff.cacheSignature() shouldBe untouched.cacheSignature()
        tunedButOff.detectionOptions(numThreads = 4) shouldBe untouched.detectionOptions(numThreads = 4)

        // And they do bite once the user opts in.
        tunedButOff.advancedTuning.set(true)
        tunedButOff.cacheSignature() shouldNotBe untouched.cacheSignature()
        tunedButOff.detectionOptions(numThreads = 4).boxThresh shouldBe 0.45f
    }

    // ---------------------------------------------------------------------------------------
    // Detector resolution (`pref_ocr_det_resolution`): the fifth member of the advanced group.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the detector resolution defaults to 960`() {
        val preferences = preferences()

        preferences.detResolution.get() shouldBe OcrDetResolution.DEFAULT.name
        preferences.effectiveTuning().detResolution shouldBe OcrDetResolution.DEFAULT
        preferences.effectiveTuning().detResolution.limitFor(2385) shouldBe 960
        preferences.effectiveDetectorLimit(2385) shouldBe 960
    }

    @Test
    fun `with advanced tuning off a stored resolution is ignored and 960 is used`() {
        val preferences = preferences()
        // Stored, but the switch is off: the engine must run 960, not the stored 1600.
        preferences.detResolution.set(OcrDetResolution.VERY_HIGH.name)

        preferences.effectiveTuning().detResolution shouldBe OcrDetResolution.DEFAULT
        preferences.effectiveDetectorLimit(2385) shouldBe 960

        // Nothing was erased: turning the switch on brings the choice back.
        preferences.advancedTuning.set(true)
        preferences.effectiveTuning().detResolution shouldBe OcrDetResolution.VERY_HIGH
    }

    @Test
    fun `turning advanced tuning off and on again preserves the stored resolution`() {
        val preferences = preferences()
        preferences.advancedTuning.set(true)
        preferences.detResolution.set(OcrDetResolution.HIGH.name)
        preferences.effectiveTuning().detResolution shouldBe OcrDetResolution.HIGH

        preferences.advancedTuning.set(false)
        preferences.effectiveTuning().detResolution shouldBe OcrDetResolution.DEFAULT
        preferences.detResolution.get() shouldBe OcrDetResolution.HIGH.name

        preferences.advancedTuning.set(true)
        preferences.effectiveTuning().detResolution shouldBe OcrDetResolution.HIGH
    }

    @Test
    fun `auto picks the smallest limit that keeps 60 percent of the chunk's long side`() {
        // The rule, on the sizes that matter: the annotation set (all <= 1600) stays at 960, and the
        // two working pages (long side 2385) go to 1600.
        OcrDetResolution.AUTO.limitFor(1000) shouldBe 960
        OcrDetResolution.AUTO.limitFor(1600) shouldBe 960 // exactly 60 % of 1600 is the 960 rung
        OcrDetResolution.AUTO.limitFor(2000) shouldBe 1280
        OcrDetResolution.AUTO.limitFor(2385) shouldBe 1600
        OcrDetResolution.AUTO.limitFor(2828) shouldBe 1600 // above the cap: still 1600

        // The fixed modes ignore the chunk size entirely.
        OcrDetResolution.DEFAULT.limitFor(2385) shouldBe 960
        OcrDetResolution.HIGH.limitFor(2385) shouldBe 1280
        OcrDetResolution.VERY_HIGH.limitFor(2385) shouldBe 1600
    }

    @Test
    fun `the resolution mode changes the cache signature only while it is active`() {
        val untouched = preferences()
        val advancedDefault = preferences()
        advancedDefault.advancedTuning.set(true)

        // Opting in without touching the resolution changes nothing: the default mode contributes no
        // tag, so pages cached by an older build stay valid.
        advancedDefault.cacheSignature() shouldBe untouched.cacheSignature()

        // A non-default mode is a different detector input, so its cached pages must not be reused.
        advancedDefault.detResolution.set(OcrDetResolution.VERY_HIGH.name)
        advancedDefault.cacheSignature() shouldNotBe untouched.cacheSignature()
        advancedDefault.detResolution.set(OcrDetResolution.AUTO.name)
        advancedDefault.cacheSignature() shouldNotBe untouched.cacheSignature()

        // Stored but inactive: back to the untouched signature.
        advancedDefault.advancedTuning.set(false)
        advancedDefault.cacheSignature() shouldBe untouched.cacheSignature()
    }

    @Test
    fun `reset restores the detector resolution to 960 and leaves the other choices alone`() {
        val preferences = preferences()
        preferences.advancedTuning.set(true)
        preferences.detResolution.set(OcrDetResolution.AUTO.name)
        preferences.detBoxThreshold.set(45)

        preferences.resetTuningToDefaults()

        preferences.detResolution.get() shouldBe OcrDetResolution.DEFAULT.name
        preferences.effectiveTuning().detResolution shouldBe OcrDetResolution.DEFAULT
        preferences.effectiveDetectorLimit(2385) shouldBe 960
        // The reset is about the tuning values only.
        preferences.advancedTuning.get() shouldBe true
        preferences.detModelVariant.get() shouldBe DetModelVariant.DEFAULT.name
        preferences.recModelVariant.get() shouldBe RecModelVariant.DEFAULT.name
    }

    @Test
    fun `a resolution above what the pinned native applies is clamped to 960`() {
        val preferences = preferences()
        preferences.advancedTuning.set(true)
        preferences.detResolution.set(OcrDetResolution.VERY_HIGH.name)

        // `libpaddle_ocr.so` (ocr-models-v4) has no limit-side argument, so the frame helpers must be
        // handed the limit it really applies; a bigger number would mis-predict the frame and defeat
        // the 4 MP frame-preservation clamp. The mode itself is still what the user (and the cache
        // signature) sees.
        preferences.effectiveTuning().detResolution shouldBe OcrDetResolution.VERY_HIGH
        preferences.effectiveDetectorLimit(2385) shouldBe 960
    }
}
