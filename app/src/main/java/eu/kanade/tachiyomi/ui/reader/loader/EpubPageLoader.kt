package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.core.archive.EpubReader

/**
 * Loader used to load a chapter from a .epub file.
 */
internal class EpubPageLoader(private val reader: EpubReader) : PageLoader() {

    // KUN -->
    /**
     * An `EpubReader` is a thin wrapper over an `ArchiveReader`, so [recycle]s `close()` unmaps the
     * same mapped buffer and a page stream read afterwards faults in native code exactly like a CBZ
     * one. Guarded for the same reason: `ReaderOcrBlockLoader` reads these page bytes from a
     * background coroutine that can outlive the chapter. See [ArchiveReadGuard].
     */
    private val archiveReads = ArchiveReadGuard()
    // KUN <--

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        // KUN -->
        // One guarded hold per archive read, never one for the whole spine: `getPages` opens the
        // container, the package document and **every** spine page's xhtml through `getInputStream`,
        // and `ChapterLoader.loadChapter` publishes this loader before awaiting `getPages`, so a
        // preloaded neighbour that is recycled mid-enumeration used to enumerate a freed mapping.
        // `recycle()` (which takes the write lock and unmaps, on the main thread) therefore waits for
        // one step — one header/entry read — instead of for a whole EPUB's page list; the spine
        // extraction in between parses an already-read document and touches no archive entry.
        val packageHref = archiveReads.read { reader.getPackageHref() }
        val packageDocument = archiveReads.read { reader.getPackageDocument(packageHref) }
        val spine = reader.getPagesFromDocument(packageDocument)
        val paths = spine.flatMap { page ->
            archiveReads.read { reader.getPageImagePaths(page, packageHref) }
        }
        // KUN <--
        return paths.mapIndexed { i, path ->
            // KUN --> guarded: this stream is read later, from any thread.
            archiveReads.readerPage(i, path) { reader.getInputStream(it) }
            // KUN <--
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        // KUN --> close under the guard: wait for the read in flight, refuse every later one.
        archiveReads.close { reader.close() }
        // KUN <--
    }
}
