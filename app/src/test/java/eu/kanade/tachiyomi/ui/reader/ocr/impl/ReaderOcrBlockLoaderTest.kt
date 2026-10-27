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
import org.junit.jupiter.api.fail
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

    /**
     * Every loader this test built, so [tearDown] can stop the page-work scope each one owns. Each
     * loader allocates its own `SupervisorJob` on `Dispatchers.IO` (`newWorkScope`), separate from
     * the injected [scope], so cancelling [scope] alone leaks a live job tree per test.
     */
    private val loaders = mutableListOf<ReaderOcrBlockLoader>()

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
    ).also { loaders += it }

    @AfterEach
    fun tearDown() {
        // `detach()` is what cancels a loader's own page-work scope; without it every loader built
        // here keeps a `SupervisorJob` (and whatever it launched) alive past its test.
        loaders.forEach { it.detach() }
        loaders.clear()
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
     *
     * It must also hold **for the whole redo**, not just at its start: the reader re-drives
     * `ensureLoaded` every frame with the visible pages and their neighbours, so a redo that
     * released the page's single-flight slot while it worked was read and inferred a second time by
     * a normal load a frame later. The redo is parked in `forceRecognizeAndCache` below while
     * `ensureLoaded` is re-driven, which the released-slot implementation answers with a second
     * `GetManga` call (and a `recognizeAndCache`).
     */
    @Test
    fun `a forced redo cancels the load already in flight for the page`(): Unit = runBlocking {
        val initialLoadCancelled = CountDownLatch(1)
        val redoStarted = CountDownLatch(1)
        val releaseRedo = CountDownLatch(1)
        val forced = AtomicInteger(0)
        val ordinaryRuns = AtomicInteger(0)
        val forcedBlock = block.copy(lines = listOf("強制再実行"))
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
        coEvery { ocrManager.cachedBlocks(any(), any(), any()) } returns null
        coEvery { ocrManager.recognizeAndCache(any(), any(), any(), any(), any()) } coAnswers {
            ordinaryRuns.incrementAndGet()
            listOf(block)
        }
        coEvery { ocrManager.forceRecognizeAndCache(any(), any(), any(), any(), any()) } coAnswers {
            forced.incrementAndGet()
            redoStarted.countDown()
            releaseRedo.await(5, TimeUnit.SECONDS)
            listOf(forcedBlock)
        }
        page.stream = { ByteArrayInputStream(ByteArray(8)) }
        val loader = loader(LOAD_TIMEOUT_MS, ocrManager = ocrManager)

        loader.ensureLoaded(listOf(page))
        awaitSettled { awaitCalls.get() == 1 }

        loader.redoOcr(page)
        redoStarted.await(2, TimeUnit.SECONDS) shouldBe true
        initialLoadCancelled.await(2, TimeUnit.SECONDS) shouldBe true

        // The per-frame driver, re-driven while the redo is still working. A wrongly-started load is
        // caught the moment it reaches `GetManga`; a fixed `repeat(3) { delay(50) }` only gave it
        // 150 ms to get there, and a loaded machine can exceed that — the test then passed with the
        // defect present.
        awaitNoViolation(
            drive = { loader.ensureLoaded(listOf(page)) },
            violation = { ordinaryRuns.get() != 0 },
        )
        awaitCalls.get() shouldBe 2 // the ordinary load's (cancelled) one, plus the redo's own
        forced.get() shouldBe 1

        releaseRedo.countDown()
        awaitSettled { loader.blocks.value.isNotEmpty() }
        // The redo's result stands: no ordinary load ran to overwrite it.
        loader.blocks.value shouldBe mapOf(page.ocrKey() to listOf(forcedBlock))
        forced.get() shouldBe 1
        ordinaryRuns.get() shouldBe 0
    }

    /**
     * A page load must not release a claim that a **newer** drive registered for the same page.
     * `invalidate()` — reached from the OCR queue observer whenever a cached chapter advances —
     * drops every claim while a load of that page can still be in flight. With an unconditional
     * `inFlight.remove(key)` its late release deletes the claim the re-drive just took, so the page
     * is read and OCRed twice (and the loser can overwrite `_blocks[key]`).
     *
     * The first load is parked in `GetManga.await`; `invalidate()` drops its claim, the re-drive
     * claims the page and parks there too, and only then is the first load released — so its release
     * runs strictly after the second claim. Its own outcome is "not resolvable yet", which also arms
     * the retry backoff; that is waited out below, so the third drive can only be stopped by the
     * claim itself.
     */
    @Test
    fun `a finishing load does not clear the claim a newer drive registered`(): Unit = runBlocking {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        coEvery { getManga.await(any()) } coAnswers {
            when (awaitCalls.incrementAndGet()) {
                1 -> {
                    firstEntered.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                    null
                }
                2 -> {
                    secondEntered.countDown()
                    awaitCancellation()
                }
                else -> null
            }
        }
        val loader = loader(loadTimeoutMs = LOAD_TIMEOUT_MS)

        // The first load starts and parks in the source lookup.
        loader.ensureLoaded(listOf(page))
        firstEntered.await(2, TimeUnit.SECONDS) shouldBe true

        // The queue observer clears the cache (and the claims); the page is re-driven and claimed.
        loader.invalidate()
        loader.ensureLoaded(listOf(page))
        secondEntered.await(2, TimeUnit.SECONDS) shouldBe true

        // The first load finishes only now, i.e. strictly after the re-drive claimed the page.
        releaseFirst.countDown()
        delay(RETRY_BACKOFF_MS + 300)

        // The re-drive is still suspended in `GetManga`, so every further drive must be a no-op.
        // Driven over a window rather than once, so a duplicate started on a loaded machine is still
        // caught.
        val deadline = System.currentTimeMillis() + NEGATIVE_WINDOW_MS
        while (true) {
            loader.ensureLoaded(listOf(page))
            awaitCalls.get() shouldBe 2
            if (System.currentTimeMillis() >= deadline) break
            delay(10)
        }
    }

    /**
     * The negative twin of [awaitSettled]: re-runs [drive] for [windowMs] and fails the moment
     * [violation] holds, so a wrongly-started load is caught whenever it reaches its first suspension
     * point instead of only when it happens to win a fixed sleep. [windowMs] is a bound on how long
     * the absence is demonstrated, not a delay that must be out-raced.
     */
    private suspend fun awaitNoViolation(
        windowMs: Long = NEGATIVE_WINDOW_MS,
        drive: () -> Unit,
        violation: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + windowMs
        while (true) {
            drive()
            if (violation()) fail("a page load reached GetManga while the redo was still in flight")
            if (System.currentTimeMillis() >= deadline) return
            delay(10)
        }
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

        /**
         * How long a negative expectation is demonstrated for. Long enough that a wrongly-started
         * load on `Dispatchers.IO` reaches `GetManga` on a loaded machine, short enough to keep the
         * suite fast.
         */
        private const val NEGATIVE_WINDOW_MS = 1_000L
    }
}
