package eu.kanade.tachiyomi.data.download

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

/**
 * Guards the collision rule of the orphaned-download cleanup: a removed chapter's file may only be
 * deleted when no surviving chapter still resolves to that name.
 */
class OrphanedDownloadsTest {

    /**
     * Stands in for `DownloadProvider.getValidChapterDirNames`, which always yields both the folder
     * form and the archived `.cbz` form of a chapter name.
     */
    private fun namesOf(name: String, scanlator: String?, url: String): List<String> =
        listOf(name, "$name.cbz")

    private fun chapterNames(chapter: Chapter): List<String> =
        namesOf(chapter.name, chapter.scanlator, chapter.url)

    private fun chapter(id: Long, name: String) = Chapter.create().copy(id = id, name = name, url = "/chapter/$id")

    @Test
    fun `a removed chapter whose names a surviving chapter still produces is not orphaned`() {
        val removed = chapter(id = 1, name = "Vol.01 Ch.001")
        val survivor = chapter(id = 2, name = "Vol.01 Ch.001")

        val reachable = OrphanedDownloads.reachableFileNames(listOf(survivor), ::namesOf)

        reachable shouldContainExactlyInAnyOrder listOf("Vol.01 Ch.001", "Vol.01 Ch.001.cbz")
        chapterNames(removed).filterNot { it in reachable } shouldBe emptyList()
    }

    @Test
    fun `a removed chapter with a unique name is orphaned`() {
        val removed = chapter(id = 1, name = "Vol.01 Ch.001")
        val survivor = chapter(id = 2, name = "Vol.01 Ch.002")

        val reachable = OrphanedDownloads.reachableFileNames(listOf(survivor), ::namesOf)

        reachable shouldNotContain "Vol.01 Ch.001"
        reachable shouldNotContain "Vol.01 Ch.001.cbz"
        chapterNames(removed).filterNot { it in reachable } shouldContainExactlyInAnyOrder
            listOf("Vol.01 Ch.001", "Vol.01 Ch.001.cbz")
    }

    @Test
    fun `a name that merely resembles a surviving one is still orphaned`() {
        val removed = chapter(id = 1, name = "Vol.01 Ch.001.5")
        val survivor = chapter(id = 2, name = "Vol.01 Ch.001")

        val reachable = OrphanedDownloads.reachableFileNames(listOf(survivor), ::namesOf)

        chapterNames(removed).filterNot { it in reachable } shouldContainExactlyInAnyOrder
            listOf("Vol.01 Ch.001.5", "Vol.01 Ch.001.5.cbz")
    }

    @Test
    fun `with no surviving chapter every removed name is orphaned`() {
        val removed = chapter(id = 1, name = "Vol.01 Ch.001")
        val other = chapter(id = 2, name = "Vol.01 Ch.002")

        val reachable = OrphanedDownloads.reachableFileNames(emptyList(), ::namesOf)

        reachable shouldBe emptySet<String>()
        chapterNames(removed).filterNot { it in reachable } shouldContainExactlyInAnyOrder
            listOf("Vol.01 Ch.001", "Vol.01 Ch.001.cbz")
        chapterNames(other).filterNot { it in reachable } shouldContainExactlyInAnyOrder
            listOf("Vol.01 Ch.002", "Vol.01 Ch.002.cbz")
    }

    @Test
    fun `the reachable set is deduplicated across chapters`() {
        val chapters = listOf(chapter(id = 1, name = "Vol.01 Ch.001"), chapter(id = 2, name = "Vol.01 Ch.001"))

        val reachable = OrphanedDownloads.reachableFileNames(chapters, ::namesOf)

        reachable.size shouldBe 2
        reachable shouldContainExactlyInAnyOrder listOf("Vol.01 Ch.001", "Vol.01 Ch.001.cbz")
    }

    @Test
    fun `the reachable set compares names ignoring case, as the file lookup does`() {
        // `DownloadProvider.findChapterDirs` resolves names case-insensitively, so the set has to
        // deduplicate the same way: these two chapters yield exactly two distinct files, not four.
        val chapters = listOf(chapter(id = 1, name = "Chapter 1"), chapter(id = 2, name = "chapter 1"))

        val reachable = OrphanedDownloads.reachableFileNames(chapters, ::namesOf)

        reachable.size shouldBe 2
        OrphanedDownloads.isOrphaned("Chapter 1.CBZ", reachable) shouldBe false
        OrphanedDownloads.isOrphaned("chapter 1.cbz", reachable) shouldBe false
        OrphanedDownloads.isOrphaned("CHAPTER 1", reachable) shouldBe false
    }

