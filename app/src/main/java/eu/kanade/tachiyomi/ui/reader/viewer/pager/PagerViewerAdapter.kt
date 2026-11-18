package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderItem
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import tachiyomi.core.common.util.system.logcat

/**
 * Pager adapter used by this [viewer] to where [ViewerChapters] updates are posted.
 */
class PagerViewerAdapter(
    private val viewer: PagerViewer,
    // KUN --> the fork's slots are pairs, see [PagerJoinedItems]
    private val joinedItems: PagerJoinedItems,
    // KMK -->
    @ColorInt private val seedColor: Int? = null,
    // KMK <--
    // KUN <--
) : ViewPagerAdapter() {

    /**
     * Paired list of currently set items.
     */
    val items: MutableList<Pair<ReaderItem, ReaderItem?>>
        get() = joinedItems.items

    /**
     * Context that has been wrapped to use the correct theme values based on the
     * current app theme and reader background color
     */
    private var readerThemedContext = viewer.activity.createReaderThemeContext()

    init {
        // SY: the joined list is rebuilt by [PagerJoinedItems] itself, so it tells this adapter to
        // notify the pager before it restores the item it was on.
        joinedItems.onItemsChanged = ::notifyDataSetChanged
    }

    /**
     * Updates this adapter with the given [chapters]. It handles setting a few pages of the
     * next/previous chapter to allow seamless transitions and inverting the pages if the viewer
     * has R2L direction.
     */
    fun setChapters(chapters: ViewerChapters, forceTransition: Boolean) {
        val update = joinedItems.setChapters(chapters, forceTransition, viewer.pager.currentItem)

        update.position?.let { viewer.pager.setCurrentItem(it, false) }

        // Will skip insert page otherwise
        update.insertPage?.let(viewer::moveToPage)
    }

    /**
     * Returns the amount of items of the adapter.
     */
    override fun getCount(): Int {
        return items.size
    }

    /**
     * Creates a new view for the item at the given [position].
     */
    override fun createView(container: ViewGroup, position: Int): View {
        val item = items[position].first
        val item2 = items[position].second
        return when (item) {
            is ReaderPage -> PagerPageHolder(
                readerThemedContext,
                viewer,
                item,
                item2 as? ReaderPage,
                // KMK -->
                seedColor = seedColor,
                // KMK <--
            )
            is ChapterTransition -> PagerTransitionHolder(
                readerThemedContext,
                viewer,
                item,
                // KMK -->
                seedColor = seedColor,
                // KMK <--
            )
            // SY --> else -> throw NotImplementedError("Holder for ${item.javaClass} not implemented") SY <--
        }
    }

    /**
     * Returns the current position of the given [view] on the adapter.
     */
    override fun getItemPosition(view: Any): Int {
        if (view is PositionableView) {
            val position = items.indexOf(view.item)
            if (position != -1) {
                return position
            } else {
                logcat { "Position for ${view.item} not found" }
            }
        }
        return POSITION_NONE
    }

    fun onPageSplit(currentPage: Any?, newPage: InsertPage) {
        if (joinedItems.onPageSplit(currentPage, newPage)) {
            notifyDataSetChanged()
        }
    }

    fun cleanupPageSplit() {
        joinedItems.cleanupPageSplit()
        notifyDataSetChanged()
    }

    fun refresh() {
        readerThemedContext = viewer.activity.createReaderThemeContext()
    }

    // SY -->
    fun splitDoublePages(current: ReaderPage) {
        joinedItems.splitDoublePages(current)
    }
    // SY <--
}
