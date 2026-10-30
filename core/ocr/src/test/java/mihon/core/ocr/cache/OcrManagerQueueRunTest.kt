// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Queue-runner bookkeeping tests (`OcrManager.runPendingQueue`).
//
// The runner launches one coroutine per task and keeps it in `runningJobs` until it finishes; the
// poll loop only exits when that map is empty. A task's job must therefore be registered *before* it
// can run: a job that finishes before its own registration leaves a finished `Job` behind, and then
// the chapter is skipped forever, the loop re-reads the store every poll interval for the life of the
// process, and `isRunning` never goes false (so `OcrWorker` never sees the run finish and its
// foreground notification never goes away).
//
// The ordering is forced here, not raced for: `Dispatchers.IO` is replaced with `Dispatchers.Unconfined`
// for the duration of the test, so the launched body runs inline inside `launch(...)` — exactly the
// "the worker thread got there before the calling thread registered it" ordering that an eager launch
// only loses by scheduling luck. (The late-cleanup test below is the exception: it needs two *real*
// overlapping runs, so it keeps `Dispatchers.IO` and gates the two jobs on latches instead.)
// KUN <--
package mihon.core.ocr.cache

import android.graphics.BitmapFactory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.core.ocr.EngineLine
import mihon.core.ocr.OcrEngine
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.OcrPreferences
import mihon.core.ocr.merge.OcrLineMerger
import mihon.core.ocr.model.OcrTextBlock
import org.junit.jupiter.api.Test
import tachiyomi.core.common.ocr.ReaderOcrSource
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class OcrManagerQueueRunTest {

    // KUN --> A working engine: these tests are about the runner's own bookkeeping, and a relaxed
    // mock would report `isAvailable = false`, which correctly stops the run for a missing engine.
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
     * One page, already cached, so a run over [CHAPTER] completes without reading a page or calling
     * the engine: the task is simply removed again, which is the fastest possible job body.
     */
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
     * Stateful queue state installed on the mocked [OcrStore]; the real store needs an Android
     * `Context`, so it cannot run in a JVM test. Every call is non-suspending, which is what lets
     * the job body run to completion inline (see the class comment).
     */
    private fun installQueue(vararg tasks: OcrTask): MutableMap<Long, OcrTask> {
        val entries = tasks.associateBy { it.chapterId }.toMutableMap()
        every { store.getAll() } answers { entries.values.sortedBy { it.order } }
        every { store.get(any()) } answers { entries[firstArg<Long>()] }
        every { store.save(any()) } answers { entries[firstArg<OcrTask>().chapterId] = firstArg() }
        every { store.remove(any()) } answers { entries.remove(firstArg<Long>()) }
        every { store.update(any(), any()) } answers {
            val chapterId = firstArg<Long>()
            val current = entries[chapterId] ?: return@answers null
            secondArg<(OcrTask) -> OcrTask>().invoke(current).also { entries[chapterId] = it }
        }
        return entries
    }

    @Test
    fun `a task that finishes immediately is released and the run terminates`() {
        val entries = installQueue(
            OcrTask(mangaId = MANGA, chapterId = CHAPTER, order = 0, waitForDownload = false),
        )
        coEvery { cacheManager.load(any(), any(), any()) } returns listOf(block)
        // Captured before `Dispatchers` is mocked: every getter on the object is intercepted below.
        val inline = Dispatchers.Unconfined
        val manager = OcrManager(
            engine = engine,
            merger = merger,
            cacheManager = cacheManager,
            store = store,
            preferences = OcrPreferences(InMemoryPreferenceStore()),
            dictionaryOcr = DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(true) },
            scope = CoroutineScope(inline),
        )

        // `Dispatchers`' members are compiled to *static* getters, so the static mock is the one that
        // intercepts `Dispatchers.IO`.
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns inline
        try {
            runBlocking {
                // A bounded run: with the finished job left in `runningJobs` the poll loop never
                // exits (`runningJobs.isEmpty()` is never true) and this times out instead.
                withTimeout(2_000) {
                    manager.runPendingQueue(inputFor = { chapterInput() })
                }
            }
        } finally {
            unmockkStatic(Dispatchers::class)
        }

        // The task completed, so the runner dropped it...
        entries shouldBe emptyMap<Long, OcrTask>()
        // ...and nothing is left "running" for it: both the public queue state and `isRunning`
        // (which is what `OcrWorker` waits on) are empty/false after the run.
        manager.queueState.value.shouldBeEmpty()
        manager.isRunning shouldBe false
    }

    @Test
    fun `a chapter that cannot be resolved aborts only that task, not the run`() {
        val entries = installQueue(
            OcrTask(mangaId = MANGA, chapterId = CHAPTER, order = 0, waitForDownload = false),
        )
        coEvery { cacheManager.load(any(), any(), any()) } returns listOf(block)
        val manager = OcrManager(
            engine = engine,
            merger = merger,
            cacheManager = cacheManager,
            store = store,
            preferences = OcrPreferences(InMemoryPreferenceStore()),
            dictionaryOcr = DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(true) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        // `inputFor` is the app-side chapter/archive lookup, and it can throw (a broken download, an
        // unreadable archive). The run must survive it: the task ends up `ERROR` and the runner
        // returns, rather than the exception escaping `runPendingQueue` — which left the task
        // `PENDING` while `OcrWorker` reported success, so nothing retried until the next launch.
        runBlocking {
            withTimeout(2_000) {
                manager.runPendingQueue(inputFor = { throw IllegalStateException("archive is unreadable") })
            }
        }

        entries.getValue(CHAPTER).status shouldBe OcrQueueStatus.ERROR
    }

    /**
     * The compare-and-remove half of the registration bookkeeping.
     *
     * A run ends by cancelling what is still in flight and **clearing** `runningJobs`, and it can only
     * return once those jobs have finished — so a cancelled job's `finally` lands *after* the clear,
     * and after a newer run has had time to register the same chapter again. Removing unconditionally
     * would drop that newer registration: the newer run would see the chapter as free and launch a
     * **second job** for it — two concurrent runs of one chapter, double OCR, and a runner whose
     * `isRunning` no longer matches the jobs it started.
     *
     * The ordering is forced rather than raced for: the cancelled job is parked in `store.get` (its
     * cancellation path, before the `finally`) until the second run has registered its job, and the
     * second job is parked in the engine so it is still `PROCESSING` when the late `finally` lands.
     */
    @Test
    fun `a cancelled job's late cleanup keeps the newer run's registration`(): Unit = runBlocking {
        val entries = ConcurrentHashMap<Long, OcrTask>()
        entries[CHAPTER] = OcrTask(mangaId = MANGA, chapterId = CHAPTER, order = 0, waitForDownload = false)
        every { store.getAll() } answers { entries.values.sortedBy { it.order } }
        every { store.save(any()) } answers { entries[firstArg<OcrTask>().chapterId] = firstArg() }
        every { store.remove(any()) } answers { entries.remove(firstArg<Long>()) }
        every { store.update(any(), any()) } answers {
            val chapterId = firstArg<Long>()
            val current = entries[chapterId] ?: return@answers null
            secondArg<(OcrTask) -> OcrTask>().invoke(current).also { entries[chapterId] = it }
        }
        val lateCleanupArmed = AtomicBoolean(false)
        val gateTaken = AtomicBoolean(false)
        val releaseLateCleanup = CountDownLatch(1)
        val lateCleanupEntered = CompletableDeferred<Unit>()
        every { store.get(any()) } answers {
            if (lateCleanupArmed.get() && gateTaken.compareAndSet(false, true)) {
                // The cancelled job is on its way to the `finally`: hold it there.
                lateCleanupEntered.complete(Unit)
                releaseLateCleanup.await()
            }
            entries[firstArg<Long>()]
        }

        val calls = AtomicInteger()
        val firstEntered = CompletableDeferred<Unit>()
        val firstCall = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val secondCall = CompletableDeferred<Unit>()
        val engine = object : OcrEngine {
            override suspend fun recognize(
                bytes: ByteArray,
                language: OcrLanguage,
            ): List<EngineLine> = when (calls.incrementAndGet()) {
                1 -> {
                    firstEntered.complete(Unit)
                    firstCall.await() // never released: this job is cancelled instead
                    emptyList()
                }
                2 -> {
                    secondEntered.complete(Unit)
                    secondCall.await()
                    emptyList()
                }
                else -> emptyList()
            }

            override fun isAvailable(): Boolean = true
        }

        coEvery { cacheManager.load(any(), any(), any()) } returns null
        val manager = OcrManager(
            engine = engine,
            merger = merger,
            cacheManager = cacheManager,
            store = store,
            preferences = OcrPreferences(InMemoryPreferenceStore()),
            dictionaryOcr = DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(true) },
            scope = CoroutineScope(Dispatchers.IO),
        )
        mockDecodingApi()

        val stopFirstRun = AtomicBoolean(false)
        var firstRun: Job? = null
        var secondRun: Job? = null
        try {
            // Run #1 picks the chapter; its job is registered and parked in the engine.
            firstRun = launch(Dispatchers.IO) {
                manager.runPendingQueue(inputFor = { chapterInput() }, stopRequested = { stopFirstRun.get() })
            }
            withTimeout(5_000) { firstEntered.await() }

            // End run #1: it cancels the job, clears the map and then waits for that job — which is
            // held in its cancellation path, so it cannot reach its own `finally` yet.
            lateCleanupArmed.set(true)
            stopFirstRun.set(true)
            withTimeout(5_000) { lateCleanupEntered.await() }

            // A second run registers its own job for the same chapter: run #1 cleared `runningJobs`, so
            // the chapter is free again (run #2 polls until it is, if the clear has not landed yet).
            secondRun = launch(Dispatchers.IO) { manager.runPendingQueue(inputFor = { chapterInput() }) }
            withTimeout(5_000) { secondEntered.await() }

            // Let the late `finally` land while job 2 is registered, then give run #2 more than one
            // poll interval to (wrongly) re-pick the chapter.
            releaseLateCleanup.countDown()
            delay(1_000)

            secondCall.complete(Unit)
            withTimeout(5_000) { secondRun.join() }
            withTimeout(5_000) { firstRun.join() }

            // Two engine calls: the cancelled job's and the second run's. A dropped registration let
            // run #2 launch a third job for the same chapter.
            calls.get() shouldBe 2
            entries[CHAPTER]?.status shouldBe OcrQueueStatus.ERROR
            manager.isRunning shouldBe false
        } finally {
            releaseLateCleanup.countDown()
            firstCall.complete(Unit)
            secondCall.complete(Unit)
            firstRun?.cancel()
            secondRun?.cancel()
            unmockkAll()
        }
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
