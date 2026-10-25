package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
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
        return reader.getImagesFromPages().mapIndexed { i, path ->
            ReaderPage(i).apply {
                // KUN --> guarded: this stream is read later, from any thread.
                stream = { archiveReads.inputStream { reader.getInputStream(path)!! } }
                // KUN <--
                status = Page.State.Ready
            }
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
