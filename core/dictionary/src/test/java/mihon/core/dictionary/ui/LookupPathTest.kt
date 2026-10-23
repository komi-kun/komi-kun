// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * T1 gate for [LookupPath], the pure reducer behind the dictionary popup's recursive-lookup tabs.
 *
 * The popup used to *accumulate* every lookup into an ever-growing tab list; it now follows
 * browser-history (path) semantics: tapping a tab only moves the active pointer (the tail stays
 * visible), and performing a new lookup truncates everything after the active entry before
 * appending the new term.
 *
 * The case names mirror the user's own notation:
 * ```
 * Term1 -> Term2 -> [Term3]
 * pressed Term2  =>  Term1 -> [Term2] -> Term3
 * pressed Term4  =>  Term1 -> Term2 -> [Term4]
 * ```
 */
class LookupPathTest {

    private fun pathOf(vararg entries: String) = LookupPath(entries = entries.toList(), activeIndex = entries.lastIndex)

    @Test
    fun `empty path has no active entry and cannot go back`() {
        val path = LookupPath<String>()
        path.entries shouldBe emptyList()
        path.active shouldBe null
        path.canGoBack shouldBe false
    }

    @Test
    fun `start holds a single active entry`() {
        LookupPath<String>().start("Term1") shouldBe pathOf("Term1")
    }

    @Test
    fun `push appends and moves the pointer to the new tail`() {
        LookupPath<String>()
            .start("Term1")
            .push("Term2")
            .push("Term3") shouldBe pathOf("Term1", "Term2", "Term3")
    }

    @Test
    fun `selecting an earlier tab keeps the tail visible`() {
        // Term1 -> Term2 -> [Term3]; pressed Term2 => Term1 -> [Term2] -> Term3
        val path = pathOf("Term1", "Term2", "Term3").select(1)
        path shouldBe LookupPath(listOf("Term1", "Term2", "Term3"), activeIndex = 1)
        path.active shouldBe "Term2"
    }

    @Test
    fun `a new lookup from a moved-back pointer drops the tail and appends`() {
        // ... pressed Term4 => Term1 -> Term2 -> [Term4]
        val path = pathOf("Term1", "Term2", "Term3").select(1).push("Term4")
        path shouldBe pathOf("Term1", "Term2", "Term4")
        path.active shouldBe "Term4"
    }

    @Test
    fun `a new lookup after selecting the first tab keeps only the first entry`() {
        val path = pathOf("Term1", "Term2", "Term3").select(0).push("Term4")
        path shouldBe pathOf("Term1", "Term4")
        path.active shouldBe "Term4"
    }

    @Test
    fun `a new lookup from the deepest tab just appends`() {
        val path = pathOf("Term1", "Term2", "Term3").select(2).push("Term4")
        path shouldBe pathOf("Term1", "Term2", "Term3", "Term4")
    }

    @Test
    fun `selecting the already-active tab is a no-op`() {
        val path = pathOf("Term1", "Term2", "Term3")
        path.select(2) shouldBe path
        path.select(2).activeIndex shouldBe 2
    }

    @Test
    fun `selecting an out-of-range tab is ignored`() {
        val path = pathOf("Term1", "Term2")
        path.select(-1) shouldBe path
        path.select(2) shouldBe path
        path.select(99) shouldBe path
    }

    @Test
    fun `a lookup that returns no results still truncates and becomes the active entry`() {
        // A zero-result lookup is still a frame: the path must record it, not silently skip it.
        data class Frame(val term: String, val results: Int)

        val path = LookupPath(
            entries = listOf(Frame("Term1", 2), Frame("Term2", 1), Frame("Term3", 3)),
            activeIndex = 2,
        ).select(1).push(Frame("Term4", results = 0))

        path.entries.map { it.term } shouldBe listOf("Term1", "Term2", "Term4")
        path.active shouldBe Frame("Term4", 0)
    }

    @Test
    fun `back moves the pointer without dropping the tail`() {
        val path = pathOf("Term1", "Term2", "Term3").back()
        path shouldBe LookupPath(listOf("Term1", "Term2", "Term3"), activeIndex = 1)
        path.canGoBack shouldBe true
    }

    @Test
    fun `back at the first entry is a no-op`() {
        pathOf("Term1", "Term2").select(0).back() shouldBe LookupPath(listOf("Term1", "Term2"), activeIndex = 0)
    }

    @Test
    fun `back then a new lookup drops everything after the target`() {
        val path = pathOf("Term1", "Term2", "Term3").back().push("Term4")
        path shouldBe pathOf("Term1", "Term2", "Term4")
    }

    @Test
    fun `push on an empty path behaves like start`() {
        LookupPath<String>().push("Term1") shouldBe pathOf("Term1")
    }
}
