// KUN -->
// The fork's pager shows paired pages, which upstream's Mihon model does not have: this is the seam between
// upstream's flat PagerItems and the pager adapter/tracker, which see one slot per page turn.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderItem
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import kotlinx.coroutines.delay
import tachiyomi.core.common.util.lang.launchUI

/**
 * The pager's slots: the fork's dual page engine, pairing [PagerItems]' flat list into one slot per page turn.
 *
 * A slot is a [ReaderPage] (or [ChapterTransition]) and, when two pages are shown at once, its partner. The list
 * is in display order, so a right to left pager reads it from the end.
 */
class PagerJoinedItems(
    private val viewer: PagerViewer,
    private val pagerItems: PagerItems,
) {

    /**
     * Paired list of currently set items, in the order the pager shows them.
     */
    var items: MutableList<Pair<ReaderItem, ReaderItem?>> = mutableListOf()
        private set

    // SY -->
    /** Page used to start the shifted pages */
    var pageToShift: ReaderPage? = null

    /** Varibles used to check if config of the pages have changed */
    private var shifted = viewer.shiftDoublePage
    private var doubledUp = viewer.doublePages
    // SY <--

    /** The chapter whose pages are set, for the preload and gap logic. */
    val currentChapter: ReaderChapter?
        get() = pagerItems.currentChapter

    /** The next chapter's transition, kept even while it is not among the items. */
    val nextTransition: ChapterTransition.Next?
        get() = pagerItems.nextTransition

    /** Told after every rebuild of [items], before the position it restores is applied. */
    var onItemsChanged: (() -> Unit)? = null

    /**
     * Rebuilds the slots from [chapters], re-pairing them when the dual page layout changed, and returns where the
     * pager must go next, as a slot position: the flat model answers in pages, which [PagerItemPositions] translates.
     */
    fun setChapters(chapters: ViewerChapters, forceTransition: Boolean, currentPosition: Int): PagerItems.Update {
        // KUN -->
        // [currentPosition] indexes the slots the pager shows, [PagerItems] indexes pages: with double pages those
        // are different numbers for the same place, so the flat model is asked about the page the slot shows.
        val update = PagerItemPositions.setChapters(pagerItems, chapters, forceTransition, items, currentPosition)
        // KUN <--

        var useSecondPage = false
        if (shifted != viewer.shiftDoublePage || (doubledUp != viewer.doublePages && doubledUp)) {
            if (shifted && (doubledUp == viewer.doublePages)) {
                useSecondPage = true
            }
            shifted = viewer.shiftDoublePage
        }
        doubledUp = viewer.doublePages
        setJoinedItems(useSecondPage)

        // KUN --> and back again: the flat model answers with a page index, the pager is moved by slot.
        val position = PagerItemPositions.slotFor(items, pagerItems, update)
        return PagerItems.Update(position, update.insertPage)
        // KUN <--
    }

    /**
     * Adds [newPage], the second half of [currentPage], as its own slot, and returns whether the items changed.
     *
     * [PagerItems] is told of an adjacent chapter's half so its split survives until that chapter is the current
     * one; the current chapter's is only in this list, as it is rebuilt when the chapter changes.
     */
    fun onPageSplit(currentPage: Any?, newPage: InsertPage): Boolean {
        if (currentPage !is ReaderPage) return false

        val currentIndex = items.indexOfFirst { it.first == currentPage }

        // Put aside pages of an adjacent chapter until it becomes the current one, so they are not lost on the change
        if (currentPage.chapter.chapter.id != currentChapter?.chapter?.id) {
            pagerItems.putAsideInsertPage(newPage)
            return false
        }

        val placeAtIndex = when (viewer) {
            is L2RPagerViewer,
            is VerticalPagerViewer,
            -> currentIndex + 1
            else -> currentIndex
        }

        // It will enter a endless cycle of insert pages
        if (viewer is R2LPagerViewer && placeAtIndex - 1 >= 0 && items[placeAtIndex - 1].first is InsertPage) {
            return false
        }

        // Same here it will enter a endless cycle of insert pages
        if (items[placeAtIndex].first is InsertPage) {
            return false
        }

        items.add(placeAtIndex, newPage to null)
        return true
    }

    fun cleanupPageSplit() {
        items.removeAll { it.first is InsertPage }
    }

    /**
     * The flat items of [pagerItems] in reading order: a right to left pager keeps them in display order, which is
     * the reverse.
     */
    private fun flatItems(): List<ReaderItem> {
        val items = if (viewer is R2LPagerViewer) pagerItems.items.asReversed() else pagerItems.items
        return items.map { it as ReaderItem }
    }

    // SY -->
    private fun setJoinedItems(useSecondPage: Boolean = false) {
        val oldCurrent = items.getOrNull(viewer.pager.currentItem)
        val flat = flatItems()
        if (!viewer.doublePages) {
            // If not in double mode, set up items like before
            flat.forEach { readerItem ->
                if (readerItem is ReaderPage) {
                    readerItem.shiftedPage = false
                }
            }
            items = flat.map { Pair(it, null) }.toMutableList()
            if (viewer is R2LPagerViewer) {
                items.reverse()
            }
        } else {
            val pagedItems = mutableListOf<MutableList<ReaderPage?>>()
            val otherItems = mutableListOf<ReaderItem>()
            pagedItems.add(mutableListOf())

            // Step 1: segment the pages and transition pages
            flat.forEach { readerItem ->
                when (readerItem) {
                    is ReaderPage -> {
                        if (pagedItems.last().isNotEmpty() &&
                            pagedItems.last().last()?.chapter?.chapter?.id != readerItem.chapter.chapter.id
                        ) {
                            pagedItems.add(mutableListOf())
                        }
                        pagedItems.last().add(readerItem)
                    }
                    is ChapterTransition -> {
                        otherItems.add(readerItem)
                        pagedItems.add(mutableListOf())
                    }
                }
            }

            val subJoinedItems = mutableListOf<Pair<ReaderItem, ReaderItem?>>()

            // Step 2: run through each set of pages
            pagedItems.forEach { items ->
                items.forEach { it?.shiftedPage = false }

                // Step 3: If pages have been shifted,
                if (viewer.shiftDoublePage) {
                    val index = items.indexOf(pageToShift)
                    // Go from the current page and work your way back to the first page,
                    // or the first page that's a full page.
                    // This is done in case user tries to shift a page after a full page
                    val fullPageBeforeIndex = if (index > -1) {
                        items.take(index).indexOfLast { it?.fullPage == true }
                    } else {
                        -1
                    }.coerceAtLeast(0)

                    // Add a shifted page to the first place there isnt a full page
                    run loop@{
                        (fullPageBeforeIndex until items.size).forEach {
                            if (items[it]?.fullPage == false) {
                                items[it]?.shiftedPage = true
                                return@loop
                            }
                        }
                    }
                }

                // Step 4: Add blanks for chunking
                var itemIndex = 0
                while (itemIndex < items.size) {
                    val currentItem = items[itemIndex]
                    currentItem?.isolatedPage = false
                    if (currentItem?.fullPage == true || currentItem?.shiftedPage == true) {
                        // Add a 'blank' page after each full page. It will be used when chunked to solo a page
                        items.add(itemIndex + 1, null)
                        if (
                            currentItem.fullPage &&
                            itemIndex > 0 &&
                            items[itemIndex - 1] != null &&
                            (itemIndex - 1) % 2 == 0
                        ) {
                            // If a page is a full page, check if the previous page needs to be isolated
                            // we should check if it's an even or odd page, since even pages need shifting
                            // For example if Page 1 is full, Page 0 needs to be isolated
                            // No need to take account shifted pages, because null additions should
                            // always have an odd index in the list
                            items[itemIndex - 1]?.isolatedPage = true
                            items.add(itemIndex, null)
                            itemIndex++
                        }
                        itemIndex++
                    }
                    itemIndex++
                }

                // Step 5: chunk em
                if (items.isNotEmpty()) {
                    subJoinedItems.addAll(items.chunked(2).map { Pair(it.first()!!, it.getOrNull(1)) })
                }

                otherItems.getOrNull(pagedItems.indexOf(items))?.let {
                    subJoinedItems.add(Pair(it, null))
                }
            }

            if (viewer is R2LPagerViewer) {
                subJoinedItems.reverse()
            }

            items = subJoinedItems
        }

        onItemsChanged?.invoke()

        // Step 6: Move back to our previous page or transition page
        // The listener is likely off around now, but either way when shifting or doubling,
        // we need to set the page back correctly
        // We will however shift to the first page of the new chapter if the last page we were are
        // on is not in the new chapter that has loaded
        val newPage = when {
            oldCurrent?.first is ReaderPage &&
                (oldCurrent.first as ReaderPage).chapter != currentChapter &&
                (oldCurrent.second as? ChapterTransition)?.from != currentChapter ->
                flat.find { it is ReaderPage && it.chapter == currentChapter }
            useSecondPage -> oldCurrent?.second ?: oldCurrent?.first
            else -> oldCurrent?.first ?: return
        }

        val index = when {
            newPage is ChapterTransition && items.none { it.first == newPage || it.second == newPage } -> {
                val filteredPages = items.filter {
                    it.first is ReaderPage &&
                        (it.first as ReaderPage).chapter == newPage.to
                }
                val page = if (newPage is ChapterTransition.Next) {
                    filteredPages.minByOrNull { (it.first as ReaderPage).index }?.first
                } else {
                    filteredPages.maxByOrNull { (it.first as ReaderPage).index }?.first
                }
                items.indexOfFirst { it.first == page || it.second == page }
            }
            else -> items.indexOfFirst { it.first == newPage || it.second == newPage }
        }

        viewer.pager.setCurrentItem(index, false)
    }

    fun splitDoublePages(current: ReaderPage) {
        val oldCurrent = items.getOrNull(viewer.pager.currentItem)
        val oldSecondPage = oldCurrent?.second as? ReaderPage
        val oldFirstPage = oldCurrent?.first as? ReaderPage
        val oldPage = oldSecondPage ?: oldFirstPage

        setJoinedItems(oldSecondPage == current || (current.index + 1) < (oldPage?.index ?: 0))

        // The listener may be removed when we split a page, so the ui may not have updated properly
        // This case usually happens when we load a new chapter and the first 2 pages need to split og
        viewer.scope.launchUI {
            delay(100)
            viewer.onPageChange(viewer.pager.currentItem)
        }
    }
    // SY <--
}

