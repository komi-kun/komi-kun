package eu.kanade.domain.manga.interactor

import android.util.Log
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

class RefreshMangaCoverTest {

    private val mangaId = 1L
    private val sourceId = 2L
    private val staleUrl = "https://old.example/cover.jpg"
    private val newUrl = "https://new.example/cover.jpg"

    private val manga = Manga.create().copy(
        id = mangaId,
        source = sourceId,
        url = "/manga/1",
        favorite = true,
        ogThumbnailUrl = staleUrl,
    )

    private val repository = mockk<MangaRepository>()
    private val sourceManager = mockk<SourceManager>()
    private val coverCache = mockk<CoverCache>(relaxed = true)
    private val source = mockk<HttpSource>()

    private val refreshMangaCover = RefreshMangaCover(repository, sourceManager, coverCache)

    @BeforeEach
    fun setUpLogging() {
        // XLog is not initialized in JVM tests; its fallback uses android.util.Log.
        mockkStatic(Log::class)
        every { Log.println(any(), any(), any()) } returns 0
        every { Log.getStackTraceString(any()) } returns ""
    }

    @AfterEach
    fun tearDownLogging() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `finds a changed source cover without persisting it`() = runTest {
        stubManga()
        stubSource(newUrl)

        refreshMangaCover.findReplacement(mangaId, staleUrl) shouldBe newUrl

        coVerify(exactly = 0) { repository.updateCover(any(), any(), any(), any()) }
        verify(exactly = 0) { coverCache.deleteFromCache(any(), any()) }
    }

    @Test
    fun `ignores the cover URL that already failed`() = runTest {
        stubManga()
        stubSource(staleUrl)

        refreshMangaCover.findReplacement(mangaId, staleUrl) shouldBe null

        coVerify(exactly = 0) { repository.updateCover(any(), any(), any(), any()) }
    }

    @Test
    fun `persists only the cover fields`() = runTest {
        stubManga()
        coEvery { repository.updateCover(mangaId, staleUrl, newUrl, any()) } returns true

        refreshMangaCover.persistReplacement(mangaId, staleUrl, newUrl) shouldBe true

        coVerify(exactly = 1) { repository.updateCover(mangaId, staleUrl, newUrl, any()) }
        verify(exactly = 1) { coverCache.deleteFromCache(manga, false) }
    }

    @Test
    fun `does not persist when the entry changed meanwhile`() = runTest {
        stubManga(ogThumbnailUrl = "https://manual.example/cover.jpg")

        refreshMangaCover.persistReplacement(mangaId, staleUrl, newUrl) shouldBe false

        coVerify(exactly = 0) { repository.updateCover(any(), any(), any(), any()) }
    }

    @Test
    fun `does not persist over a custom cover`() = runTest {
        stubManga()
        every { coverCache.hasCustomCover(mangaId) } returns true

        refreshMangaCover.persistReplacement(mangaId, staleUrl, newUrl) shouldBe false

        coVerify(exactly = 0) { repository.updateCover(any(), any(), any(), any()) }
    }

    @Test
    fun `reuses the discovered replacement without hitting the source again`() = runTest {
        stubManga()
        stubSource(newUrl)

        refreshMangaCover.findReplacement(mangaId, staleUrl) shouldBe newUrl
        refreshMangaCover.findReplacement(mangaId, staleUrl) shouldBe newUrl

        coVerify(exactly = 1) { source.getMangaUpdate(any(), any(), true, false) }
    }

    @Test
    fun `concurrent requests share one source call`() = runTest {
        stubManga()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        every { source.id } returns sourceId
        coEvery { sourceManager.get(sourceId) } returns source
        coEvery { source.getMangaUpdate(any(), any(), true, false) } coAnswers {
            started.complete(Unit)
            release.await()
            SMangaUpdate(
                manga = SManga.create().apply { thumbnail_url = newUrl },
                chapters = emptyList(),
            )
        }

        val first = async { refreshMangaCover.findReplacement(mangaId, staleUrl) }
        started.await()
        val second = async { refreshMangaCover.findReplacement(mangaId, staleUrl) }
        release.complete(Unit)

        first.await() shouldBe newUrl
        second.await() shouldBe newUrl
        coVerify(exactly = 1) { source.getMangaUpdate(any(), any(), true, false) }
    }

    private fun stubManga(ogThumbnailUrl: String = staleUrl) {
        coEvery { repository.getMangaById(mangaId) } returns manga.copy(ogThumbnailUrl = ogThumbnailUrl)
    }

    private fun stubSource(thumbnailUrl: String?) {
        every { source.id } returns sourceId
        coEvery { sourceManager.get(sourceId) } returns source
        coEvery { source.getMangaUpdate(any(), any(), true, false) } returns SMangaUpdate(
            manga = SManga.create().apply { this.thumbnail_url = thumbnailUrl },
            chapters = emptyList(),
        )
    }
}
