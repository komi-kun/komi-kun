// KUN -->
package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

/**
 * The flat page model, which the fork's paired pager still builds its slots from: the reading direction it
 * reverses for, and the position it reports when the items change under the pager.
 *
 * The last part is the one with teeth. The pager only follows the pages it has laid out, so before its first
 * layout it stays at the same position while pages are added in front of it - which is exactly what loading the
 * next chapter does in right to left. Without the remap the reader lands in another chapter.
 */
class PagerItemsTest {

    @Test
    fun `a right to left pager reverses the items and a left to right one does not`() {
        val chapters = ViewerChapters(chapter(id = 1, number = 3.0, pageCount = 3), prevChapter = null, nextChapter = null)

        val leftToRight = PagerItems(isReversed = false)
        val ltrUpdate = leftToRight.setChapters(chapters, forceTransition = false, currentPosition = 0)
        assertEquals(
            listOf("Prev", "page 0 of 1", "page 1 of 1", "page 2 of 1", "Next"),
            leftToRight.items.describe(),
        )
        // Nothing was there before, so there is no item to follow and the pager is not moved.
        assertNull(ltrUpdate.position)
        assertNull(ltrUpdate.insertPage)

        val rightToLeft = PagerItems(isReversed = true)
        rightToLeft.setChapters(chapters, forceTransition = false, currentPosition = 0)
        assertEquals(
            listOf("Next", "page 2 of 1", "page 1 of 1", "page 0 of 1", "Prev"),
            rightToLeft.items.describe(),
        )
    }

    @Test
    fun `a right to left pager follows its page when the next chapter is added in front`() {
        val current = chapter(id = 1, number = 2.0, pageCount = 3)
        val next = ReaderChapter(Chapter.create().copy(id = 2, chapterNumber = 1.0))
        val items = PagerItems(isReversed = true)

        // The next chapter is not loaded yet, so it is only its transition that is among the items.
        items.setChapters(ViewerChapters(current, prevChapter = null, nextChapter = next), false, 0)
        assertEquals(
            listOf("Next", "page 2 of 1", "page 1 of 1", "page 0 of 1", "Prev"),
            items.items.describe(),
        )

        val page = items.items[1]
        assertSame(current.pages!![2], page)

        // The chapter loads: its pages go to the end of the flat list, i.e. to the front once reversed, so the
        // page the pager was on moves from index 1 to index 2.
        next.state = ReaderChapter.State.Loaded(pageList(next, 2))
        val update = items.setChapters(ViewerChapters(current, prevChapter = null, nextChapter = next), false, 1)

        assertEquals(
            listOf("page 1 of 2", "page 0 of 2", "page 2 of 1", "page 1 of 1", "page 0 of 1", "Prev"),
            items.items.describe(),
        )
        assertEquals(2, update.position)
        assertSame(page, items.items[update.position!!])
    }

    /** The items' shape, so an assertion reads as the order on screen and not as a class dump. */
    private fun List<Any>.describe(): List<String> = map {
        when (it) {
            is ReaderPage -> "page ${it.index} of ${it.chapter.chapter.id}"
            is ChapterTransition.Prev -> "Prev"
            is ChapterTransition.Next -> "Next"
            else -> it.toString()
        }
    }

    private fun chapter(id: Long, number: Double, pageCount: Int): ReaderChapter {
        val chapter = ReaderChapter(Chapter.create().copy(id = id, chapterNumber = number))
        chapter.state = ReaderChapter.State.Loaded(pageList(chapter, pageCount))
        return chapter
    }

    private fun pageList(chapter: ReaderChapter, count: Int): List<ReaderPage> =
        (0 until count).map { index ->
            ReaderPage(index = index, url = "page$index").apply { this.chapter = chapter }
        }
}
// KUN <--
