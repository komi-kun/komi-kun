package komikun.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource

/**
 * Guards which downloaded chapters of a library manga a persistent chapter filter makes a deletion
 * candidate — and, just as importantly, which ones it must leave alone.
 */
class FilteredDownloadPolicyTest {

    private fun manga(
        favorite: Boolean = true,
        source: Long = SOURCE_ID,
        chapterFlags: Long = Manga.SHOW_ALL,
    ) = Manga.create().copy(
        id = MANGA_ID,
        source = source,
        favorite = favorite,
        chapterFlags = chapterFlags,
    )

    private fun chapter(
        id: Long,
        read: Boolean = false,
        bookmark: Boolean = false,
        scanlator: String? = null,
    ) = Chapter.create().copy(
        id = id,
        mangaId = MANGA_ID,
        read = read,
        bookmark = bookmark,
        scanlator = scanlator,
    )

    private fun hidden(
        chapters: List<Chapter>,
        manga: Manga,
        excludedScanlators: Set<String> = emptySet(),
        downloadedChapterIds: Collection<Long> = chapters.map { it.id },
        keepBookmarked: Boolean = false,
    ): List<Chapter> = FilteredDownloadPolicy.hiddenDownloadedChapters(
        chapters = chapters,
        manga = manga,
        excludedScanlators = excludedScanlators,
        downloadedChapterIds = downloadedChapterIds,
        keepBookmarked = keepBookmarked,
    )

    @Test
    fun `an excluded scanlator is a candidate whether the chapter is read or unread`() {
        // Everything but the scanlator filter is off, so the scanlator is the only hiding cause here.
        val readHidden = chapter(id = 1, read = true, scanlator = "Scanner")
        val unreadHidden = chapter(id = 2, read = false, scanlator = "Scanner")
        val otherScanlator = chapter(id = 3, read = false, scanlator = "Other")

        hidden(listOf(readHidden, unreadHidden, otherScanlator), manga(), excludedScanlators = setOf("Scanner")) shouldBe
            listOf(readHidden, unreadHidden)
    }

    @Test
    fun `the show-read-only filter hides unread chapters and that is not a hiding cause`() {
        // Only the read filter is on (bookmark and downloaded/not stay DISABLED), and this unread
        // chapter has no other reason to be hidden: it must not be a candidate.
        val showOnlyRead = manga(chapterFlags = Manga.CHAPTER_SHOW_READ)
        val unreadChapter = chapter(id = 1, read = false)

        hidden(listOf(unreadChapter), showOnlyRead) shouldBe emptyList()
    }

    @Test
    fun `the show-unread-only filter hides read chapters and that is not a hiding cause`() {
        // The mirror direction, so a regression that honours the read filter in either TriState arm
        // fails on its own test.
        val showOnlyUnread = manga(chapterFlags = Manga.CHAPTER_SHOW_UNREAD)
        val readChapter = chapter(id = 1, read = true)

        hidden(listOf(readChapter), showOnlyUnread) shouldBe emptyList()
    }

    @Test
    fun `the downloaded filter is ignored in both directions and never exempts a hidden chapter`() {
        // Neither chapter is hidden by the bookmark filter or a scanlator, so only the downloaded/not
        // filter could make them candidates — and it must not.
        val notDownloadedOnly = listOf(chapter(id = 1), chapter(id = 2))
        hidden(notDownloadedOnly, manga(chapterFlags = Manga.CHAPTER_SHOW_NOT_DOWNLOADED)) shouldBe emptyList()
        hidden(notDownloadedOnly, manga(chapterFlags = Manga.CHAPTER_SHOW_DOWNLOADED)) shouldBe emptyList()

        // Nor does it exempt one: the scanlator filter still hides it while "show only downloaded" is on.
        val excluded = chapter(id = 3, scanlator = "Excluded")
        hidden(
            listOf(excluded),
            manga(chapterFlags = Manga.CHAPTER_SHOW_DOWNLOADED),
            excludedScanlators = setOf("Excluded"),
        ) shouldBe listOf(excluded)
    }

    @Test
    fun `the bookmark filter hides in both directions and hides nothing when disabled`() {
        val notBookmarked = chapter(id = 1, bookmark = false)
        val bookmarked = chapter(id = 2, bookmark = true)
        val chapters = listOf(notBookmarked, bookmarked)

        // ENABLED_IS shows only bookmarked chapters, so a non-bookmarked download is a candidate.
        hidden(chapters, manga(chapterFlags = Manga.CHAPTER_SHOW_BOOKMARKED)) shouldBe
            listOf(notBookmarked)
        // ENABLED_NOT shows only non-bookmarked chapters, so a bookmarked download is a candidate.
        hidden(chapters, manga(chapterFlags = Manga.CHAPTER_SHOW_NOT_BOOKMARKED)) shouldBe
            listOf(bookmarked)
        // DISABLED hides nothing.
        hidden(chapters, manga(chapterFlags = Manga.SHOW_ALL)) shouldBe emptyList()
    }

    @Test
    fun `an excluded scanlator hides its chapters and only those`() {
        val excluded = chapter(id = 1, scanlator = "Excluded")
        val other = chapter(id = 2, scanlator = "Other")
        val noScanlator = chapter(id = 3, scanlator = null)

        hidden(
            listOf(excluded, other, noScanlator),
            manga(),
            excludedScanlators = setOf("Excluded"),
        ) shouldBe listOf(excluded)
    }

    @Test
    fun `a kept bookmarked chapter is not a candidate even when a filter hides it`() {
        val bookmarked = chapter(id = 1, bookmark = true, scanlator = "Excluded")

        // The scanlator filter hides it either way; only the retention flag decides.
        hidden(
            listOf(bookmarked),
            manga(),
            excludedScanlators = setOf("Excluded"),
            keepBookmarked = true,
        ) shouldBe emptyList()
        hidden(
            listOf(bookmarked),
            manga(),
            excludedScanlators = setOf("Excluded"),
            keepBookmarked = false,
        ) shouldBe listOf(bookmarked)
    }

    @Test
    fun `a local-source manga's chapters are never candidates`() {
        val localManga = manga(source = LocalSource.ID)
        val excluded = chapter(id = 1, scanlator = "Excluded")

        hidden(listOf(excluded), localManga, excludedScanlators = setOf("Excluded")) shouldBe emptyList()
    }

    @Test
    fun `a manga outside the library is never a candidate`() {
        val notFavorite = manga(favorite = false)
        val excluded = chapter(id = 1, scanlator = "Excluded")

        hidden(listOf(excluded), notFavorite, excludedScanlators = setOf("Excluded")) shouldBe emptyList()
    }

    @Test
    fun `a filter-hidden chapter that is not downloaded is not returned`() {
        val downloadedHidden = chapter(id = 1, scanlator = "Excluded")
        val notDownloadedHidden = chapter(id = 2, scanlator = "Excluded")

        hidden(
            listOf(downloadedHidden, notDownloadedHidden),
            manga(),
            excludedScanlators = setOf("Excluded"),
            downloadedChapterIds = setOf(downloadedHidden.id),
        ) shouldBe listOf(downloadedHidden)
    }

    private companion object {
        const val MANGA_ID = 1L
        const val SOURCE_ID = 2L
    }
}
