// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The OCR popup anchor is an app-graph singleton shared with the dictionary popup, so anything it
// still points at after the reader is gone leaks: `screenshot` holds a provider that captures the
// `ReaderPage` it was opened on. The reader's OCR **session** (one per reader activity, created by
// `ReaderOcrHostImpl.newSession`, closed by `ReaderActivity.onDestroy`) owns that release: `close()`
// must clear the anchor, close the popup and stop the page work, and a session closed for a
// configuration change must hand its per-reader state to the session the recreated reader creates
// instead of releasing anything — the popup has to survive a rotation.
//
// Pinned here because the Android lifecycle ordering itself is not testable in this module; the
// anchor, the block loader and the dictionary host are real objects or recording fakes, and the
// session is the production one.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.ocr.impl

import android.content.Context
import android.graphics.RectF
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import io.mockk.clearMocks
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
    fun `closing the reader's session clears the anchor, closes the popup and stops the page work`() {
        val anchor = ReaderOcrPopupAnchor().apply {
            rect.value = mockk<RectF>()
            screenshot.value = pageScreenshot
        }
        val dictionaryHost = mockk<ReaderDictionaryHost>(relaxed = true)
        val blockLoader = mockk<ReaderOcrBlockLoader>(relaxed = true)
        val session = host(anchor, dictionaryHost, blockLoader).newSession()

        session.close()

        anchor.screenshot.value shouldBe null
        anchor.rect.value shouldBe null
        verify(exactly = 1) { dictionaryHost.dismiss() }
        // The page work reads the chapter archive, which the reader recycles right after this: a load
        // left running across the exit reads an unmapped file. Without this call nothing else stops
        // it — the loader's own scope is app-lifetime.
        verify(exactly = 1) { blockLoader.detach() }
    }

    /**
     * A configuration change closes the activity's session and creates a new one before the reader
     * draws again. Nothing the *reader* owns may be released on that path — the open lookup has to be
     * re-anchored by the new session's per-frame loop — and the new session must adopt the closed
     * one's state rather than start blank, or the popup keeps its pre-rotation rect over a page that
     * has just been re-laid-out.
     */
    @Test
    fun `a configuration change hands the open lookup to the session the recreated reader creates`() {
        val anchor = ReaderOcrPopupAnchor().apply {
            rect.value = mockk<RectF>()
            screenshot.value = pageScreenshot
        }
        val dictionaryHost = mockk<ReaderDictionaryHost>(relaxed = true)
        val blockLoader = mockk<ReaderOcrBlockLoader>(relaxed = true)
        val host = host(anchor, dictionaryHost, blockLoader)

        val rotating = host.newSession()
        rotating.close(recreating = true)

        anchor.screenshot.value shouldBeSameInstanceAs pageScreenshot
        verify(exactly = 0) { dictionaryHost.dismiss() }
        verify(exactly = 0) { blockLoader.detach() }

        val recreated = host.newSession()
        (recreated as ReaderOcrSessionImpl).state shouldBeSameInstanceAs (rotating as ReaderOcrSessionImpl).state

        // ...and the reader really going away afterwards does release everything, once.
        recreated.close()

        anchor.screenshot.value shouldBe null
        verify(exactly = 1) { dictionaryHost.dismiss() }
        verify(exactly = 1) { blockLoader.detach() }
    }

    /**
     * The handover slot is one-shot: a reader opened after the previous one closed must not inherit a
     * dead reader's lookup (or a popup anchor that still holds its captured page).
     */
    @Test
    fun `a reader opened after the previous one closed starts from an empty session`() {
        val anchor = ReaderOcrPopupAnchor().apply { screenshot.value = pageScreenshot }
        val host = host(anchor, mockk<ReaderDictionaryHost>(relaxed = true))

        val first = host.newSession()
        first.close(recreating = true)
        val recreated = host.newSession()
        recreated.close()
        val next = host.newSession()

        (next as ReaderOcrSessionImpl).state shouldNotBeSameInstanceAs (first as ReaderOcrSessionImpl).state
        anchor.screenshot.value shouldBe null
    }

    /**
     * `onDestroy` may run a stale second close, and by then the *next* reader can already own the
     * shared anchor and the page work. A close that is not guarded by "have I already closed?" takes
     * that reader's popup anchor away and stops its page loads.
     */
    @Test
    fun `a stale second close cannot release the next reader's state`() {
        val anchor = ReaderOcrPopupAnchor()
        val dictionaryHost = mockk<ReaderDictionaryHost>(relaxed = true)
        val blockLoader = mockk<ReaderOcrBlockLoader>(relaxed = true)
        val host = host(anchor, dictionaryHost, blockLoader)

        val closed = host.newSession()
        closed.close()

        // The next reader is alive and has a lookup open.
        host.newSession()
        anchor.rect.value = mockk<RectF>()
        anchor.screenshot.value = pageScreenshot
        clearMocks(dictionaryHost, blockLoader, answers = false)

        closed.close()

        anchor.screenshot.value shouldBeSameInstanceAs pageScreenshot
        verify(exactly = 0) { dictionaryHost.dismiss() }
        verify(exactly = 0) { blockLoader.detach() }
    }

    /**
     * Every collaborator other than [anchor]/[dictionaryHost]/`blockLoader` is mocked and closing a
     * session must not touch them (`context` in particular: nothing here reads resources) — the block
     * loader is the one other collaborator it *must* stop, and that is verified per test.
     */
    private fun host(
        anchor: ReaderOcrPopupAnchor,
        dictionaryHost: ReaderDictionaryHost,
        blockLoader: ReaderOcrBlockLoader = mockk(relaxed = true),
    ) = ReaderOcrHostImpl(
        blockLoader = blockLoader,
        readerPreferences = mockk<ReaderPreferences>(relaxed = true),
        dictionaryPreferences = mockk<DictionaryPreferences>(relaxed = true),
        dictionaryHost = dictionaryHost,
        popupAnchor = anchor,
        context = mockk<Context>(relaxed = true),
    )
}
