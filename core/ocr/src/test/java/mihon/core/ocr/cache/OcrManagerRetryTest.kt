// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The queue half of the OCR worker's retry: a failed run is retried a bounded number of times
// (`OcrRuntimePolicy.workerFailureDisposition` in the app), and each retry must have work to do.
//
// The defect this pins: `runPendingQueue` only picks up `PENDING`/`PROCESSING`, so retrying a run
// that had marked its chapters `ERROR` would drain an empty queue — the retry would look successful
// and the chapters the first attempt gave up on would stay failed. `requeueFailedTasks` is what
// turns `ERROR` back into runnable work, and *only* the OCR failures: a task whose download failed
// stays `ERROR` because its retry belongs to the download, which promotes it when the files land.
// KUN <--
package mihon.core.ocr.cache

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import mihon.core.ocr.OcrEngine
import mihon.core.ocr.OcrPreferences
import mihon.core.ocr.merge.OcrLineMerger
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class OcrManagerRetryTest {

    private val engine = mockk<OcrEngine>(relaxed = true)
    private val merger = mockk<OcrLineMerger>(relaxed = true)
    private val cacheManager = mockk<OcrCacheManager>(relaxed = true)
    private val store = mockk<OcrStore>(relaxed = true)

    private fun manager(enabled: Boolean) = OcrManager(
        engine = engine,
        merger = merger,
        cacheManager = cacheManager,
        store = store,
        preferences = OcrPreferences(InMemoryPreferenceStore()),
        dictionaryOcr = DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(enabled) },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun `a retry re-arms the OCR failures and leaves the download failures alone`() {
        val ocrFailure = OcrTask(
            mangaId = MANGA,
            chapterId = CHAPTER,
            order = 1,
            waitForDownload = false,
            status = OcrQueueStatus.ERROR,
            currentPage = 3,
            totalPages = 9,
        )
        val downloadFailure = OcrTask(
            mangaId = MANGA,
            chapterId = SECOND_CHAPTER,
            order = 2,
            waitForDownload = true,
            status = OcrQueueStatus.ERROR,
        )
        every { store.getAll() } returns listOf(
            OcrTask(mangaId = MANGA, chapterId = PENDING_CHAPTER, order = 0, waitForDownload = false),
            ocrFailure,
            downloadFailure,
            OcrTask(
                mangaId = MANGA,
                chapterId = COMPLETED_CHAPTER,
                order = 3,
                waitForDownload = false,
                status = OcrQueueStatus.COMPLETED,
            ),
        )
        val saved = mutableListOf<OcrTask>()
        every { store.save(capture(saved)) } just Runs

        manager(enabled = true).requeueFailedTasks() shouldBe 1

        // Only the OCR failure: runnable again, with its progress reset (the retried run re-reads
        // the chapter from the first page, like every other promotion).
        saved.single().chapterId shouldBe CHAPTER
        saved.single().status shouldBe OcrQueueStatus.PENDING
        saved.single().waitForDownload shouldBe false
        saved.single().currentPage shouldBe 0
        saved.single().totalPages shouldBe 0
    }

    @Test
    fun `nothing is re-armed while the feature is off`() {
        // The worker never runs while the feature is off, so this must not be reachable with work to
        // do — and it may not touch the persisted queue even if it is.
        manager(enabled = false).requeueFailedTasks() shouldBe 0

        verify(exactly = 0) { store.getAll() }
        verify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun `an already runnable queue is not rewritten`() {
        every { store.getAll() } returns listOf(
            OcrTask(mangaId = MANGA, chapterId = CHAPTER, order = 0, waitForDownload = false),
        )
        val saved = mutableListOf<OcrTask>()
        every { store.save(capture(saved)) } just Runs

        manager(enabled = true).requeueFailedTasks() shouldBe 0

        saved.shouldBeEmpty()
    }

    private companion object {
        const val MANGA = 7L
        const val CHAPTER = 11L
        const val SECOND_CHAPTER = 12L
        const val PENDING_CHAPTER = 21L
        const val COMPLETED_CHAPTER = 22L
    }
}
