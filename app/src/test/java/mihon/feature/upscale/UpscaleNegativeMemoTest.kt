// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The negative memo behind "this page cannot be upscaled, do not ask again": its three guarantees —
// a page is remembered per signature, the memo is bounded, and a page that does have an upscale is
// forgotten again rather than skipped.
// KUN <--
package mihon.feature.upscale

import io.kotest.matchers.shouldBe
import mihon.core.upscale.PageKey
import org.junit.jupiter.api.Test

class UpscaleNegativeMemoTest {

    /**
     * Catches a memo that is not bounded: a reader walks a whole chapter of pages it cannot upscale
     * and the set grows with the chapter, on a session that outlives it (a reader stays open across
     * many chapters).
     */
    @Test
    fun `the memo is bounded and evicts the oldest page`() {
        val memo = UpscaleNegativeMemo(maxEntries = 3)
        val pages = (0..3).map { page(it) }

        pages.take(3).forEach { memo.record(it, SIGNATURE) }
        memo.size shouldBe 3

        memo.record(pages[3], SIGNATURE)
        memo.size shouldBe 3
        // The oldest went; the three most recent are still there, including the newest.
        memo.contains(pages[0], SIGNATURE) shouldBe false
        memo.contains(pages[1], SIGNATURE) shouldBe true
        memo.contains(pages[3], SIGNATURE) shouldBe true
    }

    /**
     * Catches a memo keyed on the page alone: a changed mode/scale/model is a *different question*
     * about the page, so a page that could not be upscaled under the old selection must be asked
     * again under the new one instead of being skipped for the rest of the chapter.
     */
    @Test
    fun `a page is remembered per preference signature`() {
        val memo = UpscaleNegativeMemo()
        val page = page(0)

        memo.record(page, "v1;mode=AUTO;scale=2")
        memo.contains(page, "v1;mode=AUTO;scale=2") shouldBe true
        memo.contains(page, "v1;mode=AUTO;scale=4") shouldBe false
    }

    /** Catches a memo that keeps a page the engine has now upscaled: it would stay unserved. */
    @Test
    fun `a page that has an upscale is forgotten`() {
        val memo = UpscaleNegativeMemo()
        val page = page(0)

        memo.record(page, SIGNATURE)
        memo.forget(page, SIGNATURE)
        memo.contains(page, SIGNATURE) shouldBe false

        memo.record(page, SIGNATURE)
        memo.clear()
        memo.contains(page, SIGNATURE) shouldBe false
        memo.size shouldBe 0
    }

    /** Catches a key that ignores part of the page identity: a re-download must be asked again. */
    @Test
    fun `a page with different source bytes is a different page`() {
        val memo = UpscaleNegativeMemo()
        memo.record(page(0, length = 10L), SIGNATURE)

        memo.contains(page(0, length = 10L), SIGNATURE) shouldBe true
        memo.contains(page(0, length = 11L), SIGNATURE) shouldBe false
        memo.contains(page(1, length = 10L), SIGNATURE) shouldBe false
    }

    private fun page(index: Int, length: Long = 10L) = PageKey(
        sourceId = -1L,
        mangaId = 2L,
        chapterId = 7L,
        pageIndex = index,
        sourceLength = length,
        sourceLastModified = 20L,
    )

    private companion object {
        const val SIGNATURE = "v1;mode=AUTO;scale=2;bucket=1600;model=x"
    }
}
