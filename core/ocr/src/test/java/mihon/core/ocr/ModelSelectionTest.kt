// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The model selection is two independent axes — detector × recogniser — and each of the four
// combinations is a distinct release tag. These tests pin the matrix itself (a missing or duplicated
// cell ships the wrong detector: before the axis existed the release alone chose it, and the
// axis-less default `ocr-manga-v2` was the stock detector plus the fine-tuned recogniser — the SFX
// detector was never a default), the dialogue-first default, and the fact that a recogniser the user
// chose under the old single-axis key keeps resolving to the same recogniser.
// KUN <--
package mihon.core.ocr

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class ModelSelectionTest {

    private fun preferences(store: InMemoryPreferenceStore = InMemoryPreferenceStore()) = OcrPreferences(store)

    @Test
    fun `each detector and recogniser pair maps to its own release`() {
        ModelRelease.of(DetModelVariant.DIALOGUE, RecModelVariant.FINE_TUNED).release shouldBe "ocr-manga-v4"
        ModelRelease.of(DetModelVariant.DIALOGUE, RecModelVariant.STOCK).release shouldBe "ocr-models-v3"
        ModelRelease.of(DetModelVariant.SFX, RecModelVariant.FINE_TUNED).release shouldBe "ocr-manga-v3"
        ModelRelease.of(DetModelVariant.SFX, RecModelVariant.STOCK).release shouldBe "ocr-models-v4"

        // All four cells exist, and no two cells share a tag: a duplicated tag would silently serve
        // one cell's weights to another. The cartesian product check means a new axis value with no
        // release mapping fails here instead of falling back to another variant's weights at runtime.
        val combos = DetModelVariant.entries.flatMap { det ->
            RecModelVariant.entries.map { rec -> det to rec }
        }
        ModelRelease.entries.size shouldBe 4
        ModelRelease.entries.map { it.det to it.rec }.toSet() shouldBe combos.toSet()
        ModelRelease.entries.map { it.release }.toSet().size shouldBe 4
    }

    @Test
    fun `the download root is derived from the release tag`() {
        ModelRelease.of(DetModelVariant.DIALOGUE, RecModelVariant.FINE_TUNED).baseUrl shouldBe
            "https://github.com/komi-kun/komi-kun-ocr-models/releases/download/ocr-manga-v4"
        ModelRelease.of(DetModelVariant.SFX, RecModelVariant.FINE_TUNED).baseUrl shouldBe
            "https://github.com/komi-kun/komi-kun-ocr-models/releases/download/ocr-manga-v3"
    }

    @Test
    fun `every release publishes the same detector file name`() {
        // This is the whole reason the downloader records the release tag instead of the file set:
        // the dialogue and SFX detectors are the same path with different bytes, so only the tag can
        // tell them apart — the engine cannot either, which is why it keys its reload on the
        // *installed* release + file fingerprint (`installedModelIdentity`), never on the selection.
        ModelRelease.entries.map { it.det.detModel }.toSet() shouldBe setOf("ppocrv6_small_det.onnx")
        DetModelVariant.DIALOGUE.detModel shouldBe "ppocrv6_small_det.onnx"
        DetModelVariant.SFX.detModel shouldBe "ppocrv6_small_det.onnx"
    }

    @Test
    fun `nothing stored is the dialogue detector with the fine-tuned recogniser`() {
        val preferences = preferences()

        preferences.resolveDetModel() shouldBe DetModelVariant.DIALOGUE
        preferences.resolveRecModel() shouldBe RecModelVariant.FINE_TUNED
        preferences.resolveModelRelease() shouldBe ModelRelease.DIALOGUE_FINE_TUNED
        preferences.resolveModelRelease().release shouldBe "ocr-manga-v4"
        // The detector the previous builds always installed (SFX) must not be the default. Not a
        // `shouldNotBe "ocr-manga-v3"` after the `shouldBe "ocr-manga-v4"` above (unreachable), but a
        // property of the store: nothing was ever chosen, so nothing inherited an old detector.
        preferences.detModelVariant.isSet() shouldBe false
        preferences.recModelVariant.isSet() shouldBe false
    }

    @Test
    fun `an explicitly stored recogniser choice is preserved and only swaps the recogniser`() {
        // A build before the detector axis stored only `pref_ocr_rec_model`; a user who explicitly
        // picked STOCK must keep the stock recogniser, with the detector moving to the dialogue
        // default (which is `ocr-models-v3`, not the SFX stock release they had before).
        val store = InMemoryPreferenceStore()
        store.getString("pref_ocr_rec_model", RecModelVariant.FINE_TUNED.name).set(RecModelVariant.STOCK.name)

        val preferences = OcrPreferences(store)
        preferences.resolveRecModel() shouldBe RecModelVariant.STOCK
        preferences.resolveDetModel() shouldBe DetModelVariant.DIALOGUE
        preferences.resolveModelRelease().release shouldBe "ocr-models-v3"
        preferences.recModelFile() shouldBe "ppocrv6_small_rec.onnx"

        // The fine-tuned choice is preserved too — and must not be re-pointed at the SFX release.
        store.getString("pref_ocr_rec_model", RecModelVariant.STOCK.name).set(RecModelVariant.FINE_TUNED.name)
        preferences.resolveModelRelease().release shouldBe "ocr-manga-v4"
        preferences.recModelFile() shouldBe "ppocrv6_small_rec_manga_real.onnx"
    }

    @Test
    fun `an explicitly stored detector choice is honoured`() {
        val preferences = preferences()
        preferences.detModelVariant.set(DetModelVariant.SFX.name)

        preferences.resolveModelRelease().release shouldBe "ocr-manga-v3"
        // There is no selection-derived `modelKey()` any more: the engine keys its reload on what is
        // installed (see `InstalledModelIdentityTest`), so only the release is asserted here.

        // An unknown stored value falls back to dialogue rather than to the SFX detector.
        preferences.detModelVariant.set("NONSENSE")
        preferences.resolveDetModel() shouldBe DetModelVariant.DIALOGUE
        preferences.resolveModelRelease().release shouldBe "ocr-manga-v4"
    }

    @Test
    fun `the detector and recogniser keys are the ones the previous build and the settings screen use`() {
        val preferences = preferences()

        // The recogniser key is unchanged, which is what makes a legacy explicit choice survive.
        preferences.recModelVariant.key() shouldBe "pref_ocr_rec_model"
        // A new key: every install that never chose a detector resolves to the dialogue default.
        preferences.detModelVariant.key() shouldBe "pref_ocr_det_model"
        preferences.detModelVariant.isSet() shouldBe false
        preferences.detModelVariant.get() shouldBe DetModelVariant.DIALOGUE.name
    }
}
