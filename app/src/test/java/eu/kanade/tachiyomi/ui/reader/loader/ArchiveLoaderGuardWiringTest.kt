// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// **Source-level wiring guards for the archive read guard — not behavioural tests.**
//
// The guard itself (`ArchiveReadGuard`) and the enumeration seam (`GuardedArchiveEnumeration`) are
// tested behaviourally in `ArchiveReadGuardTest` / `GuardedArchiveEnumerationTest`. What no JVM test
// in this module can prove is that the two loaders still *use* them: a real `ArchiveReader` needs a
// `ParcelFileDescriptor` and libarchive, and a read that escapes the guard does not fail an assertion
// — it touches an unmapped range and kills the process. The crash this closes was a bare
// `reader.close()` in `recycle()` (which unmaps while a background page read is in flight), so the
// whole fix can be reverted to one line with every other test still green. These tests read the call
// site sources, the same way `OcrPolicySourceWiringTest` pins the OCR call sites.
//
// Two properties are deliberate, because a grep guard easily becomes noise:
//  * comments are stripped first, so a needle can never be satisfied (or broken) by prose, and the
//    documentation around these files stays free to change;
//  * the needles tolerate any whitespace between tokens, so re-indenting or wrapping (Spotless's
//    business) cannot fail the test.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.loader

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File
import kotlin.math.min

class ArchiveLoaderGuardWiringTest {

    /** A page loader's source, with comments removed. */
    private fun loader(name: String): String =
        File("src/main/java/eu/kanade/tachiyomi/ui/reader/loader/$name").readText().withoutComments()

    /**
     * `ArchivePageLoader.recycle()` unmaps the archive from the main thread while background page
     * reads may still be in flight. Reverting the body to the pre-fix bare `reader.close()` makes the
     * first assertion fail; adding a second, unguarded close anywhere in the file makes the count
     * fail.
     */
    @Test
    fun `the cbz loader closes its reader only through the read guard`() {
        val archive = loader("ArchivePageLoader.kt")

        functionBody(archive, "override fun recycle()") shouldContainSource
            """archiveReads\s*\.\s*close\s*\{\s*reader\s*\.\s*close\(\s*\)\s*\}"""
        // Exactly one close of the reader in the whole file, and it is the guarded one above.
        occurrences(archive, """reader\s*\.\s*close\(\s*\)""") shouldBe 1
        // The enumeration goes through the seam (per-step holds), not the pre-fix bulk walk, which
        // held the read lock for every entry and made `recycle()` wait for the whole archive.
        val pages = functionBody(archive, "override suspend fun getPages()")
        pages shouldContainSource """archiveReads\s*\.\s*imageEntryNames\(\s*archiveSource\s*\)"""
        pages shouldContainSource """archiveReads\s*\.\s*readerPage\("""
        archive shouldNotContainSource """reader\s*\.\s*useEntries"""

        // Not asserted: that a page stream really is read after the close and refused (the seam tests
        // cover that), nor that `recycle()` runs on the main thread.
    }

    /**
     * The EPUB loader's guarded reads: each archive step of the spine enumeration (the container
     * header, the package document, and **one** spine page's xhtml per call) and the teardown.
     * Reverting to the pre-fix single hold — `archiveReads.read { reader.getImagesFromPages() }`,
     * which opens the container, the package document and every spine page inside one lock — fails
     * here.
     */
    @Test
    fun `the epub loader enumerates and closes only through the read guard`() {
        val epub = loader("EpubPageLoader.kt")

        val pages = functionBody(epub, "override suspend fun getPages()")
        pages shouldContainSource """archiveReads\s*\.\s*read\s*\{\s*reader\s*\.\s*getPackageHref\(\s*\)\s*\}"""
        pages shouldContainSource """archiveReads\s*\.\s*read\s*\{\s*reader\s*\.\s*getPackageDocument\("""
        pages shouldContainSource """archiveReads\s*\.\s*read\s*\{\s*reader\s*\.\s*getPageImagePaths\("""
        pages shouldContainSource """archiveReads\s*\.\s*readerPage\("""
        // One guarded step per archive read, not one for the whole enumeration.
        occurrences(pages, """archiveReads\s*\.\s*read\s*\{""") shouldBe 3
        // The whole-spine call is gone from the loader: it is a single hold over every page entry.
        epub shouldNotContainSource """reader\s*\.\s*getImagesFromPages\("""
        val recycle = functionBody(epub, "override fun recycle()")
        recycle shouldContainSource """archiveReads\s*\.\s*close\s*\{\s*reader\s*\.\s*close\(\s*\)\s*\}"""
        occurrences(epub, """reader\s*\.\s*close\(\s*\)""") shouldBe 1

        // Not asserted: that each `EpubReader` accessor really reads one entry (it is mocked in
        // `EpubPageLoaderGuardTest`, which measures how long a close waits), nor that a stream is
        // refused after the close.
    }

    /**
     * The seam is the only place a loader's native steps are named: it takes a [GuardedArchiveSource],
     * so it must never reach for a reader (or an `ArchiveInputStream`) itself — that would be a read
     * with no way for the fake in `GuardedArchiveEnumerationTest` to prove the guard was held.
     */
    @Test
    fun `the enumeration seam never touches an archive directly`() {
        val seam = loader("GuardedArchiveEnumeration.kt")

        seam shouldNotContainSource """reader\s*\.\s*close\("""
        seam shouldNotContain "ArchiveInputStream("
        seam shouldNotContain "getInputStream("
        // Every native step it performs goes through the guard, one hold per step.
        occurrences(seam, """\bread\s*\{""") shouldBe 3
    }

    /**
     * The body of the function starting at [signature], found by counting braces from its opening
     * brace, so the guard survives reformatting. (Comments are already gone; the loader bodies have
     * no braces inside string literals, so the count is exact.)
     */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing call site: $signature" }
        val open = source.indexOf('{', start)
        check(open > start) { "no block for: $signature" }

        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        error("unterminated body for: $signature")
    }
}

/** How many times [pattern] occurs in this text (same whitespace-tolerant regex form). */
private fun occurrences(source: String, pattern: String): Int = Regex(pattern).findAll(source).count()

/** [pattern] must occur somewhere in this text. */
private infix fun String.shouldContainSource(pattern: String) {
    if (!Regex(pattern).containsMatchIn(this)) {
        fail("expected the source to contain /$pattern/")
    }
}

/** The mirror: [pattern] must not occur in this text. */
private infix fun String.shouldNotContainSource(pattern: String) {
    if (Regex(pattern).containsMatchIn(this)) {
        fail("expected the source not to contain /$pattern/")
    }
}

/**
 * This source with `//` line comments and slash-star block comments removed, so a needle is only ever
 * matched against code. String literals are copied verbatim (these files have no `//` in one), and
 * the loader files have no raw strings.
 */
private fun String.withoutComments(): String {
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        when {
            c == '"' -> {
                out.append(c)
                i++
                while (i < length && this[i] != '"') {
                    if (this[i] == '\\' && i + 1 < length) {
                        out.append(this[i])
                        i++
                    }
                    out.append(this[i])
                    i++
                }
                if (i < length) {
                    out.append(this[i])
                    i++
                }
            }
            c == '/' && i + 1 < length && this[i + 1] == '/' -> {
                while (i < length && this[i] != '\n') i++
            }
            c == '/' && i + 1 < length && this[i + 1] == '*' -> {
                i += 2
                while (i + 1 < length && !(this[i] == '*' && this[i + 1] == '/')) i++
                i = min(i + 2, length)
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}
// KUN <--
