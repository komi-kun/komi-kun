// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Opt-in gate tests for the OCR manager. The feature must be inert until the user has explicitly
// enabled it: no queue writes/reads, no cache reads, no engine calls, no recognition. The exception
// pinned below is queue *metadata* bookkeeping (download promotion / download failure), which must
// keep working while off so an already-enqueued chapter is never stranded in WAITING_DOWNLOAD —
// while still starting no OCR work. The enabled cases are the positive control: they fail if a gate
// accidentally blocks the normal path. Turning the switch off **mid-chapter** is pinned too: it must
// abort without caching the (empty) disabled result and without completing or dropping the task.
// KUN <--
package mihon.core.ocr.cache

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.core.ocr.OcrEngine
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.OcrPreferences
import mihon.core.ocr.merge.OcrLineMerger
import mihon.core.ocr.model.OcrTextBlock
import org.junit.jupiter.api.Test
import tachiyomi.core.common.ocr.ReaderOcrSource
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.util.concurrent.atomic.AtomicInteger

class OcrManagerOptInTest {

    // KUN --> A working engine: these tests pin the opt-in gate, and a relaxed mock would report
    // `isAvailable = false`, which now (correctly) stops every run for a missing engine.
    private val engine = mockk<OcrEngine>(relaxed = true) {
        every { isAvailable() } returns true
    }
    // KUN <--
    private val merger = mockk<OcrLineMerger>(relaxed = true)
    private val cacheManager = mockk<OcrCacheManager>(relaxed = true)
    private val store = mockk<OcrStore>(relaxed = true)

    private val block = OcrTextBlock(
        xmin = 0.1f,
        ymin = 0.2f,
        xmax = 0.3f,
        ymax = 0.4f,
        lines = listOf("こんにちは"),
        vertical = true,
        language = "ja",
    )

    private val key = OcrCacheKey(sourceId = SOURCE, mangaId = MANGA, chapterId = CHAPTER)

