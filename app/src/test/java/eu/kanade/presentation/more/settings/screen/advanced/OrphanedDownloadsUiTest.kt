package eu.kanade.presentation.more.settings.screen.advanced

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.OrphanedDownloadScanner.Kind
import eu.kanade.tachiyomi.data.download.OrphanedDownloadScanner.OrphanedDownload
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Guards the ordering rules of the orphaned-download list: the rows must only reorder and section the
 * entries the scan returned, never drop or duplicate one.
 */
class OrphanedDownloadsUiTest {

    // One mock for every entry: the file handle plays no part in ordering, and building one per item
    // would only slow the tests down.
    private val file = mockk<UniFile>(relaxed = true)

    private fun orphan(path: String, size: Long, kind: Kind = Kind.CHAPTER) = OrphanedDownload(
        id = path,
        relativePath = path,
        sizeBytes = size,
        kind = kind,
        file = file,
    )

    private fun itemsOf(rows: List<OrphanedDownloadRow>): List<OrphanedDownload> = rows.map {
        (it as OrphanedDownloadRow.Item).item
    }

    @Test
    fun `largest first orders by size and breaks ties by path`() {
        // The insertion order is deliberately not the answer: a stable sort without the path
        // tiebreaker would keep `s/z` before `s/b`.
        val smallZ = orphan("s/z", size = 10)
        val big = orphan("s/a", size = 30)
        val smallB = orphan("s/b", size = 10)

        val rows = rows(listOf(smallZ, big, smallB), OrphanedDownloadSort.SIZE_DESC, OrphanedDownloadGroup.NONE)

        itemsOf(rows) shouldBe listOf(big, smallB, smallZ)
    }

    @Test
    fun `smallest first orders by size and breaks ties by path`() {
        val smallZ = orphan("s/z", size = 10)
        val big = orphan("s/a", size = 30)
        val smallB = orphan("s/b", size = 10)

        val rows = rows(listOf(smallZ, big, smallB), OrphanedDownloadSort.SIZE_ASC, OrphanedDownloadGroup.NONE)

        itemsOf(rows) shouldBe listOf(smallB, smallZ, big)
    }

    @Test
    fun `path sorts by the relative path`() {
        val z = orphan("src/manga/z.cbz", size = 1)
        val a = orphan("src/manga/a.cbz", size = 2)
        val m = orphan("src/manga/m.cbz", size = 3)

        val rows = rows(listOf(z, a, m), OrphanedDownloadSort.PATH, OrphanedDownloadGroup.NONE)

        itemsOf(rows) shouldBe listOf(a, m, z)
    }

    @Test
    fun `kind sorts chapter entries before manga folders before source folders`() {
        val source = orphan("src", size = 1, kind = Kind.SOURCE)
        val manga = orphan("src/manga", size = 2, kind = Kind.MANGA)
        val chapterZ = orphan("src/manga/z.cbz", size = 3)
        val chapterA = orphan("src/manga/a.cbz", size = 4)

        val rows = rows(
            listOf(source, manga, chapterZ, chapterA),
            OrphanedDownloadSort.KIND,
            OrphanedDownloadGroup.NONE,
        )

        itemsOf(rows) shouldBe listOf(chapterA, chapterZ, manga, source)
    }

    @Test
    fun `no grouping renders entries only, in the sorted order`() {
        val b = orphan("s/b", size = 1)
        val a = orphan("s/a", size = 2)

        val rows = rows(listOf(b, a), OrphanedDownloadSort.PATH, OrphanedDownloadGroup.NONE)

        rows shouldBe listOf(OrphanedDownloadRow.Item(a), OrphanedDownloadRow.Item(b))
    }

    @Test
    fun `grouping by source puts each source folder under its own header`() {
        val aManga1 = orphan("srcA/manga1/ch1.cbz", size = 1)
        val aManga2 = orphan("srcA/manga2/ch1.cbz", size = 2)
        val bManga2 = orphan("srcB/manga2/ch1.cbz", size = 3)

        val rows = rows(
            listOf(bManga2, aManga2, aManga1),
            OrphanedDownloadSort.PATH,
            OrphanedDownloadGroup.SOURCE,
        )

        rows shouldBe listOf(
            OrphanedDownloadRow.Header("source:srcA", OrphanedDownloadRow.Label.Source("srcA")),
            OrphanedDownloadRow.Item(aManga1),
            OrphanedDownloadRow.Item(aManga2),
            OrphanedDownloadRow.Header("source:srcB", OrphanedDownloadRow.Label.Source("srcB")),
            OrphanedDownloadRow.Item(bManga2),
        )
    }

