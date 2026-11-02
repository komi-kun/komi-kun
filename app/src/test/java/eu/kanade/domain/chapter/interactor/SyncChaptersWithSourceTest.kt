package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.OrphanedDownloadCleanerImpl
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.download.service.OrphanedDownloadCleaner
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

/**
 * The orphaned-download cleanup must not eat the file of a chapter the source re-listed under a new
 * url but the same (name, scanlator) — the replacement writes the very same directory.
 *
 * The guarantee only holds if the cleaner runs **after** [ChapterRepository.updateFromRemote]
 * committed: it re-reads the survivors itself, so a read taken before the commit sees the old row
 * instead of the replacement.
 */
class SyncChaptersWithSourceTest {

    private val downloadManager = mockk<DownloadManager>(relaxed = true)
    private val chapterRepository = mockk<ChapterRepository>(relaxed = true)
    private val mangaRepository = mockk<MangaRepository>(relaxed = true)
    private val sourceManager = mockk<SourceManager>(relaxed = true)
    private val updateManga = mockk<UpdateManga>(relaxed = true)
    private val orphanedDownloadCleaner = mockk<OrphanedDownloadCleaner>(relaxed = true)

    private val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
    private val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore())

    // The real provider: the "same file name" premise below is its own output, not a stub of it.
    private val downloadProvider = DownloadProvider(
        mockk(relaxed = true),
        mockk(relaxed = true),
        libraryPreferences,
        downloadPreferences,
    )

    private val source = mockk<Source>(relaxed = true)

    private val manga = Manga.create().copy(
        id = MANGA_ID,
        source = SOURCE_ID,
        ogTitle = "Title",
        url = "/manga/1",
    )

    /** The chapter the source dropped. */
    private val droppedChapter = Chapter.create().copy(
        id = DROPPED_ID,
        mangaId = MANGA_ID,
        url = DROPPED_URL,
        name = CHAPTER_NAME,
        chapterNumber = 5.0,
    )

    /** What the source lists now: same name, new url, so the same on-disk chapter directory. */
    private val replacementSourceChapter = SChapter(name = CHAPTER_NAME, url = REPLACEMENT_URL)

    /** The replacement as the database hands it back after the commit. */
    private val replacementChapter = Chapter.create().copy(
        id = REPLACEMENT_ID,
        mangaId = MANGA_ID,
        url = REPLACEMENT_URL,
        name = CHAPTER_NAME,
        chapterNumber = 5.0,
    )

    init {
        every { source.id } returns SOURCE_ID
        every { source.toString() } returns SOURCE_NAME
        coEvery { chapterRepository.getChapterByMangaId(MANGA_ID) } returns listOf(droppedChapter)
    }

    @Test
    fun `the cleanup is handed the dropped chapter and runs after the update commits`() = runBlocking {
        coEvery { chapterRepository.updateFromRemote(any(), any(), any()) } returns listOf(replacementChapter)

        interactor().await(listOf(replacementSourceChapter), manga, source)

        coVerify {
            orphanedDownloadCleaner.cleanupAfterChapterRemoval(listOf(droppedChapter), manga)
        }
        // Before the commit the removed rows still exist, so a cleaner reading survivors then would
        // keep the files of every chapter the source actually dropped.
        coVerifyOrder {
            chapterRepository.updateFromRemote(any(), any(), any())
            orphanedDownloadCleaner.cleanupAfterChapterRemoval(any(), any())
        }
    }

    @Test
    fun `the replacement is a survivor, so only the dropped chapter's file is at risk`() = runBlocking {
        // The premise: the two urls resolve to one directory, so the replacement really does own the
        // file the dropped chapter's row named.
        downloadProvider.getChapterDirName(CHAPTER_NAME, null, DROPPED_URL) shouldBe
            downloadProvider.getChapterDirName(CHAPTER_NAME, null, REPLACEMENT_URL)

        // A minimal stand-in for the chapter table: `updateFromRemote` is the only write, and the
        // survivor read returns whatever it left there, exactly like the real repository would.
        val rows = mutableListOf(droppedChapter)
        coEvery { chapterRepository.getChaptersByMangaIds(any()) } answers { rows.toList() }
        coEvery { chapterRepository.updateFromRemote(any(), any(), any()) } answers {
            val removedIds = invocation.args[0] as List<Long>
            val added = invocation.args[1] as List<Chapter>
            // The replacement is added in the same transaction that removes the old row.
            rows.removeAll { it.id in removedIds }
            rows += added.mapIndexed { i, chapter -> chapter.copy(id = REPLACEMENT_ID + i) }
            rows.toList()
        }
        coEvery { sourceManager.get(any()) } returns source
        coEvery { sourceManager.getAll() } returns listOf(source)
        coEvery { mangaRepository.getMangaBySourceId(SOURCE_ID) } returns listOf(manga)

        interactor(withRealCleaner = true).await(listOf(replacementSourceChapter), manga, source)

        coVerify { chapterRepository.updateFromRemote(removedIds = listOf(DROPPED_ID), added = any(), updated = any()) }
        // The survivor set is the one read *after* the commit; a read taken before it hands back the
        // dropped row instead, which is the defect this case pins.
        coVerifyOrder {
            chapterRepository.updateFromRemote(any(), any(), any())
            chapterRepository.getChaptersByMangaIds(any())
        }
        coVerify {
            downloadManager.deleteOrphanedChapters(
                match { it.map(Chapter::id) == listOf(DROPPED_ID) },
                match { it.map(Chapter::id) == listOf(REPLACEMENT_ID) },
                match { it.id == MANGA_ID },
                any(),
            )
        }
    }

    private fun interactor(withRealCleaner: Boolean = false) = SyncChaptersWithSource(
        downloadManager = downloadManager,
        downloadProvider = downloadProvider,
        chapterRepository = chapterRepository,
        shouldUpdateDbChapter = ShouldUpdateDbChapter(),
        updateManga = updateManga,
        getExcludedScanlators = GetExcludedScanlators(mangaRepository),
        libraryPreferences = libraryPreferences,
        syncPreferences = SyncPreferences(InMemoryPreferenceStore()),
        orphanedDownloadCleaner = if (withRealCleaner) {
            OrphanedDownloadCleanerImpl(
                downloadPreferences = downloadPreferences,
                downloadManager = downloadManager,
                chapterRepository = chapterRepository,
                mangaRepository = mangaRepository,
                downloadProvider = downloadProvider,
                sourceManager = sourceManager,
            )
        } else {
            orphanedDownloadCleaner
        },
    )

    companion object {
        private const val SOURCE_ID = 42L
        private const val SOURCE_NAME = "Solos"
        private const val MANGA_ID = 1L
        private const val DROPPED_ID = 10L
        private const val REPLACEMENT_ID = 11L
        private const val CHAPTER_NAME = "Chapter 5"
        private const val DROPPED_URL = "/chapter/5"
        private const val REPLACEMENT_URL = "/chapter/5-reupload"
    }
}
