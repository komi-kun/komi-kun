// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ownership of a resolved `ChapterOcrInput` (`OcrManager.runPendingQueue`).
//
// The input owns a native resource — the app-side provider maps the chapter's CBZ — and
// `processChapter`'s `finally` is the only thing that releases it. The runner therefore has to hand
// that ownership over explicitly: resolving the chapter suspends, so the run's scope can be cancelled
// *after* `inputFor` returned an input but *before* the job that would consume it exists. `launch` on
// a cancelled scope answers an already-cancelled (never started) child, its body never runs, and the
// `finally` never runs either — the mapping leaks with no log line and no retry.
//
// The cancelled-run test forces exactly that ordering: `inputFor` cancels the run's own job and then
// returns the input, and `stopRequested` ends the loop before its next suspension point. The
// executing-run test is the other half of the contract — closing must happen exactly once, because in
// production `close()` is `Os.munmap` (`ArchiveReader.close`) and a second call throws.
// KUN <--
package mihon.core.ocr.cache

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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

class OcrManagerInputLifetimeTest {

    private val engine = mockk<OcrEngine>(relaxed = true) {
        every { isAvailable() } returns true
    }
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

    /** The provider the app builds: it holds whatever `close()` has to release, and counts calls. */
    private class TrackingProvider : ChapterImageProvider {
        val pageCountReads = AtomicInteger()
        val closeCalls = AtomicInteger()

        override val pageCount: Int
            get() {
                pageCountReads.incrementAndGet()
                return 1
            }

        override fun pageBytes(pageIndex: Int): ByteArray = ByteArray(8)

        override fun close() {
            closeCalls.incrementAndGet()
        }
    }

    private fun chapterInput(provider: ChapterImageProvider): ChapterOcrInput = ChapterOcrInput(
        key = key,
        language = OcrLanguage.JAPANESE,
        source = ReaderOcrSource.PADDLE,
        provider = provider,
    )

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

    private fun manager(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)): OcrManager = OcrManager(
        engine = engine,
        merger = merger,
        cacheManager = cacheManager,
        store = store,
        preferences = OcrPreferences(InMemoryPreferenceStore()),
        dictionaryOcr = DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(true) },
        scope = scope,
    )

    /**
     * The leak window: the run's scope is cancelled while the chapter is being resolved, so the input
     * exists but the job that would consume (and close) it is created already-cancelled.
     *
     * `pageCountReads shouldBe 0` is what makes this test meaningful rather than accidentally green:
     * it proves the job body really never ran, so the close below is the runner's, not
     * `processChapter`'s `finally`.
     */
    @Test
    fun `an input resolved while the run is cancelled is closed`(): Unit = runBlocking {
        val entries = installQueue(
            OcrTask(mangaId = MANGA, chapterId = CHAPTER, order = 0, waitForDownload = false),
        )
        coEvery { cacheManager.load(any(), any(), any()) } returns listOf(block)
        val provider = TrackingProvider()
        val manager = manager()

        var resolved = 0
        // Cancelling the run's own job inside `inputFor` reproduces the race without a timer: the
        // loop cannot observe the cancellation until a suspension point, and there is none between
        // here and the `launch`.
        runCatching {
            withTimeout(5_000) {
                manager.runPendingQueue(
                    inputFor = {
                        resolved++
                        currentCoroutineContext()[Job]?.cancel()
                        chapterInput(provider)
                    },
                    stopRequested = { resolved > 0 },
                )
            }
        }

        resolved shouldBe 1
        provider.pageCountReads.get() shouldBe 0
        provider.closeCalls.get() shouldBe 1
        // The task was never processed, so it is still queued for a later run.
        entries.getValue(CHAPTER).status shouldBe OcrQueueStatus.PENDING
    }

    /**
     * The other half: a run that actually executes closes the input **once**. A completion handler
     * that closed unconditionally would call `close()` a second time after `processChapter`'s
     * `finally` already did, and in production that second call is a second `Os.munmap`.
     */
    @Test
    fun `an input that is processed is closed exactly once`(): Unit = runBlocking {
        val entries = installQueue(
            OcrTask(mangaId = MANGA, chapterId = CHAPTER, order = 0, waitForDownload = false),
        )
        coEvery { cacheManager.load(any(), any(), any()) } returns listOf(block)
        val provider = TrackingProvider()
        val manager = manager()

        withTimeout(5_000) {
            manager.runPendingQueue(inputFor = { chapterInput(provider) })
        }

        provider.closeCalls.get() shouldBe 1
        provider.pageCountReads.get() shouldBe 1
        entries shouldBe emptyMap<Long, OcrTask>()
    }

    private companion object {
        private const val SOURCE = 1L
        private const val MANGA = 2L
        private const val CHAPTER = 3L
    }
}
