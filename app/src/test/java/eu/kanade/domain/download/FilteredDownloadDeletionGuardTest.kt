// KUN -->
package eu.kanade.domain.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

/**
 * Guards which of a sweep's deletion candidates are actually safe to delete: a chapter that shares a
 * download directory name with a chapter that stays on disk must be dropped, because deleting by
 * directory name would take the survivor's files with it.
 */
class FilteredDownloadDeletionGuardTest {

    private fun chapter(
        name: String,
        scanlator: String? = null,
        url: String = "https://example.com/${scanlator.orEmpty()}_$name",
    ) = Chapter.create().copy(
        name = name,
        scanlator = scanlator,
        url = url,
    )

    // Mimics the shape of `DownloadProvider.getValidChapterDirNames`: a set of candidates, usually
    // one plain name plus a url-derived hash variant (never a single string), so the set-intersection
    // behaviour is genuinely exercised. A blank name has no resolvable directory, hence an empty set.
    private fun dirNames(chapter: Chapter): Set<String> {
        if (chapter.name.isBlank()) return emptySet()
        val scanlator = chapter.scanlator?.takeIf { it.isNotBlank() }
        val base = if (scanlator == null) chapter.name else "${scanlator}_${chapter.name}"
        return setOf(base, "${base}_${chapter.url.hashCode().toString(16)}")
    }

    private fun safe(toDelete: List<Chapter>, kept: List<Chapter>): List<Chapter> =
        FilteredDownloadDeletionGuard.safeToDelete(toDelete, kept) { dirNames(it) }

    @Test
    fun `a chapter sharing a directory name with a kept sibling is dropped from the result`() {
        // Same name, no scanlator, different url: exactly the duplicate case that makes two chapters
        // resolve to one directory. The hash variant differs, but the plain name collides.
        val keptSibling = chapter(name = "Ch. 5", url = "https://example.com/a/5")
        val hiddenDuplicate = chapter(name = "Ch. 5", url = "https://example.com/b/5")
        val distinct = chapter(name = "Ch. 6")

        safe(listOf(hiddenDuplicate, distinct), listOf(keptSibling)) shouldBe listOf(distinct)
    }

    @Test
    fun `a chapter with no directory name in common with a kept sibling stays deletable`() {
        val kept = chapter(name = "Ch. 1")
        val hidden = chapter(name = "Ch. 2")

        safe(listOf(hidden), listOf(kept)) shouldBe listOf(hidden)
    }

    @Test
    fun `a chapter with no resolvable directory names is not dropped`() {
        // The blank name yields an empty candidate set, which intersects nothing. A real sibling is
        // kept, so this is not the "nothing is kept" short-circuit.
        val blank = chapter(name = "")
        val kept = chapter(name = "Ch. 1")

        safe(listOf(blank), listOf(kept)) shouldBe listOf(blank)
    }

    @Test
    fun `two chapters in toDelete sharing a name do not protect each other`() {
        // Only the kept set protects: both duplicates stay deletable, in input order.
        val firstDuplicate = chapter(name = "Ch. 9", url = "https://example.com/a/9")
        val secondDuplicate = chapter(name = "Ch. 9", url = "https://example.com/b/9")
        val kept = chapter(name = "Ch. 10")

        safe(listOf(firstDuplicate, secondDuplicate), listOf(kept)) shouldBe
            listOf(firstDuplicate, secondDuplicate)
    }

    @Test
    fun `a kept chapter whose directory names partially overlap still drops the delete`() {
        // Same scanlator and name, different url: one common candidate out of two per set. The
        // conservative direction wins — the file is retained rather than risk deleting the kept one.
        val kept = chapter(name = "Ch. 3", scanlator = "Group", url = "https://example.com/a/3")
        val hidden = chapter(name = "Ch. 3", scanlator = "Group", url = "https://example.com/b/3")

        val keptNames = dirNames(kept)
        val hiddenNames = dirNames(hidden)
        (keptNames intersect hiddenNames).size shouldBe 1
        (keptNames - hiddenNames).size shouldBe 1

        safe(listOf(hidden), listOf(kept)) shouldBe emptyList()
    }

    @Test
    fun `when nothing is kept everything in toDelete is returned unchanged`() {
        val chapters = listOf(
            chapter(name = "Ch. 1"),
            chapter(name = "Ch. 1", url = "https://example.com/other/1"),
        )

        safe(chapters, emptyList()) shouldBe chapters
    }
}
// KUN <--