// KUN -->
/**
 * Translates between the two index spaces the fork's pager keeps: a slot of [PagerJoinedItems], one per page turn,
 * and a flat item of [PagerItems], one per page. They are the same number only while a slot holds a single page, so
 * with double pages a slot index and a page index name different places, and handing one to the other leaves the
 * pager on another spread - or in another chapter.
 *
 * Both directions are here rather than at the call sites so the flat model can be driven, and its answer translated,
 * without a live [PagerViewer].
 */
internal object PagerItemPositions {

    /**
     * Rebuilds [pagerItems] for [chapters] while the pager is on [slot] of [slots], asking the flat model about the
     * page that slot shows rather than about its index.
     */
    fun setChapters(
        pagerItems: PagerItems,
        chapters: ViewerChapters,
        forceTransition: Boolean,
        slots: List<Pair<ReaderItem, ReaderItem?>>,
        slot: Int,
    ): PagerItems.Update = pagerItems.setChapters(
        chapters,
        forceTransition,
        flatPositionOf(slots, pagerItems.items, slot),
    )

    /**
     * The slot to move the pager to for [update]: the one holding the page the flat model followed, or null when no
     * slot holds it, where the pager stays where it is.
     */
    fun slotFor(
        slots: List<Pair<ReaderItem, ReaderItem?>>,
        pagerItems: PagerItems,
        update: PagerItems.Update,
    ): Int? = update.position?.let { slotOf(slots, pagerItems.items, it) }

    /**
     * The flat position of the page [slot] shows - the first of its pair, which is what the pager restores to.
     * Falls back to -1, "no item", when that page is not in [flat], so a stale index cannot follow another page.
     */
    private fun flatPositionOf(slots: List<Pair<ReaderItem, ReaderItem?>>, flat: List<Any>, slot: Int): Int {
        val item = slots.getOrNull(slot)?.first ?: return -1
        return flat.indexOf(item)
    }

    /** The slot showing the flat item at [flatPosition] in [flat], or null when no slot holds it. */
    private fun slotOf(slots: List<Pair<ReaderItem, ReaderItem?>>, flat: List<Any>, flatPosition: Int): Int? {
        val item = flat.getOrNull(flatPosition) ?: return null
        return slots.indexOfFirst { it.first == item || it.second == item }.takeIf { it >= 0 }
    }
}
// KUN <--