    @Test
    fun `grouping by manga names each folder entry after itself`() {
        val aManga1Ch1 = orphan("srcA/manga1/ch1.cbz", size = 3)
        val aManga1Ch2 = orphan("srcA/manga1/ch2.cbz", size = 1)
        val bManga2 = orphan("srcB/manga2/ch1.cbz", size = 2)
        // A manga-folder entry is a folder path, so it is its own group: sending it to its parent
        // would name the section `srcB` and split it from the folder it names.
        val bMangaFolder = orphan("srcB/manga2", size = 5, kind = Kind.MANGA)
        // A source folder that matches no source has a single-segment path: it is its own group too,
        // so the header always has a name.
        val source = orphan("lostSource", size = 4, kind = Kind.SOURCE)

        val rows = rows(
            listOf(aManga1Ch1, source, bManga2, aManga1Ch2, bMangaFolder),
            OrphanedDownloadSort.SIZE_DESC,
            OrphanedDownloadGroup.MANGA,
        )

        rows shouldBe listOf(
            OrphanedDownloadRow.Header("manga:lostSource", OrphanedDownloadRow.Label.Manga("lostSource")),
            OrphanedDownloadRow.Item(source),
            OrphanedDownloadRow.Header("manga:srcA/manga1", OrphanedDownloadRow.Label.Manga("srcA/manga1")),
            // The sort is applied within the group, not to the raw input order.
            OrphanedDownloadRow.Item(aManga1Ch1),
            OrphanedDownloadRow.Item(aManga1Ch2),
            OrphanedDownloadRow.Header("manga:srcB/manga2", OrphanedDownloadRow.Label.Manga("srcB/manga2")),
            // The folder entry sits with the chapter entry that names the same folder, not above it.
            OrphanedDownloadRow.Item(bMangaFolder),
            OrphanedDownloadRow.Item(bManga2),
        )
    }

    @Test
    fun `grouping by kind emits chapter, manga and source sections in that fixed order`() {
        val source = orphan("src", size = 1, kind = Kind.SOURCE)
        val manga = orphan("srcZ/manga", size = 2, kind = Kind.MANGA)
        val chapter = orphan("srcA/manga/ch1.cbz", size = 3)

        val rows = rows(
            listOf(source, manga, chapter),
            OrphanedDownloadSort.PATH,
            OrphanedDownloadGroup.KIND,
        )

        rows shouldBe listOf(
            OrphanedDownloadRow.Header("kind:CHAPTER", OrphanedDownloadRow.Label.Type(Kind.CHAPTER)),
            OrphanedDownloadRow.Item(chapter),
            OrphanedDownloadRow.Header("kind:MANGA", OrphanedDownloadRow.Label.Type(Kind.MANGA)),
            OrphanedDownloadRow.Item(manga),
            OrphanedDownloadRow.Header("kind:SOURCE", OrphanedDownloadRow.Label.Type(Kind.SOURCE)),
            OrphanedDownloadRow.Item(source),
        )
    }

    @Test
    fun `entries and headers sort case-insensitively instead of byte-exactly`() {
        // Byte order puts every uppercase name before every lowercase one, so a byte-exact sort lists
        // `Beta` before `alpha` while the header order says the opposite: one comparator for both.
        val upper = orphan("Beta/ch1.cbz", size = 1)
        val lower = orphan("alpha/ch1.cbz", size = 1)

        val rows = rows(listOf(upper, lower), OrphanedDownloadSort.PATH, OrphanedDownloadGroup.SOURCE)

        rows shouldBe listOf(
            OrphanedDownloadRow.Header("source:alpha", OrphanedDownloadRow.Label.Source("alpha")),
            OrphanedDownloadRow.Item(lower),
            OrphanedDownloadRow.Header("source:Beta", OrphanedDownloadRow.Label.Source("Beta")),
            OrphanedDownloadRow.Item(upper),
        )
    }

    @Test
    fun `paths differing only in case keep a byte-exact order`() {
        // Two entries can share a case-insensitive path: the byte-exact tiebreaker keeps their order
        // derived from the path instead of from what the scan happened to walk first.
        val lower = orphan("a/x.cbz", size = 1)
        val upper = orphan("A/x.cbz", size = 2)

        itemsOf(
            rows(listOf(lower, upper), OrphanedDownloadSort.PATH, OrphanedDownloadGroup.NONE),
        ) shouldBe listOf(upper, lower)
    }

    @Test
    fun `only chapter entries are selectable`() {
        val chapter = orphan("src/manga/ch1.cbz", size = 1)
        val mangaFolder = orphan("src/manga2", size = 2, kind = Kind.MANGA)
        val sourceFolder = orphan("src2", size = 3, kind = Kind.SOURCE)

        selectableIds(listOf(chapter, mangaFolder, sourceFolder)) shouldBe setOf(chapter.id)
        selectableIds(emptyList()) shouldBe emptySet()
        // A whole folder never counts as "everything selected", so select-all cannot take it with it.
        isAllSelected(listOf(chapter, mangaFolder), setOf(chapter.id)) shouldBe true
        isAllSelected(listOf(mangaFolder), setOf(mangaFolder.id)) shouldBe false
    }

    @Test
    fun `the selected size counts only the selected entries`() {
        val selected = orphan("src/manga/a.cbz", size = 10)
        val unselected = orphan("src/manga/b.cbz", size = 25)

        selectedSizeBytes(listOf(selected, unselected), setOf(selected.id)) shouldBe 10
        selectedSizeBytes(listOf(selected, unselected), setOf(selected.id, unselected.id)) shouldBe 35
        selectedSizeBytes(listOf(selected, unselected), emptySet()) shouldBe 0
    }

    @Test
    fun `retainSelection drops ids that are no longer listed and keeps the rest`() {
        val kept = orphan("src/manga/a.cbz", size = 1)
        val unselected = orphan("src/manga/b.cbz", size = 2)

        retainSelection(setOf(kept.id, unselected.id, "src/manga/deleted.cbz"), listOf(kept, unselected)) shouldBe
            setOf(kept.id, unselected.id)
        retainSelection(setOf(kept.id), listOf(unselected)) shouldBe emptySet()
        retainSelection(setOf(kept.id), emptyList()) shouldBe emptySet()
    }
}