    /**
     * The OCR manager under test. `enabled = false` mirrors the real post-decline state (the key is
     * stored `false`), not merely "never asked".
     */
    private fun manager(enabled: Boolean) =
        manager(DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(enabled) })

    /** Same, for tests that flip the switch while the manager is already running. */
    private fun manager(preferences: DictionaryOcrPreferences) = OcrManager(
        engine = engine,
        merger = merger,
        cacheManager = cacheManager,
        store = store,
        preferences = OcrPreferences(InMemoryPreferenceStore()),
        dictionaryOcr = preferences,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun `queueing is a no-op while disabled`() {
        val manager = manager(enabled = false)

        manager.queueChapters(mangaId = MANGA, chapterIds = listOf(CHAPTER), waitForDownload = false)

        verify(exactly = 0) { store.enqueue(any()) }
        manager.queueState.value.shouldBeEmpty()
    }

    @Test
    fun `queueing still works while enabled`() {
        every { store.getAll() } returns listOf(
            OcrTask(mangaId = MANGA, chapterId = CHAPTER, order = 0, waitForDownload = false),
        )
        val manager = manager(enabled = true)

        manager.queueChapters(mangaId = MANGA, chapterIds = listOf(CHAPTER), waitForDownload = false)

        verify(exactly = 1) {
            store.enqueue(
                match {
                    it.single().mangaId == MANGA &&
                        it.single().chapterId == CHAPTER &&
                        !it.single().waitForDownload
                },
            )
        }
        manager.queueState.value.single().chapterId shouldBe CHAPTER
    }

    @Test
    fun `download promotion still updates queue metadata while disabled`() {
        every { store.get(CHAPTER) } returns OcrTask(
            mangaId = MANGA,
            chapterId = CHAPTER,
            order = 0,
            waitForDownload = true,
            status = OcrQueueStatus.WAITING_DOWNLOAD,
        )
        val saved = mutableListOf<OcrTask>()
        every { store.save(capture(saved)) } just Runs

        // Not gated: a chapter queued while the feature was on and downloaded while it is off must
        // not stay WAITING_DOWNLOAD forever.
        manager(enabled = false).markChapterReadyForOcr(CHAPTER) shouldBe true

        saved.single().status shouldBe OcrQueueStatus.PENDING
        saved.single().waitForDownload shouldBe false
        // Metadata only: no OCR work was started, no cache read, no new queue entry.
        coVerify(exactly = 0) { engine.recognize(any<ByteArray>(), any()) }
        coVerify(exactly = 0) { cacheManager.load(any(), any(), any()) }
        verify(exactly = 0) { store.enqueue(any()) }
    }

    @Test
    fun `download failure still updates queue metadata while disabled`() {
        every { store.get(CHAPTER) } returns OcrTask(
            mangaId = MANGA,
            chapterId = CHAPTER,
            order = 0,
            waitForDownload = true,
            status = OcrQueueStatus.WAITING_DOWNLOAD,
        )
        val saved = mutableListOf<OcrTask>()
        every { store.save(capture(saved)) } just Runs

        manager(enabled = false).markChapterDownloadFailed(CHAPTER) shouldBe true

        saved.single().status shouldBe OcrQueueStatus.ERROR
        // Still flagged as waiting for the chapter: the task is `ERROR` because the *download*
        // failed, and a later successful download has to be able to promote it.
        saved.single().waitForDownload shouldBe true
        coVerify(exactly = 0) { engine.recognize(any<ByteArray>(), any()) }
        verify(exactly = 0) { store.enqueue(any()) }
    }

    /**
     * The sequence the queue actually goes through when a download is retried: the first attempt
     * fails (`WAITING_DOWNLOAD` → `ERROR`), the next one succeeds. Clearing `waitForDownload` on the
     * failure made the promotion below impossible — `markChapterReadyForOcr` only promoted
     * `WAITING_DOWNLOAD`, so the task stayed `ERROR` forever and the chapter was never OCR'd even
     * though its files were on disk.
     */
    @Test
    fun `a failed download is promoted again by the download that finally succeeds`() {
        var entry = OcrTask(
            mangaId = MANGA,
            chapterId = CHAPTER,
            order = 0,
            waitForDownload = true,
            status = OcrQueueStatus.WAITING_DOWNLOAD,
        )
        every { store.get(any()) } answers { entry }
        every { store.save(any()) } answers { entry = firstArg() }
        val manager = manager(enabled = true)

        manager.markChapterDownloadFailed(CHAPTER) shouldBe true
        entry.status shouldBe OcrQueueStatus.ERROR
        entry.waitForDownload shouldBe true

        // The retry succeeds: the ordinary download path promotes the task.
        manager.markChapterReadyForOcr(CHAPTER) shouldBe true
        entry.status shouldBe OcrQueueStatus.PENDING
        entry.waitForDownload shouldBe false
        entry.currentPage shouldBe 0
        entry.totalPages shouldBe 0
    }

    /**
     * The failure path is not a promotion: an `ERROR` task that is *not* waiting for a download is
     * left alone (its chapter is on disk; re-running it is the queue's own bounded retry, not a
     * download promotion).
     */
    @Test
    fun `a download failure does not touch a task that is not waiting for a download`() {
        every { store.get(CHAPTER) } returns OcrTask(
            mangaId = MANGA,
            chapterId = CHAPTER,
            order = 0,
            waitForDownload = false,
            status = OcrQueueStatus.ERROR,
        )
        val saved = mutableListOf<OcrTask>()
        every { store.save(capture(saved)) } just Runs

        manager(enabled = true).markChapterDownloadFailed(CHAPTER) shouldBe false

        saved.shouldBeEmpty()
    }

    @Test
    fun `a chapter promoted while disabled is never executed by the queue runner`() {
        // A stateful queue entry, so an (ungated) runner would terminate instead of looping forever.
        var entry = OcrTask(
            mangaId = MANGA,
            chapterId = CHAPTER,
            order = 0,
            waitForDownload = true,
            status = OcrQueueStatus.WAITING_DOWNLOAD,
        )
        every { store.get(any()) } answers { entry }
        every { store.save(any()) } answers { entry = firstArg() }
        every { store.getAll() } answers { listOf(entry) }
        every { store.update(any(), any()) } answers {
            val updated = secondArg<(OcrTask) -> OcrTask>().invoke(entry)
            entry = updated
            updated
        }
        val manager = manager(enabled = false)

        manager.markChapterReadyForOcr(CHAPTER) shouldBe true
        entry.status shouldBe OcrQueueStatus.PENDING
        // The promoted (PENDING) task exists, but the runner stays inert, so the chapter waits for
        // the feature to be enabled instead of being processed or failed.
        var inputRequested = false
        runBlocking {
            manager.runPendingQueue(
                inputFor = {
                    inputRequested = true
                    null
                },
            )
        }

        inputRequested shouldBe false
        verify(exactly = 0) { store.update(any(), any()) }
        entry.status shouldBe OcrQueueStatus.PENDING
        coVerify(exactly = 0) { engine.recognize(any<ByteArray>(), any()) }
    }

    @Test
    fun `promoting a downloaded chapter still works while enabled`() {
        every { store.get(CHAPTER) } returns OcrTask(
            mangaId = MANGA,
            chapterId = CHAPTER,
            order = 0,
            waitForDownload = true,
            status = OcrQueueStatus.WAITING_DOWNLOAD,
        )
        val saved = mutableListOf<OcrTask>()
        every { store.save(capture(saved)) } just Runs

        manager(enabled = true).markChapterReadyForOcr(CHAPTER) shouldBe true

        saved.single().status shouldBe OcrQueueStatus.PENDING
        saved.single().waitForDownload shouldBe false
    }

    @Test
    fun `the pending queue is never read while disabled`(): Unit = runBlocking {
        manager(enabled = false).runPendingQueue(inputFor = { null })

        verify(exactly = 0) { store.getAll() }
    }

    @Test
    fun `the pending queue is read while enabled`(): Unit = runBlocking {
        manager(enabled = true).runPendingQueue(inputFor = { null })

        verify(atLeast = 1) { store.getAll() }
    }

    /**
     * The mid-run disable case: the queue starts enabled work and the user turns the feature off
     * while the chapter is being resolved, so the task comes back `PENDING`. An implementation that
     * only checks the switch before the loop keeps re-resolving and re-requeueing it forever — this
     * test then times out (bounded termination) and `inputFor` is called far more than once.
     */
    @Test
    fun `disabling mid-run stops the queue instead of re-running work it now refuses`(): Unit = runBlocking {
        val preferences = DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(true) }
        // A real queue entry the runner mutates, so an unfixed loop has something to keep re-running.
        var entry = OcrTask(mangaId = MANGA, chapterId = CHAPTER, order = 0, waitForDownload = false)
        var writes = 0
        every { store.get(any()) } answers { entry }
        every { store.save(any()) } answers {
            writes++
            entry = firstArg()
        }
        every { store.getAll() } answers { listOf(entry) }
        every { store.update(any(), any()) } answers {
            writes++
            secondArg<(OcrTask) -> OcrTask>().invoke(entry).also { entry = it }
        }
        val resolutions = AtomicInteger()
        val provider = mockk<ChapterImageProvider>(relaxed = true)
        val manager = manager(preferences)

        // Bounded termination: an unfixed loop never returns, so this would time out instead.
        withTimeout(5_000) {
            manager.runPendingQueue(
                inputFor = {
                    resolutions.incrementAndGet()
                    // The user turns the feature off while the chapter is being resolved.
                    preferences.choose(false)
                    ChapterOcrInput(
                        key = key,
                        language = OcrLanguage.JAPANESE,
                        source = ReaderOcrSource.PADDLE,
                        provider = provider,
                    )
                },
            )
        }

        // Resolved once, before the switch; nothing is resolved afterwards (no hot loop).
        resolutions.get() shouldBe 1
        // Not rewritten in a loop: at most the PENDING requeue (+ the cancellation cleanup).
        (writes <= 2) shouldBe true
        // The interrupted task is preserved for a later re-enable — not failed, not dropped.
        entry.status shouldBe OcrQueueStatus.PENDING
        coVerify(exactly = 0) { engine.recognize(any<ByteArray>(), any()) }
    }

    @Test
    fun `cached blocks are not served while disabled`(): Unit = runBlocking {
        coEvery { cacheManager.load(any(), any(), any()) } returns listOf(block)

        manager(enabled = false).cachedBlocks(key, pageIndex = 0, source = ReaderOcrSource.AUTOMATIC) shouldBe null

        coVerify(exactly = 0) { cacheManager.load(any(), any(), any()) }
    }

    @Test
    fun `cached blocks are still served while enabled`(): Unit = runBlocking {
        coEvery { cacheManager.load(any(), any(), any()) } returns listOf(block)

        manager(enabled = true).cachedBlocks(key, pageIndex = 0, source = ReaderOcrSource.AUTOMATIC) shouldBe
            listOf(block)
    }

    @Test
    fun `recognising a page returns nothing and never calls the engine while disabled`(): Unit = runBlocking {
        manager(enabled = false).recognizePage(ByteArray(16), OcrLanguage.JAPANESE).shouldBeEmpty()

        coVerify(exactly = 0) { engine.recognize(any<ByteArray>(), any()) }
    }

    @Test
    fun `force recognition returns nothing and writes no cache while disabled`(): Unit = runBlocking {
        manager(enabled = false).forceRecognizeAndCache(
            key = key,
            pageIndex = 0,
            bytes = ByteArray(16),
            language = OcrLanguage.JAPANESE,
            source = ReaderOcrSource.PADDLE,
        ).shouldBeEmpty()

        coVerify(exactly = 0) { engine.recognize(any<ByteArray>(), any()) }
        coVerify(exactly = 0) { cacheManager.save(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `on-demand recognition returns nothing and writes no cache while disabled`(): Unit = runBlocking {
        manager(enabled = false).recognizeAndCache(
            key = key,
            pageIndex = 0,
            bytes = ByteArray(16),
            language = OcrLanguage.JAPANESE,
            source = ReaderOcrSource.PADDLE,
        ).shouldBeEmpty()

        coVerify(exactly = 0) { engine.recognize(any<ByteArray>(), any()) }
        coVerify(exactly = 0) { cacheManager.save(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `processing a chapter stops without touching the page provider while disabled`(): Unit = runBlocking {
        val provider = mockk<ChapterImageProvider>(relaxed = true)

        val result = manager(enabled = false).processChapter(chapterInput(provider))

        result shouldBe OcrTaskResult.STOPPED
        // The provider is still closed (the manager owns it), but no page was read or recognised.
        verify(exactly = 1) { provider.close() }
        verify(exactly = 0) { provider.pageCount }
        coVerify(exactly = 0) { engine.recognize(any<ByteArray>(), any()) }
        verify(exactly = 0) { store.get(any()) }
    }

    @Test
    fun `processing a chapter still runs while enabled`(): Unit = runBlocking {
        val provider = mockk<ChapterImageProvider>()
        every { provider.pageCount } returns 1
        every { provider.close() } just Runs
        // The queue entry exists and the page is already cached, so the chapter completes without
        // invoking the engine — which is exactly what the enabled path must still do.
        every { store.get(CHAPTER) } returns OcrTask(
            mangaId = MANGA,
            chapterId = CHAPTER,
            order = 0,
            waitForDownload = false,
        )
        coEvery { cacheManager.load(any(), any(), any()) } returns listOf(block)

        val result = manager(enabled = true).processChapter(chapterInput(provider))

        result shouldBe OcrTaskResult.SUCCESS
        coVerify(exactly = 0) { engine.recognize(any<ByteArray>(), any()) }
        verify(exactly = 1) { provider.close() }
    }

    /**
     * Mid-chapter opt-out, page already read: the switch flips while the page bytes are read, so
     * `recognizePage` takes its disabled branch and returns an **empty** block list. Persisting that
     * is the cache poisoning this pins: the page must not be saved, the next page must not be read,
     * and the chapter must stay `PENDING` in the store (runnable by a later enabled run) instead of
     * being marked `COMPLETED` or dropped by the runner.
     */
    @Test
    fun `disabling during a page saves no result and leaves the task pending`(): Unit = runBlocking {
        val preferences = DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(true) }
        var entry = OcrTask(mangaId = MANGA, chapterId = CHAPTER, order = 0, waitForDownload = false)
        val statuses = mutableListOf<OcrQueueStatus>()
        every { store.get(any()) } answers { entry }
        every { store.update(any(), any()) } answers {
            secondArg<(OcrTask) -> OcrTask>().invoke(entry).also {
                entry = it
                statuses += it.status
            }
        }
        every { store.save(any()) } answers {
            entry = firstArg()
            statuses += entry.status
        }
        val provider = mockk<ChapterImageProvider>()
        every { provider.pageCount } returns 2
        every { provider.close() } just Runs
        // Explicit cache misses: a relaxed `load` would answer an empty list (i.e. "cached") and the
        // page would never be read at all.
        coEvery { cacheManager.load(any(), any(), any()) } returns null
        every { provider.pageBytes(0) } answers {
            // The user turns the switch off while this page is being read.
            preferences.choose(false)
            ByteArray(8)
        }

        manager(preferences).processChapter(chapterInput(provider)) shouldBe OcrTaskResult.STOPPED

        // No empty `OcrPageData`: the disabled recognition result is never cached.
        coVerify(exactly = 0) { cacheManager.save(any(), any(), any(), any(), any()) }
        // Nothing is recognised for a feature that is off...
        coVerify(exactly = 0) { engine.recognize(any<ByteArray>(), any()) }
        // ...the page that flipped the switch was read, the next one was not, and the chapter is
        // neither completed nor dropped.
        verify(exactly = 1) { provider.pageBytes(0) }
        verify(exactly = 0) { provider.pageBytes(1) }
        verify(exactly = 0) { store.remove(any()) }
        statuses shouldNotContain OcrQueueStatus.COMPLETED
        statuses shouldNotContain OcrQueueStatus.ERROR
        entry.status shouldBe OcrQueueStatus.PENDING
    }

    /**
     * Mid-chapter opt-out inside the **retry pass**: page 0 cannot be read in the main loop, so the
     * chapter enters `retryFailedPages`; the switch is turned off while the retry pass reads the
     * page. The retry must abort instead of recognising (a disabled `recognizePage` answers with an
     * empty list) and persisting that empty `OcrPageData`, and the task must stay `PENDING` rather
     * than being completed or dropped.
     */
    @Test
    fun `disabling during the retry pass saves no result and leaves the task pending`(): Unit = runBlocking {
        val preferences = DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(true) }
        var entry = OcrTask(mangaId = MANGA, chapterId = CHAPTER, order = 0, waitForDownload = false)
        val statuses = mutableListOf<OcrQueueStatus>()
        every { store.get(any()) } answers { entry }
        every { store.update(any(), any()) } answers {
            secondArg<(OcrTask) -> OcrTask>().invoke(entry).also {
                entry = it
                statuses += it.status
            }
        }
        every { store.save(any()) } answers {
            entry = firstArg()
            statuses += entry.status
        }
        val provider = mockk<ChapterImageProvider>()
        every { provider.pageCount } returns 1
        every { provider.close() } just Runs
        // Explicit cache miss: a relaxed `load` would answer an empty list (i.e. "cached") and the
        // page would never be read.
        coEvery { cacheManager.load(any(), any(), any()) } returns null
        var reads = 0
        every { provider.pageBytes(0) } answers {
            reads++
            if (reads == 1) {
                // The main loop cannot read the page: the chapter falls through to the retry pass.
                null
            } else {
                // ...and the user turns the switch off while the retry pass reads it.
                preferences.choose(false)
                ByteArray(8)
            }
        }

        manager(preferences).processChapter(chapterInput(provider)) shouldBe OcrTaskResult.STOPPED

        // Page 0 was read by the main loop and once more by the retry pass.
        verify(exactly = 2) { provider.pageBytes(0) }
        // The retry pass' (disabled, empty) result is never persisted.
        coVerify(exactly = 0) { cacheManager.save(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { engine.recognize(any<ByteArray>(), any()) }
        // The task is neither completed nor dropped: it waits for a later re-enable.
        verify(exactly = 0) { store.remove(any()) }
        statuses shouldNotContain OcrQueueStatus.COMPLETED
        statuses shouldNotContain OcrQueueStatus.ERROR
        entry.status shouldBe OcrQueueStatus.PENDING
    }

    /**
     * Mid-chapter opt-out, page not yet read: page 0 is already cached, and the switch flips while
     * that cache entry is looked up. The pre-page check must abort before page 1's bytes are read,
     * and again leave the task `PENDING` rather than saving anything.
     */
    @Test
    fun `disabling between pages stops before the next page is read`(): Unit = runBlocking {
        val preferences = DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(true) }
        var entry = OcrTask(mangaId = MANGA, chapterId = CHAPTER, order = 0, waitForDownload = false)
        every { store.get(any()) } answers { entry }
        every { store.update(any(), any()) } answers {
            secondArg<(OcrTask) -> OcrTask>().invoke(entry).also { entry = it }
        }
        var loads = 0
        coEvery { cacheManager.load(any(), any(), any()) } answers {
            loads++
            if (loads == 1) {
                // Page 0 is already cached; the switch is turned off right as it is served.
                preferences.choose(false)
                listOf(block)
            } else {
                null
            }
        }
        val provider = mockk<ChapterImageProvider>()
        every { provider.pageCount } returns 2
        every { provider.close() } just Runs
        every { provider.pageBytes(any()) } returns ByteArray(8)

        manager(preferences).processChapter(chapterInput(provider)) shouldBe OcrTaskResult.STOPPED

        verify(exactly = 0) { provider.pageBytes(any()) }
        coVerify(exactly = 0) { engine.recognize(any<ByteArray>(), any()) }
        coVerify(exactly = 0) { cacheManager.save(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { store.remove(any()) }
        entry.status shouldBe OcrQueueStatus.PENDING
    }

    private fun chapterInput(provider: ChapterImageProvider) = ChapterOcrInput(
        key = key,
        language = OcrLanguage.JAPANESE,
        source = ReaderOcrSource.PADDLE,
        provider = provider,
    )

    private companion object {
        private const val SOURCE = 1L
        private const val MANGA = 2L
        private const val CHAPTER = 3L
    }
}
