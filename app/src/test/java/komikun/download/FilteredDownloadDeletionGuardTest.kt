package komikun.download

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

    // Mimics the shape of `DownloadProvider.getValidChapterDirNames`: a plain name and a url-derived
    // hash variant, each also as its `.cbz` archive (what a chapter downloaded as one file resolves
    // to), so the set-intersection behaviour is exercised on every on-disk shape the manager deletes.
    // A blank name has no resolvable directory, hence an empty set.
    private fun dirNames(chapter: Chapter): Set<String> {
        if (chapter.name.isBlank()) return emptySet()
        val scanlator = chapter.scanlator?.takeIf { it.isNotBlank() }
        val base = if (scanlator == null) chapter.name else "${scanlator}_${chapter.name}"
        val hashed = "${base}_${chapter.url.hashCode().toString(16)}"
        return setOf(base, "$base.cbz", hashed, "$hashed.cbz")
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
        // `Ch. 2` and `Ch. 10` differ by more than case, so case-insensitive matching must not reach
        // across distinct names (`Ch. 10` shares the `Ch. 1` prefix, which only a sloppier normalising
        // comparison would treat as a collision).
        val kept = chapter(name = "Ch. 1")
        val hidden = chapter(name = "Ch. 2")
        val longer = chapter(name = "Ch. 10")

        safe(listOf(hidden, longer), listOf(kept)) shouldBe listOf(hidden, longer)
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
        // Same scanlator and name, different url: two common candidates out of four per set (the
        // folder and its `.cbz` archive); only the url-hash variants differ. The conservative
        // direction wins — the file is retained rather than risk deleting the kept one.
        val kept = chapter(name = "Ch. 3", scanlator = "Group", url = "https://example.com/a/3")
        val hidden = chapter(name = "Ch. 3", scanlator = "Group", url = "https://example.com/b/3")

        val keptNames = dirNames(kept)
        val hiddenNames = dirNames(hidden)
        (keptNames intersect hiddenNames) shouldBe setOf("Group_Ch. 3", "Group_Ch. 3.cbz")
        (keptNames - hiddenNames).size shouldBe 2

        safe(listOf(hidden), listOf(kept)) shouldBe emptyList()
    }

    @Test
    fun `a kept chapter whose name differs only by case still protects the hidden one`() {
        // The delete resolves chapter directories case-insensitively (`DownloadProvider.findChapterDirs`
        // matches through `sortedMapOf(String.CASE_INSENSITIVE_ORDER)`), so `ch. 1` is the same folder
        // as `Ch. 1` on disk. No candidate matches exactly, yet the hidden chapter must be dropped:
        // an exact comparison would call this delete safe and take the kept chapter's folder with it.
        val kept = chapter(name = "Ch. 1", url = "https://example.com/a/1")
        val hidden = chapter(name = "ch. 1", url = "https://example.com/b/1")

        (dirNames(kept) intersect dirNames(hidden)) shouldBe emptySet()

        safe(listOf(hidden), listOf(kept)) shouldBe emptyList()
    }

    @Test
    fun `the archived cbz name collides by case too`() {
        // A chapter downloaded as one archive resolves to `<name>.cbz`, a separate on-disk entry the
        // manager deletes the same way, and the case-insensitive lookup hands `ch. 1.cbz` to a kept
        // `Ch. 1.cbz`. The archive candidates differ only by case and carry no exact match either.
        val kept = chapter(name = "Ch. 1", url = "https://example.com/a/1")
        val hidden = chapter(name = "ch. 1", url = "https://example.com/b/1")

        ("Ch. 1.cbz" in dirNames(kept)) shouldBe true
        ("ch. 1.cbz" in dirNames(hidden)) shouldBe true
        (dirNames(kept) intersect dirNames(hidden)) shouldBe emptySet()

        safe(listOf(hidden), listOf(kept)) shouldBe emptyList()
    }

    @Test
    fun `a case-only difference in the scanlator is the same directory as well`() {
        // The scanlator is part of the directory name, so a group differing only in case produces the
        // same folder under the case-insensitive lookup.
        val kept = chapter(name = "Ch. 1", scanlator = "Group", url = "https://example.com/a/1")
        val hidden = chapter(name = "Ch. 1", scanlator = "group", url = "https://example.com/b/1")

        (dirNames(kept) intersect dirNames(hidden)) shouldBe emptySet()

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
