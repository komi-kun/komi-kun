package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    // SY -->
    /** Value to check if this page is used to as if it was too wide */
    var shiftedPage: Boolean = false,
    /** Value to check if a page is can be doubled up, but can't because the next page is too wide */
    var isolatedPage: Boolean = false,
    // SY <--
    var stream: (() -> InputStream)? = null,

) : Page(index, url, imageUrl, null), ReaderItem {

    // KUN -->
    /**
     * Size in bytes of the image this page's [stream] reads, or `-1` when the loader cannot know it
     * (an archive entry the reader cannot stat, a chapter directory entry, …).
     *
     * It is part of the upscaling cache identity: [sourceLength] and [sourceLastModified] together
     * make a re-downloaded chapter miss the cache instead of serving an upscale of the previous
     * bytes. It does not change what [stream] reads.
     */
    var sourceLength: Long = -1L

    /**
     * Last-modified time (epoch millis) of the stored source image, or `-1` when unknown. See
     * [sourceLength].
     */
    var sourceLastModified: Long = -1L
    // KUN <--

    open lateinit var chapter: ReaderChapter

    /** Value to check if a page is too wide to be doubled up */
    var fullPage: Boolean = false
        set(value) {
            field = value
            if (value) shiftedPage = false
        }

    /** Set by a viewer before the download; filled during [Page.State.DownloadImage]. */
    @Volatile
    var downloadStream: DownloadStream? = null
}
