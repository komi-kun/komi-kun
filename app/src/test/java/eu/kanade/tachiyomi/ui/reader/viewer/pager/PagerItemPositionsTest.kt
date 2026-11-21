// KUN -->
package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderItem
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

/**
 * The two index spaces the fork's pager keeps: a slot of [PagerJoinedItems], which is what the pager is indexed by,
 * and a flat item of [PagerItems], which is what the flat model answers in. With double pages they are different
 * numbers for the same place, so a slot index handed to [PagerItems] names a page of another spread - or of another
 * chapter - and the flat model's answer applied as a slot lands on the wrong one.
 *
 * The slot lists here are the pairing [PagerJoinedItems] builds for these pages, written out by hand: building them
 * needs a live [PagerViewer], so the pairing itself is not exercised, only the translation over it.
 */
class PagerItemPositionsTest {

    /**
     * Right to left, double pages, the next chapter loaded: the flat list is `[B1, B0, A3, A2, A1, A0, Prev]` and
     * the slots are `[(B0, B1), (A2, A3), (A0, A1), Prev]`, so flat index 1 is B0 while slot 1 shows A2 and A3.
     */
    @Test
    fun `a slot is followed by the page it shows and found again by it`() {
        val (current, next) = loadedCurrentAndNext()
        val items = PagerItems(isReversed = true)
        items.setChapters(ViewerChapters(current, prevChapter = null, nextChapter = next), false, 0)

        assertEquals(
            listOf("page 1 of 2", "page 0 of 2", "page 3 of 1", "page 2 of 1", "page 1 of 1", "page 0 of 1", "Prev"),
            items.items.describe(),
        )
        val slots = loadedSlots(current, next)
        assertEquals(
            listOf("page 0 of 2 + page 1 of 2", "page 2 of 1 + page 3 of 1", "page 0 of 1 + page 1 of 1", "Prev"),
            slots.describeSlots(),
        )

        // The flat model's answer comes back as a slot: flat indices 3 and 2 are both in slot 1, while flat index 1
        // is B0, in slot 0 - a flat position is not a slot index.
        assertEquals(1, PagerItemPositions.slotFor(slots, items, PagerItems.Update(3, null)))
        assertEquals(1, PagerItemPositions.slotFor(slots, items, PagerItems.Update(2, null)))
        assertEquals(0, PagerItemPositions.slotFor(slots, items, PagerItems.Update(1, null)))
    }

    /**
     * The same translation across a real [PagerItems] rebuild: loading the next chapter moves the page the slot
     * shows from flat index 2 to 3, and following the slot's own page keeps the pager on it. Following the slot
     * index instead follows A3 - the other half of the same pair - so the flat model answers about another page.
     */
    @Test
    fun `the page a double page slot shows is the one the flat model follows across a chapter load`() {
        val (current, next) = chapterAndUnloadedNext()
        val items = PagerItems(isReversed = true)
        items.setChapters(ViewerChapters(current, prevChapter = null, nextChapter = next), false, 0)

        assertEquals(
            listOf("Next", "page 3 of 1", "page 2 of 1", "page 1 of 1", "page 0 of 1", "Prev"),
            items.items.describe(),
        )
        val oldSlots = unloadedSlots(current)
        val slot = 1
        assertSame(current.pages!![2], oldSlots[slot].first)

        next.state = ReaderChapter.State.Loaded(pageList(next, 2))
        val update = PagerItemPositions.setChapters(
            items,
            ViewerChapters(current, prevChapter = null, nextChapter = next),
            forceTransition = false,
            slots = oldSlots,
            slot = slot,
        )

        // The flat model followed the page the slot was showing, not the other half of the pair.
        val followed = checkNotNull(update.position)
        assertEquals(3, followed)
        assertSame(current.pages!![2], items.items[followed])

        // And its answer leads back to the same slot.
        val newSlots = loadedSlots(current, next)
        val restored = PagerItemPositions.slotFor(newSlots, items, update)
        assertEquals(slot, restored)
        assertEquals("page 2 of 1 + page 3 of 1", newSlots[restored!!].describeSlot())
    }

