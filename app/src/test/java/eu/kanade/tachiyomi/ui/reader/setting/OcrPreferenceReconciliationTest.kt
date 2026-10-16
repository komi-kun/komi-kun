// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Verifies the OCR preference split is a single source of truth (fix B3): the reader-facing display
// toggles live on ReaderPreferences (the keys the GPL-free reader gates/draws from) and never
// collide with the queue/source policy on OcrPreferences (the keys :core:ocr services read).
// KUN <--
package eu.kanade.tachiyomi.ui.reader.setting

import io.kotest.matchers.shouldBe
import mihon.core.ocr.OcrPreferences
import org.junit.jupiter.api.Test
import tachiyomi.core.common.ocr.ReaderOcrSource
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class OcrPreferenceReconciliationTest {

    @Test
    fun `reader display toggles are owned by ReaderPreferences with reader-safe defaults`() {
        val store = InMemoryPreferenceStore()
        val readerPreferences = ReaderPreferences(store)

        // Overlay off by default, so a fresh install behaves exactly as upstream until opted in.
        readerPreferences.ocrOverlayEnabled.get() shouldBe false
        // Outlines on, two-finger gesture off, active-block highlight on.
        readerPreferences.ocrOutlineVisible.get() shouldBe true
        readerPreferences.ocrTwoFingerGestureEnabled.get() shouldBe false
        readerPreferences.ocrActiveBlockHighlight.get() shouldBe true

        // The settings switch and the reader gate need only share this one key.
        readerPreferences.ocrOverlayEnabled.set(true)
        readerPreferences.ocrOverlayEnabled.get() shouldBe true
    }

    @Test
    fun `queue and source policy are owned by OcrPreferences`() {
        val preferences = OcrPreferences(InMemoryPreferenceStore())

        preferences.defaultSource.get() shouldBe ReaderOcrSource.AUTOMATIC.name
        preferences.autoOnDownload.get() shouldBe false
        preferences.parallelOcrLimit.get() shouldBe 1
        preferences.resolveSource(1L) shouldBe ReaderOcrSource.AUTOMATIC
    }

    @Test
    fun `per-manga source override wins over the default`() {
        val preferences = OcrPreferences(
            InMemoryPreferenceStore(
                sequenceOf(
                    InMemoryPreferenceStore.InMemoryPreference("pref_ocr_source", ReaderOcrSource.PADDLE.name, ""),
                    InMemoryPreferenceStore.InMemoryPreference("pref_ocr_source_42", ReaderOcrSource.MOKURO.name, ""),
                ),
            ),
        )

        preferences.resolveSource(42L) shouldBe ReaderOcrSource.MOKURO
        // A manga without an override keeps the global default.
        preferences.resolveSource(7L) shouldBe ReaderOcrSource.PADDLE
    }
}
