package eu.kanade.tachiyomi.ui.reader.viewer.pager

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.viewerItems

/**
 * Items of a pager, where [ViewerChapters] updates are posted, inverted when the pager [isReversed] reads right to
 * left. Wide pages split in two get an [InsertPage] for their second half.
 *
 * KUN --> This is the flat page model. The fork's pager shows *pairs* of pages, and the pairing lives one layer
 * up in [PagerJoinedItems], which reads [items] as its input; see that class for the joined list the adapter and
 * the tracker use.
 */
class PagerItems(private val isReversed: Boolean) {

    /**
     * List of currently set items.
     */
    var items: List<Any> by mutableStateOf(emptyList())
        private set

    /**
     * Holds preprocessed items so they don't get removed when changing chapter
     */
    private var preprocessed: List<InsertPage> = emptyList()

    var nextTransition: ChapterTransition.Next? = null
        private set

    var currentChapter: ReaderChapter? = null
        private set

    /**
     * Updates the items with the given [chapters] while the pager is at [currentPosition], and returns where it must
     * go next.
     */
    fun setChapters(chapters: ViewerChapters, forceTransition: Boolean, currentPosition: Int): Update {
        var insertPageLastPage: InsertPage? = null

        val currPages = chapters.currChapter.pages?.toMutableList()?.also { pages ->
            val lastPage = pages.last()

            // Insert preprocessed pages into current page list. Pages of the adjacent chapters are split too, and
            // placed by their index, so only this chapter's belong here.
            preprocessed.filter { it.chapter.chapter.id == chapters.currChapter.chapter.id }
                .sortedByDescending { it.index }
                .forEach { page ->
                    if (lastPage.index == page.index) {
                        insertPageLastPage = page
                    }
                    pages.add(page.index + 1, page)
                }
        }

        val newItems = viewerItems(chapters, forceTransition, currPages)
        currentChapter = chapters.currChapter
        nextTransition = ChapterTransition.Next(chapters.currChapter, chapters.nextChapter)

        val currentItem = items.getOrNull(currentPosition)

        if (isReversed) {
            newItems.reverse()
        }

        val adjacentChapterIds = setOfNotNull(chapters.prevChapter?.chapter?.id, chapters.nextChapter?.chapter?.id)
        preprocessed = preprocessed.filter { it.chapter.chapter.id in adjacentChapterIds }
        items = newItems

        // The pager only follows the pages it has laid out, so before its first layout it would stay at the same
        // position while pages are added in front, e.g. the next chapter's in right to left, and land in another
        // chapter
        val position = items.indexOf(currentItem).takeIf { it != -1 && it != currentPosition }
        return Update(position, insertPageLastPage)
    }

    // KUN -->
    /**
     * Puts [newPage] aside until its chapter becomes the current one, so a wide page of an adjacent chapter keeps
     * its split when the pager moves into it. The fork's split is pair-aware: [PagerJoinedItems] inserts the page
     * for the current chapter itself, and hands the other chapters' pages here.
     */
    fun putAsideInsertPage(newPage: InsertPage) {
        val chapterId = newPage.chapter.chapter.id
        if (preprocessed.none { it.chapter.chapter.id == chapterId && it.index == newPage.index }) {
            preprocessed = preprocessed + newPage
        }
    }

    // The split itself is pair-aware and lives in [PagerJoinedItems], which owns the joined slots: do not re-add
    // onPageSplit / cleanupPageSplit here, nothing in the adapter would reach them.
    // KUN <--

    /**
     * Where the pager goes after the items change: to [position] to stay on the same item, then to [insertPage] at
     * the end of the chapter, which it would skip otherwise.
     */
    class Update(val position: Int?, val insertPage: InsertPage?)
}
