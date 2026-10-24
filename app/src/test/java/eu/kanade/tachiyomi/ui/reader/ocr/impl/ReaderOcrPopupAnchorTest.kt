// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The OCR popup anchor is an app-graph singleton (`@SingleIn(AppScope::class)`), so anything it
// still points at after the reader is gone leaks: `screenshot` holds a provider that captures the
// `ReaderPage` it was opened on. `clear()` must drop it, and the reader host's `detach()` (called
// from `ReaderActivity.onDestroy`, except across a configuration change) must go through `clear()`
// and close the popup. Pinned here because the Android lifecycle ordering itself is not testable in
// this module.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.ocr.impl

import android.content.Context
import android.graphics.RectF
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import mihon.core.dictionary.anki.AnkiScreenshotProvider
import mihon.dictionary.host.ReaderDictionaryHost
import org.junit.jupiter.api.Test

class ReaderOcrPopupAnchorTest {

    /** A stand-in for the provider that captures the reader's page (bytes never requested here). */
    private val pageScreenshot = AnkiScreenshotProvider { ByteArray(3) }

    @Test
    fun `clear drops the screenshot provider so the reader page is not retained`() {
        val anchor = ReaderOcrPopupAnchor()
        val rect = mockk<RectF>()
        anchor.rect.value = rect
        anchor.popupBounds.value = rect
        anchor.screenshot.value = pageScreenshot

        anchor.clear()

        // The leak that matters: a non-null provider keeps the closed reader's page reachable.
        anchor.screenshot.value shouldBe null
        anchor.rect.value shouldBe null
        anchor.popupBounds.value shouldBe null
    }

    @Test
    fun `detaching the host clears the anchor and closes the popup`() {
        val anchor = ReaderOcrPopupAnchor().apply {
            rect.value = mockk<RectF>()
            screenshot.value = pageScreenshot
        }
        val dictionaryHost = mockk<ReaderDictionaryHost>(relaxed = true)
        val host = host(anchor, dictionaryHost)

        host.detach()

        anchor.screenshot.value shouldBe null
        anchor.rect.value shouldBe null
        verify(exactly = 1) { dictionaryHost.dismiss() }
    }

    @Test
    fun `detaching twice is harmless`() {
        val anchor = ReaderOcrPopupAnchor()
        val dictionaryHost = mockk<ReaderDictionaryHost>(relaxed = true)
        val host = host(anchor, dictionaryHost)

        host.detach()
        host.detach()

        anchor.screenshot.value shouldBe null
        verify(exactly = 2) { dictionaryHost.dismiss() }
    }

    /**
     * Every collaborator other than [anchor]/[dictionaryHost] is mocked and `detach()` must not touch
     * them (`context` in particular: nothing here reads resources).
     */
    private fun host(anchor: ReaderOcrPopupAnchor, dictionaryHost: ReaderDictionaryHost) = ReaderOcrHostImpl(
        blockLoader = mockk(relaxed = true),
        readerPreferences = mockk<ReaderPreferences>(relaxed = true),
        dictionaryPreferences = mockk<DictionaryPreferences>(relaxed = true),
        dictionaryHost = dictionaryHost,
        popupAnchor = anchor,
        context = mockk<Context>(relaxed = true),
    )
}
