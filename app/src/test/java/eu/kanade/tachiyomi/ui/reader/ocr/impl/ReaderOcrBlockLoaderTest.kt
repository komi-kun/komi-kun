// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Tests for the reader OCR block-loader watchdog (F2) and cancellation propagation (F1).
//
// The loader resolves a page through the source policy inside `withTimeoutOrNull`; if that call
// outlives the watchdog it must be treated exactly like "not resolvable yet" (retry later, never
// cache an empty result), and a `CancellationException` must be rethrown rather than logged and
// retried. Both are observable purely through the loader's public `blocks` state plus how many
// times the (mocked) `GetManga.await` is entered — no `Context` is needed: every collaborator is
// mocked and the load never gets past `GetManga.await`.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.ocr.impl

import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mihon.core.ocr.OcrEngine
import mihon.core.ocr.cache.OcrCacheKey
import mihon.core.ocr.cache.OcrManager
import mihon.core.ocr.cache.OcrQueueItem
import mihon.core.ocr.model.OcrTextBlock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.ocr.ReaderOcrSource
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReaderOcrBlockLoaderTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val getManga = mockk<GetManga>()
    private val awaitCalls = AtomicInteger(0)

    private val chapterModel = Chapter.create().copy(id = 42L, mangaId = 7L, name = "chapter", url = "url")
    private val page = ReaderPage(index = 3, url = "page").apply {
        this.chapter = ReaderChapter(chapterModel)
    }

    private val block = OcrTextBlock(
        xmin = 0.1f,
        ymin = 0.2f,
        xmax = 0.3f,
        ymax = 0.4f,
        lines = listOf("こんにちは"),
        vertical = true,
        language = "ja",
    )

    private fun loader(
        loadTimeoutMs: Long,
        enabled: Boolean = true,
        ocrManager: OcrManager = mockk(relaxed = true) {
            every { queueState } returns MutableStateFlow(emptyList<OcrQueueItem>())
        },
        dictionaryOcr: DictionaryOcrPreferences = DictionaryOcrPreferences(InMemoryPreferenceStore()).apply {
            choose(enabled)
        },
    ): ReaderOcrBlockLoader = ReaderOcrBlockLoader(
        ocrManager = ocrManager,
        engine = mockk<OcrEngine>(relaxed = true),
        getManga = getManga,
        downloadProvider = mockk<DownloadProvider>(relaxed = true),
        sourceManager = mockk<SourceManager>(relaxed = true),
        dictionaryPreferences = mockk<DictionaryPreferences>(relaxed = true),
        scope = scope,
        // KUN -->
        dictionaryOcrPreferences = dictionaryOcr,
        // KUN <--
        loadTimeoutMs = loadTimeoutMs,
    )

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a hung load is watchdogged into the retry path instead of being cached`(): Unit = runBlocking {
        coEvery { getManga.await(any()) } coAnswers {
            awaitCalls.incrementAndGet()
            awaitCancellation() // a suspension that never completes: the watchdog must fire
        }
        val loader = loader(loadTimeoutMs = WATCHDOG_MS)

        loader.ensureLoaded(listOf(page))
        delay(WATCHDOG_MS + 400) // the 100 ms watchdog has fired; attempt 1 timed out
        awaitCalls.get() shouldBe 1
        // A timed-out load is treated like "not resolvable yet": nothing is cached (not even empty).
        loader.blocks.value.keys shouldBe emptySet<OcrPageKey>()

        delay(RETRY_BACKOFF_MS + 300) // past the retry backoff: the page must be retried, not stuck
        loader.ensureLoaded(listOf(page))
        delay(WATCHDOG_MS + 400)
        awaitCalls.get() shouldBe 2
        loader.blocks.value.keys shouldBe emptySet<OcrPageKey>()
    }

    @Test
    fun `a disabled opt-in never enters the load path`(): Unit = runBlocking {
        coEvery { getManga.await(any()) } coAnswers {
            awaitCalls.incrementAndGet()
            null
        }
        // The master switch is off: neither a page drive nor a forced re-run may touch the source
        // policy (mokuro sidecar, persisted cache or engine).
        val loader = loader(loadTimeoutMs = WATCHDOG_MS, enabled = false)

        loader.ensureLoaded(listOf(page))
        loader.redoOcr(page)
        delay(WATCHDOG_MS + 200)

        awaitCalls.get() shouldBe 0
        loader.blocks.value.keys shouldBe emptySet<OcrPageKey>()
    }

    @Test
    fun `a cancelled load propagates instead of being logged and retried`(): Unit = runBlocking {
        coEvery { getManga.await(any()) } coAnswers {
            awaitCalls.incrementAndGet()
            throw CancellationException("reader scope cancelled")
        }
        val loader = loader(loadTimeoutMs = WATCHDOG_MS)

        loader.ensureLoaded(listOf(page))
        delay(WATCHDOG_MS + 200)
        // The CancellationException was rethrown (not swallowed): the page's single-flight slot is
        // still held, so a second drive is a no-op instead of scheduling a retry.
        loader.ensureLoaded(listOf(page))
        delay(RETRY_BACKOFF_MS + WATCHDOG_MS + 400)

        awaitCalls.get() shouldBe 1
        loader.blocks.value.keys shouldBe emptySet<OcrPageKey>()
    }

    /**
     * The switch can be turned off **while a page's load is already suspended**. The manager then
     * answers with no blocks at all — an opt-out is inert, not "this page has no text" — and caching
     * that empty list as a resolved entry would hide the page from a later re-enable in the same
     * reader session (an implementation that stores it never calls `recognizeAndCache` again and
     * leaves `blocks` empty here).
     */
    @Test
    fun `an opt-out during a load does not cache the page as resolved-empty`(): Unit = runBlocking {
        val preferences = DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(true) }
        val ocrManager = mockk<OcrManager>(relaxed = true) {
            every { queueState } returns MutableStateFlow(emptyList<OcrQueueItem>())
            every { sourceFor(any()) } returns ReaderOcrSource.PADDLE
            every { cacheKey(any(), any(), any()) } returns OcrCacheKey(sourceId = 1L, mangaId = 7L, chapterId = 42L)
        }
        val recognitions = AtomicInteger(0)
        coEvery { ocrManager.cachedBlocks(any(), any(), any()) } returns null
        // Mirrors `OcrManager.recognizeAndCache`: with the opt-in off it is inert and returns no
        // blocks at all; enabled, it returns the page's blocks.
        coEvery { ocrManager.recognizeAndCache(any(), any(), any(), any(), any()) } coAnswers {
            recognitions.incrementAndGet()
            if (preferences.isEnabled()) listOf(block) else emptyList()
        }
        coEvery { getManga.await(any()) } coAnswers {
            // The user turns the feature off while the *first* page load is already in flight.
            if (awaitCalls.incrementAndGet() == 1) preferences.choose(false)
            Manga.create().copy(id = 7L, source = 1L)
        }
        page.stream = { ByteArrayInputStream(ByteArray(8)) }
        val loader = loader(LOAD_TIMEOUT_MS, ocrManager = ocrManager, dictionaryOcr = preferences)

        loader.ensureLoaded(listOf(page))
        awaitSettled { recognitions.get() == 1 }
        recognitions.get() shouldBe 1
        // Nothing is cached for a page whose load was cut short by the opt-out.
        loader.blocks.value shouldBe emptyMap<OcrPageKey, List<OcrTextBlock>>()

        // Re-enabled in the same reader session: the page must be resolved, not treated as cached.
        preferences.choose(true)
        loader.ensureLoaded(listOf(page))
        awaitSettled { recognitions.get() == 2 }

        recognitions.get() shouldBe 2
        loader.blocks.value shouldBe mapOf(page.ocrKey() to listOf(block))
    }

    /**
     * The reader's page work must not outlive the reader: `detach()` (called when the reader
     * activity goes away) has to cancel a load that is still running, because that load reads the
     * chapter's page bytes out of the archive the reader unmaps on the way out — an in-flight read
     * of a freed mapping is a native `SIGSEGV`, not an exception. Launched on the app-lifetime
     * scope, nothing cancels it and the flag below never flips.
     */
    @Test
    fun `detach cancels the page load that is in flight`(): Unit = runBlocking {
        val cancelled = CountDownLatch(1)
        coEvery { getManga.await(any()) } coAnswers {
            awaitCalls.incrementAndGet()
            try {
                awaitCancellation()
            } finally {
                cancelled.countDown()
            }
        }
        val loader = loader(loadTimeoutMs = LOAD_TIMEOUT_MS)

        loader.ensureLoaded(listOf(page))
        awaitSettled { awaitCalls.get() == 1 }

        loader.detach()

        cancelled.await(2, TimeUnit.SECONDS) shouldBe true
    }

    /**
     * `detach()` must not brick the loader: it is an app-graph singleton and the next reader session
     * reuses it. An implementation that cancels the injected app scope, or that cancels the work but
     * leaves the page's single-flight slot held, silently stops OCR for every later reader.
     */
    @Test
    fun `a page load started after detach still runs`(): Unit = runBlocking {
        val firstCancelled = CountDownLatch(1)
        coEvery { getManga.await(any()) } coAnswers {
            if (awaitCalls.incrementAndGet() == 1) {
                try {
                    awaitCancellation()
                } finally {
                    firstCancelled.countDown()
                }
            } else {
                // Unresolvable: enough to prove the second drive reached the load path at all.
                null
            }
        }
        val loader = loader(loadTimeoutMs = LOAD_TIMEOUT_MS)

        loader.ensureLoaded(listOf(page))
        awaitSettled { awaitCalls.get() == 1 }
        loader.detach()
        awaitSettled { firstCancelled.count == 0L }

        loader.ensureLoaded(listOf(page))
        awaitSettled { awaitCalls.get() == 2 }

        awaitCalls.get() shouldBe 2
    }

    /**
     * A forced re-run supersedes the page's ordinary load: the old load must be cancelled rather
     * than left running beside it (two reads of the same page, and the loser can overwrite the
     * winner's blocks). This is the "a new load cancels the previous load" invariant.
     */
    @Test
    fun `a forced redo cancels the load already in flight for the page`(): Unit = runBlocking {
        val initialLoadCancelled = CountDownLatch(1)
        val forced = AtomicInteger(0)
        val ocrManager = mockk<OcrManager>(relaxed = true) {
            every { queueState } returns MutableStateFlow(emptyList<OcrQueueItem>())
            every { sourceFor(any()) } returns ReaderOcrSource.PADDLE
            every { cacheKey(any(), any(), any()) } returns OcrCacheKey(sourceId = 1L, mangaId = 7L, chapterId = 42L)
        }
        coEvery { getManga.await(any()) } coAnswers {
            if (awaitCalls.incrementAndGet() == 1) {
                try {
                    awaitCancellation()
                } finally {
                    initialLoadCancelled.countDown()
                }
            } else {
                Manga.create().copy(id = 7L, source = 1L)
            }
        }
        coEvery { ocrManager.forceRecognizeAndCache(any(), any(), any(), any(), any()) } coAnswers {
            forced.incrementAndGet()
            listOf(block)
        }
        page.stream = { ByteArrayInputStream(ByteArray(8)) }
        val loader = loader(LOAD_TIMEOUT_MS, ocrManager = ocrManager)

        loader.ensureLoaded(listOf(page))
        awaitSettled { awaitCalls.get() == 1 }

        loader.redoOcr(page)

        initialLoadCancelled.await(2, TimeUnit.SECONDS) shouldBe true
        awaitSettled { forced.get() == 1 }
        forced.get() shouldBe 1
        loader.blocks.value shouldBe mapOf(page.ocrKey() to listOf(block))
    }

    /**
     * Waits (in real time; the loader launches its work on `Dispatchers.IO`) until [condition] holds,
     * then lets the bookkeeping that follows it — which does not suspend — run before assertions.
     */
    private suspend fun awaitSettled(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) delay(10)
        condition() shouldBe true
        delay(SETTLE_MS)
    }

    private companion object {
        /** Margin for the loader's post-resolution state write (it has no suspension point left). */
        private const val SETTLE_MS = 250L

        /** Generous watchdog for the opt-out test: it must not time out (a hang is not the defect). */
        private const val LOAD_TIMEOUT_MS = 5_000L
        /** Keeps the real-time test short while staying well inside the production default. */
        private const val WATCHDOG_MS = 100L

        /** Mirrors `ReaderOcrBlockLoader.RETRY_INTERVAL_NANOS` (1 s) plus a margin. */
        private const val RETRY_BACKOFF_MS = 1_000L
    }
}
