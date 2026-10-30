// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Download-time OCR must finish a chapter, and an interrupted run must be resumable.
//
// The defect this pins: `runChapter` treats "the provider answered a page" as "the page has text",
// so a provider that cannot read a page's bytes (a CBZ the page reader could not open, an unreadable
// archive entry) answered an **empty** `ByteArray`, the engine logged the decode failure and returned
// no lines, `recognizePage` answered an empty list — and the run persisted that as page 0's result.
// `countCachedPages` then counted the bogus page, a 48-page chapter looked like a one-page chapter,
// the task was marked `COMPLETED` and removed from the queue, and the reader had to recognise every
// page live as it was turned to. Empty bytes are a page that could not be read, and must fail the
// page instead of being cached as "no text here".
//
// The other two tests are the contract that fix must not break: a run that finishes persists every
// page, and a run resumed over a partially cached chapter recognises only the pages that are missing.
// KUN <--
package mihon.core.ocr.cache

import android.graphics.BitmapFactory
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.ocr.ReaderOcrSource
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class OcrManagerPartialChapterTest {

    private val merger = mockk<OcrLineMerger>(relaxed = true)
    private val cacheManager = mockk<OcrCacheManager>(relaxed = true)
    private val store = mockk<OcrStore>(relaxed = true)

    private val key = OcrCacheKey(sourceId = SOURCE, mangaId = MANGA, chapterId = CHAPTER)

    /** Pages the fake cache holds, i.e. what a real `.json` file would already contain. */
    private val cached = mutableSetOf<Int>()

    /** Pages the run actually persisted, in call order. */
    private val saved = mutableListOf<Int>()

    private val entries = mutableMapOf(
        CHAPTER to OcrTask(mangaId = MANGA, chapterId = CHAPTER, order = 0, waitForDownload = false),
    )

    /** Pages whose bytes the provider can hand out; `null` = reported unreadable (as `pageBytes` may). */
    private var unreadablePages: Set<Int> = emptySet()

    /** Pages whose bytes are there but **empty**, which is what the broken archive reader answered. */
    private var emptyBytePages: Set<Int> = emptySet()

    private val engineCalls = AtomicInteger()

    @BeforeEach
    fun install() {
        every { store.getAll() } answers { entries.values.sortedBy { it.order } }
        every { store.get(any()) } answers { entries[firstArg<Long>()] }
        every { store.save(any()) } answers { entries[firstArg<OcrTask>().chapterId] = firstArg() }
        every { store.remove(any()) } answers { entries.remove(firstArg<Long>()) }
        every { store.update(any(), any()) } answers {
            val chapterId = firstArg<Long>()
            val current = entries[chapterId] ?: return@answers null
            secondArg<(OcrTask) -> OcrTask>().invoke(current).also { entries[chapterId] = it }
        }
        coEvery { cacheManager.save(any(), any(), any(), any(), any()) } answers {
            val page = secondArg<Int>()
            saved += page
            cached += page
        }
        coEvery { cacheManager.load(any(), any(), any()) } answers {
            val page = secondArg<Int>()
            if (page in cached) emptyList() else null
        }
        mockDecodingApi()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * The engine the manager talks to: one call per page whose bytes it is handed. `beforeCall` runs
     * first, so a test can stop the run from inside a page, exactly like a `stop()` from the worker.
     */
    private fun engine(beforeCall: () -> Unit = {}): OcrEngine = object : OcrEngine {
        override suspend fun recognize(bytes: ByteArray, language: OcrLanguage): List<EngineLine> {
            beforeCall()
            engineCalls.incrementAndGet()
            return emptyList()
        }

        override fun isAvailable(): Boolean = true
    }

    private fun chapterInput(pageCount: Int): ChapterOcrInput = ChapterOcrInput(
        key = key,
        language = OcrLanguage.JAPANESE,
        source = ReaderOcrSource.PADDLE,
        provider = object : ChapterImageProvider {
            override val pageCount: Int = pageCount

            override fun pageBytes(pageIndex: Int): ByteArray? = when {
                pageIndex in unreadablePages -> null
                pageIndex in emptyBytePages -> ByteArray(0)
                else -> ByteArray(8)
            }
        },
    )

    private fun manager(engine: OcrEngine): OcrManager = OcrManager(
        engine = engine,
        merger = merger,
        cacheManager = cacheManager,
        store = store,
        preferences = OcrPreferences(InMemoryPreferenceStore()),
        dictionaryOcr = DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(true) },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun `a page whose bytes are empty is not cached and does not complete the chapter`(): Unit = runBlocking {
        // Three pages; page 1 answers an empty array, like the ZipInputStream reader did for every
        // page of every downloaded CBZ.
        emptyBytePages = setOf(1)

        val result = manager(engine()).processChapter(chapterInput(pageCount = 3))

        // The unreadable page is not a result: nothing may be persisted for it.
        saved shouldBe listOf(0, 2)
        // ...so the chapter cannot be "complete", and its task must survive.
        result shouldBe OcrTaskResult.ERROR
        entries.getValue(CHAPTER).status shouldBe OcrQueueStatus.ERROR
        // One engine call per whole page that had bytes: the empty one was never handed over.
        engineCalls.get() shouldBe 2
    }

    @Test
    fun `a page the provider cannot read at all is not cached either`(): Unit = runBlocking {
        // The same defect through the other provider answer: `null` for the page.
        unreadablePages = setOf(1)

        manager(engine()).processChapter(chapterInput(pageCount = 3)) shouldBe OcrTaskResult.ERROR

        saved shouldBe listOf(0, 2)
        entries.getValue(CHAPTER).status shouldBe OcrQueueStatus.ERROR
    }

    @Test
    fun `a completed run persists every page and drops the task`(): Unit = runBlocking {
        withTimeout(5_000) {
            manager(engine()).runPendingQueue(inputFor = { chapterInput(pageCount = 3) })
        }

        // Every page of the chapter was recognised and persisted, once each...
        saved shouldBe listOf(0, 1, 2)
        // ...and only then is the task dropped.
        entries.containsKey(CHAPTER) shouldBe false
    }

    @Test
    fun `a run over a partially cached chapter only does the missing pages`(): Unit = runBlocking {
        // Pages 0 and 2 were cached before the run started: page 1 is the only missing one.
        cached += setOf(0, 2)

        withTimeout(5_000) {
            manager(engine()).runPendingQueue(inputFor = { chapterInput(pageCount = 3) })
        }

        // The cached pages are not recognised again; the missing one is.
        engineCalls.get() shouldBe 1
        saved shouldBe listOf(1)
        entries.containsKey(CHAPTER) shouldBe false
    }

    @Test
    fun `a stopped run leaves the chapter pending and the next run finishes it`(): Unit = runBlocking {
        val stop = AtomicBoolean(false)
        // Stop from inside the first page, so the loop sees it before page 1.
        val stoppingEngine = engine(beforeCall = { stop.set(true) })

        withTimeout(5_000) {
            manager(stoppingEngine).runPendingQueue(
                inputFor = { chapterInput(pageCount = 3) },
                stopRequested = { stop.get() },
            )
        }

        // Interrupted work is neither dropped nor marked done: the chapter is runnable again.
        entries.getValue(CHAPTER).status shouldBe OcrQueueStatus.PENDING
        saved shouldBe listOf(0)

        // The next run continues from what is missing instead of redoing page 0.
        withTimeout(5_000) {
            manager(engine()).runPendingQueue(inputFor = { chapterInput(pageCount = 3) })
        }
        saved shouldBe listOf(0, 1, 2)
        engineCalls.get() shouldBe 3
        entries.containsKey(CHAPTER) shouldBe false
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
