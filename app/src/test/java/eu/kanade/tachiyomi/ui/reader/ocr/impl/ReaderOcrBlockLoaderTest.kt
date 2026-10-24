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
import mihon.core.ocr.cache.OcrManager
import mihon.core.ocr.cache.OcrQueueItem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.atomic.AtomicInteger

class ReaderOcrBlockLoaderTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val getManga = mockk<GetManga>()
    private val awaitCalls = AtomicInteger(0)

    private val chapterModel = Chapter.create().copy(id = 42L, mangaId = 7L, name = "chapter", url = "url")
    private val page = ReaderPage(index = 3, url = "page").apply {
        this.chapter = ReaderChapter(chapterModel)
    }

    private fun loader(loadTimeoutMs: Long, enabled: Boolean = true): ReaderOcrBlockLoader = ReaderOcrBlockLoader(
        ocrManager = mockk(relaxed = true) {
            every { queueState } returns MutableStateFlow(emptyList<OcrQueueItem>())
        },
        engine = mockk<OcrEngine>(relaxed = true),
        getManga = getManga,
        downloadProvider = mockk<DownloadProvider>(relaxed = true),
        sourceManager = mockk<SourceManager>(relaxed = true),
        dictionaryPreferences = mockk<DictionaryPreferences>(relaxed = true),
        scope = scope,
        // KUN -->
        dictionaryOcrPreferences = DictionaryOcrPreferences(InMemoryPreferenceStore()).apply {
            choose(enabled)
        },
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

    private companion object {
        /** Keeps the real-time test short while staying well inside the production default. */
        private const val WATCHDOG_MS = 100L

        /** Mirrors `ReaderOcrBlockLoader.RETRY_INTERVAL_NANOS` (1 s) plus a margin. */
        private const val RETRY_BACKOFF_MS = 1_000L
    }
}
