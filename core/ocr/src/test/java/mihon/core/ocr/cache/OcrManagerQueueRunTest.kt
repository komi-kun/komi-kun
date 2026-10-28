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
// only loses by scheduling luck.
// KUN <--
package mihon.core.ocr.cache

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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

class OcrManagerQueueRunTest {

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

    private companion object {
        private const val SOURCE = 1L
        private const val MANGA = 2L
        private const val CHAPTER = 3L
    }
}
