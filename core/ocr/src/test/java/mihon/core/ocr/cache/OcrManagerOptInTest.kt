// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Opt-in gate tests for the OCR manager. The feature must be inert until the user has explicitly
// enabled it: no queue writes/reads, no cache reads, no engine calls, no recognition. The exception
// pinned below is queue *metadata* bookkeeping (download promotion / download failure), which must
// keep working while off so an already-enqueued chapter is never stranded in WAITING_DOWNLOAD —
// while still starting no OCR work. The enabled cases are the positive control: they fail if a gate
// accidentally blocks the normal path.
// KUN <--
package mihon.core.ocr.cache

import io.kotest.matchers.collections.shouldBeEmpty
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

    private val engine = mockk<OcrEngine>(relaxed = true)
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
        saved.single().waitForDownload shouldBe false
        coVerify(exactly = 0) { engine.recognize(any<ByteArray>(), any()) }
        verify(exactly = 0) { store.enqueue(any()) }
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
    fun `retrying errors is a no-op while disabled`() {
        manager(enabled = false).retryAllErrors()

        verify(exactly = 0) { store.getAll() }
        verify(exactly = 0) { store.save(any()) }
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