    /** A slot per page: the two index spaces coincide, which is why the single page reader was never off. */
    @Test
    fun `a single page layout follows the same page it always did`() {
        val (current, next) = chapterAndUnloadedNext()
        val items = PagerItems(isReversed = true)
        items.setChapters(ViewerChapters(current, prevChapter = null, nextChapter = next), false, 0)
        val oldSlots = items.items.map { it as ReaderItem to null as ReaderItem? }
        assertSame(current.pages!![3], oldSlots[1].first)

        next.state = ReaderChapter.State.Loaded(pageList(next, 2))
        val update = PagerItemPositions.setChapters(
            items,
            ViewerChapters(current, prevChapter = null, nextChapter = next),
            forceTransition = false,
            slots = oldSlots,
            slot = 1,
        )

        // The next chapter's pages go to the front once reversed, so A3 moves from flat index 1 to 2, and the
        // translated position is the slot holding it - which is that page's own slot on this layout.
        val followed = checkNotNull(update.position)
        assertEquals(2, followed)
        assertSame(current.pages!![3], items.items[followed])

        val newSlots = items.items.map { it as ReaderItem to null as ReaderItem? }
        assertEquals(2, PagerItemPositions.slotFor(newSlots, items, update))
    }

    /** A position with no page behind it must not move the pager anywhere. */
    @Test
    fun `a position with no item behind it resolves to nothing`() {
        val (current, next) = loadedCurrentAndNext()
        val items = PagerItems(isReversed = true)
        items.setChapters(ViewerChapters(current, prevChapter = null, nextChapter = next), false, 0)
        val slots = loadedSlots(current, next)

        assertNull(PagerItemPositions.slotFor(slots, items, PagerItems.Update(items.items.size, null)))
        assertNull(PagerItemPositions.slotFor(slots, items, PagerItems.Update(null, null)))
        // A slot with no page behind it is asked about as -1, and an unknown position has no slot to go to: the
        // pager is not moved, rather than being sent to whichever slot happens to have that index.
        assertNull(PagerItemPositions.slotFor(slots, items, PagerItems.Update(-1, null)))
    }

    /** `[(B0, B1), (A2, A3), (A0, A1), Prev]`: pairs within a chapter, the slots read from the end. */
    private fun loadedSlots(current: ReaderChapter, next: ReaderChapter): List<Pair<ReaderItem, ReaderItem?>> {
        val currentPages = current.pages!!
        val nextPages = next.pages!!
        return listOf(
            nextPages[0] to nextPages[1],
            currentPages[2] to currentPages[3],
            currentPages[0] to currentPages[1],
            ChapterTransition.Prev(current, null) to null,
        )
    }

    /** `[Next, (A2, A3), (A0, A1), Prev]`: the same, with the next chapter not loaded. */
    private fun unloadedSlots(current: ReaderChapter): List<Pair<ReaderItem, ReaderItem?>> {
        val currentPages = current.pages!!
        return listOf(
            ChapterTransition.Next(current, null) to null,
            currentPages[2] to currentPages[3],
            currentPages[0] to currentPages[1],
            ChapterTransition.Prev(current, null) to null,
        )
    }

    private fun List<Any>.describe(): List<String> = map { (it as ReaderItem).describe() }

    private fun List<Pair<ReaderItem, ReaderItem?>>.describeSlots(): List<String> = map { it.describeSlot() }

    private fun Pair<ReaderItem, ReaderItem?>.describeSlot(): String {
        val second = second
        return if (second == null) first.describe() else "${first.describe()} + ${second.describe()}"
    }

    private fun ReaderItem.describe(): String = when (this) {
        is ReaderPage -> "page $index of ${chapter.chapter.id}"
        is ChapterTransition.Prev -> "Prev"
        is ChapterTransition.Next -> "Next"
        is InsertPage -> "insert page $index"
    }

    private fun chapterAndUnloadedNext(): Pair<ReaderChapter, ReaderChapter> {
        val current = chapter(id = 1, number = 2.0, pageCount = 4)
        val next = ReaderChapter(Chapter.create().copy(id = 2, chapterNumber = 1.0))
        return current to next
    }

    private fun loadedCurrentAndNext(): Pair<ReaderChapter, ReaderChapter> {
        val (current, next) = chapterAndUnloadedNext()
        next.state = ReaderChapter.State.Loaded(pageList(next, 2))
        return current to next
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