    @Test
    fun `an entry whose name cannot be read is never orphaned`() {
        val reachable = OrphanedDownloads.reachableFileNames(listOf(chapter(id = 2, name = "Chapter 1")), ::namesOf)

        // `null` is an unknown name, not proof that nothing owns the entry.
        OrphanedDownloads.isOrphaned(null, reachable) shouldBe false
        OrphanedDownloads.isOrphaned(null, emptySet()) shouldBe false
    }

    @Test
    fun `an entry no surviving chapter names is orphaned`() {
        val reachable = OrphanedDownloads.reachableFileNames(listOf(chapter(id = 2, name = "Chapter 1")), ::namesOf)

        OrphanedDownloads.isOrphaned("Chapter 2.cbz", reachable) shouldBe true
        OrphanedDownloads.isOrphaned("Chapter 1.cbz", reachable) shouldBe false
        OrphanedDownloads.isOrphaned("Chapter 2.cbz", emptySet()) shouldBe true
    }

    @Test
    fun `the chapter fields reach the name builder as name, scanlator and url`() {
        // `DownloadProvider.getValidChapterDirNames(name, scanlator, url)`: a swapped scanlator and
        // url would still compile (both are Strings) and would build names nothing on disk matches,
        // which is exactly the shape that deletes a live file.
        val chapter = Chapter.create().copy(
            id = 1,
            name = "Vol.01",
            scanlator = "Group",
            url = "/chapter/1",
        )
        val calls = mutableListOf<Triple<String, String?, String>>()

        OrphanedDownloads.reachableFileNames(listOf(chapter)) { name, scanlator, url ->
            calls += Triple(name, scanlator, url)
            emptyList()
        }

        calls shouldBe listOf(Triple("Vol.01", "Group", "/chapter/1"))
    }

    @Test
    fun `only the entries no surviving chapter names are handed over for deletion`() {
        // The single deletion decision, exercised on mixed input: the reachable entry survives, the
        // unreachable one is returned, and an unreadable (`null`) name is kept — the last is what an
        // inverted filter would delete instead.
        val reachable = OrphanedDownloads.reachableFileNames(listOf(chapter(id = 1, name = "Chapter 1")), ::namesOf)
        val entries = listOf("Chapter 1.cbz", "Chapter 2.cbz", null)

        OrphanedDownloads.orphaned(entries, { it }, reachable) shouldBe listOf("Chapter 2.cbz")
        OrphanedDownloads.orphaned(entries, { it }, emptySet()) shouldBe listOf("Chapter 1.cbz", "Chapter 2.cbz")
    }

    @Test
    fun `cleanup runs only when enabled and something was removed`() {
        val removed = listOf(chapter(id = 1, name = "Vol.01 Ch.001"))

        OrphanedDownloads.shouldRun(enabled = true, removedChapters = removed) shouldBe true
        OrphanedDownloads.shouldRun(enabled = false, removedChapters = removed) shouldBe false
        OrphanedDownloads.shouldRun(enabled = true, removedChapters = emptyList()) shouldBe false
        OrphanedDownloads.shouldRun(enabled = false, removedChapters = emptyList()) shouldBe false
    }

    @Test
    fun `a bookmarked removed chapter keeps its files while bookmark retention is off`() {
        val bookmarked = chapter(id = 1, name = "Vol.01 Ch.001").copy(bookmark = true)
        val plain = chapter(id = 2, name = "Vol.01 Ch.002")

        // Retention off: the bookmarked chapter is not handed over for deletion, so its file stays.
        OrphanedDownloads.removableChapters(listOf(bookmarked, plain), removeBookmarkedChapters = false)
            .map { it.id } shouldBe listOf(2L)

        // Retention on: the bookmark no longer protects the file.
        OrphanedDownloads.removableChapters(listOf(bookmarked, plain), removeBookmarkedChapters = true)
            .map { it.id } shouldBe listOf(1L, 2L)
    }
}
