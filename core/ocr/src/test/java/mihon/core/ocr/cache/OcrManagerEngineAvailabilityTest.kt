// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// `OcrManager` must not turn "the engine cannot run" into "this page has no text".
//
// `PaddleOcrEngine.recognize` answers an empty list (it never throws) when the native library or the
// downloaded models are missing or init failed. A chapter run in that state used to persist an empty
// `OcrPageData` per page, count those pages as cached, be marked `COMPLETED` and be dropped from the
// queue: a silent "OCR finished" with no text, nothing in the cache and nothing left to retry. The
// task must stay `PENDING` instead, so it runs again once the models are installed.
//
// The positive control below is the other half of the distinction: a **working** engine that finds no
// text on a blank page must keep today's behaviour (page cached, chapter completed).
// KUN <--
package mihon.core.ocr.cache

import android.graphics.BitmapFactory
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.core.ocr.EngineLine
import mihon.core.ocr.OcrEngine
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.OcrPreferences
import mihon.core.ocr.merge.OcrLineMerger
import org.junit.jupiter.api.Test
import tachiyomi.core.common.ocr.ReaderOcrSource
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class OcrManagerEngineAvailabilityTest {

    private val merger = mockk<OcrLineMerger>(relaxed = true)
    private val cacheManager = mockk<OcrCacheManager>(relaxed = true)
    private val store = mockk<OcrStore>(relaxed = true)

    private val key = OcrCacheKey(sourceId = SOURCE, mangaId = MANGA, chapterId = CHAPTER)

    /** What `PaddleOcrEngine` does without its `.so`/models: no exception, an empty result. */
    private class UnavailableEngine : OcrEngine {
        var calls = 0

        override suspend fun recognize(bytes: ByteArray, language: OcrLanguage): List<EngineLine> {
            calls++
            return emptyList()
        }

        override fun isAvailable(): Boolean = false
    }

    /** A working engine that found no text at all on the page. */
    private class BlankPageEngine : OcrEngine {
        override suspend fun recognize(bytes: ByteArray, language: OcrLanguage): List<EngineLine> = emptyList()

        override fun isAvailable(): Boolean = true
    }

    private fun manager(engine: OcrEngine): OcrManager = OcrManager(
        engine = engine,
        merger = merger,
        cacheManager = cacheManager,
        store = store,
        preferences = OcrPreferences(InMemoryPreferenceStore()),
        dictionaryOcr = DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(true) },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    /** A one-page chapter whose page is **not** cached, so the run reaches the engine. */
    private fun chapterInput(): ChapterOcrInput = ChapterOcrInput(
        key = key,
        language = OcrLanguage.JAPANESE,
        source = ReaderOcrSource.PADDLE,
        provider = object : ChapterImageProvider {
            override val pageCount: Int = 1

            override fun pageBytes(pageIndex: Int): ByteArray = ByteArray(8)
        },
    )

    /**
     * A stateful queue entry installed on the mocked store (the real store needs an Android
     * `Context`), with the page cache missing so page 0 must be recognised.
     */
    private fun installQueue(): MutableMap<Long, OcrTask> {
        val entries = mutableMapOf(
            CHAPTER to OcrTask(mangaId = MANGA, chapterId = CHAPTER, order = 0, waitForDownload = false),
        )
        every { store.getAll() } answers { entries.values.sortedBy { it.order } }
        every { store.get(any()) } answers { entries[firstArg<Long>()] }
        every { store.save(any()) } answers { entries[firstArg<OcrTask>().chapterId] = firstArg() }
        every { store.remove(any()) } answers { entries.remove(firstArg<Long>()) }
        every { store.update(any(), any()) } answers {
            val chapterId = firstArg<Long>()
            val current = entries[chapterId] ?: return@answers null
            secondArg<(OcrTask) -> OcrTask>().invoke(current).also { entries[chapterId] = it }
        }
        coEvery { cacheManager.load(any(), any(), any()) } returns null
        return entries
    }

    @Test
    fun `a chapter run with no engine saves nothing and stays pending`(): Unit = runBlocking {
        val entries = installQueue()
        val engine = UnavailableEngine()

        val result = manager(engine).processChapter(chapterInput())

        // Not SUCCESS: nothing was recognised, so the runner must not drop the task as done.
        result shouldBe OcrTaskResult.STOPPED
        // No empty `OcrPageData` per page: that is what made the chapter look OCR-ed with no text.
        coVerify(exactly = 0) { cacheManager.save(any(), any(), any(), any(), any()) }
        // The engine was not asked at all — an empty result from it is not a page's text.
        engine.calls shouldBe 0
        // Neither completed nor dropped: the task waits for the models to be installed.
        entries.getValue(CHAPTER).status shouldBe OcrQueueStatus.PENDING
    }

    @Test
    fun `a queue run with no engine leaves the chapter pending instead of re-running it`(): Unit = runBlocking {
        val entries = installQueue()
        val engine = UnavailableEngine()

        val manager = manager(engine)
        // Bounded: without the run stopping itself, the aborted PENDING task is picked straight back
        // up and this loop never returns.
        withTimeout(5_000) {
            manager.runPendingQueue(inputFor = { chapterInput() })
        }
        engine.calls shouldBe 0
        coVerify(exactly = 0) { cacheManager.save(any(), any(), any(), any(), any()) }
        entries.getValue(CHAPTER).status shouldBe OcrQueueStatus.PENDING
        manager.isRunning shouldBe false
    }

    @Test
    fun `a blank page from a working engine is still cached and the chapter completed`(): Unit = runBlocking {
        val entries = installQueue()
        // The page becomes readable once it has been saved, exactly like the real cache file.
        val saved = mutableSetOf<Int>()
        coEvery {
            cacheManager.save(any(), any(), any(), any(), any())
        } answers { saved += secondArg<Int>() }
        coEvery { cacheManager.load(any(), any(), any()) } answers {
            if (secondArg<Int>() in saved) emptyList() else null
        }
        mockDecodingApi()

        try {
            manager(BlankPageEngine()).processChapter(chapterInput()) shouldBe OcrTaskResult.SUCCESS
        } finally {
            unmockkAll()
        }

        // "This page has no text" is a result: it is persisted (as an empty page) and counted.
        saved shouldBe setOf(0)
        entries.getValue(CHAPTER).status shouldBe OcrQueueStatus.COMPLETED
    }

    /**
     * The Android decode surface `recognizePage` uses, answered without native code: a 1000x1000 page,
     * below `TILE_TRIGGER_DIMENSION`, so the whole page goes to the engine in one call.
     */
    private fun mockDecodingApi() {
        mockkStatic(BitmapFactory::class)
        // `BitmapFactory.Options()` is an android.jar stub, so its constructor must be intercepted
        // before its bounds fields can be written.
        mockkConstructor(BitmapFactory.Options::class)
        every {
            BitmapFactory.decodeByteArray(any<ByteArray>(), any<Int>(), any<Int>(), any())
        } answers {
            val options = arg<BitmapFactory.Options>(3)
            options.outWidth = 1000
            options.outHeight = 1000
            null
        }
    }

    private companion object {
        private const val SOURCE = 1L
        private const val MANGA = 2L
        private const val CHAPTER = 3L
    }
}
