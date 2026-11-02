package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

/**
 * Guards which survivors the automatic cleanup consults before handing files to
 * [DownloadManager.deleteOrphanedChapters].
 *
 * The folder rule is the one the feature is built on: the download folder is
 * `<source dir name>/<manga dir name>`, and several manga — of the same source, or of another
 * extension rendering the same folder name — share it, so every one of their chapters must be read
 * before any file is called orphaned.
 */
class OrphanedDownloadCleanerImplTest {

    private val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore())
    private val downloadManager = mockk<DownloadManager>(relaxed = true)
    private val chapterRepository = mockk<ChapterRepository>(relaxed = true)
    private val mangaRepository = mockk<MangaRepository>(relaxed = true)
    private val sourceManager = mockk<SourceManager>(relaxed = true)

    // The real provider: the folder names under test are the ones it builds, not a stub of them.
    private val provider = DownloadProvider(
        mockk(relaxed = true),
        mockk(relaxed = true),
        LibraryPreferences(InMemoryPreferenceStore()),
        downloadPreferences,
    )

    private val source = mockk<Source>(relaxed = true)

    init {
        // `DownloadProvider.getSourceDirName` is `buildValidFilename(source.toString())`, so this is
        // what makes the folder names under test deterministic.
        every { source.id } returns SOURCE_ID
        every { source.toString() } returns SOURCE_NAME
    }

    private fun cleaner() = OrphanedDownloadCleanerImpl(
        downloadPreferences = downloadPreferences,
        downloadManager = downloadManager,
        chapterRepository = chapterRepository,
        mangaRepository = mangaRepository,
        downloadProvider = provider,
        sourceManager = sourceManager,
    )

    /** The source resolves, and its folder holds [mangas]. */
    private fun stubReachable(mangas: List<Manga>) {
        coEvery { sourceManager.get(any()) } returns source
        coEvery { sourceManager.getAll() } returns listOf(source)
        coEvery { mangaRepository.getMangaBySourceId(SOURCE_ID) } returns mangas
        coEvery { chapterRepository.getChaptersByMangaIds(any()) } returns emptyList()
    }

    @Test
    fun `the cleanup deletes nothing while the preference is off`() = runBlocking {
        downloadPreferences.removeOrphanedDownloads.set(false)
        stubReachable(listOf(manga(id = 1, title = "Title")))

        cleaner().cleanupAfterChapterRemoval(listOf(chapter(id = 1)), manga(id = 1, title = "Title"))

        coVerify(exactly = 0) {
            downloadManager.deleteOrphanedChapters(any(), any(), any(), any())
        }
    }

    @Test
    fun `a bookmarked removed chapter is not handed over while bookmark retention is off`() = runBlocking {
        downloadPreferences.removeBookmarkedChapters.set(false)
        stubReachable(listOf(manga(id = 1, title = "Title")))

        cleaner().cleanupAfterChapterRemoval(
            listOf(chapter(id = 1, bookmark = true), chapter(id = 2)),
            manga(id = 1, title = "Title"),
        )

        val removed = slot<List<Chapter>>()
        coVerify { downloadManager.deleteOrphanedChapters(capture(removed), any(), any(), any()) }
        removed.captured.map { it.id } shouldBe listOf(2L)
    }

    @Test
    fun `a kept bookmarked chapter still protects the files its name resolves to`() = runBlocking {
        // Both removed chapters are named the same, and the url hash is off by default, so both
        // resolve to one directory. The bookmarked one is kept, but its file is that directory's file:
        // leaving it out of the survivor set would let the unbookmarked duplicate delete it.
        downloadPreferences.removeBookmarkedChapters.set(false)
        stubReachable(listOf(manga(id = 1, title = "Title")))

        cleaner().cleanupAfterChapterRemoval(
            listOf(chapter(id = 1, bookmark = true, name = "Chapter 5"), chapter(id = 2, name = "Chapter 5")),
            manga(id = 1, title = "Title"),
        )

        coVerify {
            downloadManager.deleteOrphanedChapters(
                // The delete list is unchanged: the kept chapter is still not handed over.
                match { it.map(Chapter::id) == listOf(2L) },
                match { it.map(Chapter::id) == listOf(1L) },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `a kept bookmarked chapter survives even with no survivor of the folder`() = runBlocking {
        // No installed source renders the folder, so there is no survivor query to run — and an empty
        // id list must not reach SQL — but the kept chapter still names files that must survive.
        downloadPreferences.removeBookmarkedChapters.set(false)
        coEvery { sourceManager.get(any()) } returns source
        coEvery { sourceManager.getAll() } returns emptyList()

        cleaner().cleanupAfterChapterRemoval(
            listOf(chapter(id = 1, bookmark = true), chapter(id = 2)),
            manga(id = 1, title = "Title"),
        )

        coVerify(exactly = 0) { chapterRepository.getChaptersByMangaIds(any()) }
        coVerify {
            downloadManager.deleteOrphanedChapters(
                match { it.map(Chapter::id) == listOf(2L) },
                match { it.map(Chapter::id) == listOf(1L) },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `a chapter of another manga sharing the download folder is a survivor`() = runBlocking {
        // Same source, same title: both write into `<source>/Title`, so the other manga's chapters
        // keep their files. Reading only the removed manga's chapters would delete them.
        // The survivor is named exactly like the removed chapter, so only reading it — not the
        // folder match alone — keeps the file.
        val removedManga = manga(id = 1, title = "Title")
        val sharingManga = manga(id = 2, title = "Title")
        stubReachable(listOf(removedManga, sharingManga))
        coEvery { chapterRepository.getChaptersByMangaIds(listOf(1L, 2L)) } returns
            listOf(chapter(id = 10, mangaId = 2, name = "Chapter 5"))

        cleaner().cleanupAfterChapterRemoval(listOf(chapter(id = 5)), removedManga)

        val handedSource = slot<Source>()
        coVerify {
            downloadManager.deleteOrphanedChapters(
                match { it.map(Chapter::id) == listOf(5L) },
                match { it.map(Chapter::id) == listOf(10L) },
                match { it.id == 1L },
                capture(handedSource),
            )
        }
        // The source decides `isLocal()` and `findChapterDirs`, so a different extension is a failure.
        handedSource.captured.id shouldBe SOURCE_ID
    }

    @Test
    fun `folder matching ignores case and a different manga folder contributes nothing`() = runBlocking {
        // `Solos` and `solos` are one folder on a case-insensitive volume, and `Title`/`title` one
        // manga folder; `Other` is a different folder and must not be read.
        val otherSource = mockk<Source>(relaxed = true)
        every { otherSource.id } returns 2L
        every { otherSource.toString() } returns SOURCE_NAME.lowercase()
        // The fixture premise: both sources render one folder ignoring case.
        provider.getSourceDirName(source) shouldBe SOURCE_NAME
        provider.getSourceDirName(otherSource) shouldBe SOURCE_NAME.lowercase()
        val removedManga = manga(id = 1, title = "Title")
        val sameFolderManga = manga(id = 3, sourceId = 2, title = "title")
        val otherFolderManga = manga(id = 4, title = "Other")
        coEvery { sourceManager.get(any()) } returns source
        coEvery { sourceManager.getAll() } returns listOf(source, otherSource)
        coEvery { mangaRepository.getMangaBySourceId(SOURCE_ID) } returns listOf(removedManga, otherFolderManga)
        coEvery { mangaRepository.getMangaBySourceId(2L) } returns listOf(sameFolderManga)
        coEvery { chapterRepository.getChaptersByMangaIds(any()) } returns emptyList()

        cleaner().cleanupAfterChapterRemoval(listOf(chapter(id = 5)), removedManga)

        coVerify { chapterRepository.getChaptersByMangaIds(match { it.toSet() == setOf(1L, 3L) }) }
    }

    @Test
    fun `an unresolved source deletes nothing`() = runBlocking {
        coEvery { sourceManager.get(any()) } returns null

        cleaner().cleanupAfterChapterRemoval(listOf(chapter(id = 1)), manga(id = 1, title = "Title"))

        coVerify(exactly = 0) {
            downloadManager.deleteOrphanedChapters(any(), any(), any(), any())
        }
        coVerify(exactly = 0) { chapterRepository.getChaptersByMangaIds(any()) }
    }

    @Test
    fun `no survivor of the folder is read from an empty id list`() = runBlocking {
        // The source resolves, but no installed source renders its folder: there is no survivor
        // query to run, and an empty id list must never reach SQL.
        coEvery { sourceManager.get(any()) } returns source
        coEvery { sourceManager.getAll() } returns emptyList()

        cleaner().cleanupAfterChapterRemoval(listOf(chapter(id = 1)), manga(id = 1, title = "Title"))

        coVerify(exactly = 0) { chapterRepository.getChaptersByMangaIds(any()) }
        val handedSource = slot<Source>()
        coVerify {
            downloadManager.deleteOrphanedChapters(
                match { it.map(Chapter::id) == listOf(1L) },
                match { it.isEmpty() },
                match { it.id == 1L },
                capture(handedSource),
            )
        }
        handedSource.captured.id shouldBe SOURCE_ID
    }

    private fun manga(id: Long, sourceId: Long = SOURCE_ID, title: String): Manga = Manga.create().copy(
        id = id,
        source = sourceId,
        ogTitle = title,
    )

    private fun chapter(
        id: Long,
        mangaId: Long = 1L,
        bookmark: Boolean = false,
        name: String = "Chapter $id",
    ): Chapter =
        Chapter.create().copy(
            id = id,
            mangaId = mangaId,
            url = "/chapter/$id",
            name = name,
            bookmark = bookmark,
        )

    companion object {
        private const val SOURCE_ID = 1L
        private const val SOURCE_NAME = "Solos"
    }
}
