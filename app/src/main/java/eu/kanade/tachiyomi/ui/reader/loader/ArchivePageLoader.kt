package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.core.archive.ArchiveReader
import tachiyomi.core.common.util.system.ImageUtil
import java.io.File

/**
 * Loader used to load a chapter from an archive file.
 */
internal class ArchivePageLoader(
    private val reader: ArchiveReader,
    // SY -->
    private val context: Context,
    private val readerPreferences: ReaderPreferences,
    // SY <--
) : PageLoader() {
    // KUN -->
    /**
     * Guards every read of [reader]'s mapped buffer against [recycle]'s `ArchiveReader.close()`
     * (which unmaps it): a page stream read from a background thread after the chapter was switched
     * away used to fault in native code. See [ArchiveReadGuard].
     */
    private val archiveReads = ArchiveReadGuard()
    // KUN <--
    // SY -->
    private val mutex = Mutex()
    private val tmpDir = File(context.externalCacheDir, "reader_${reader.archiveHashCode}").also {
        it.deleteRecursively()
    }

    init {
        reader.wrongPassword?.let { wrongPassword ->
            if (wrongPassword) {
                error("Incorrect archive password")
            }
        }
        if (readerPreferences.archiveReaderMode.get() == ReaderPreferences.ArchiveReaderMode.CACHE_TO_DISK) {
            tmpDir.mkdirs()
            // KUN --> one guarded block: these reads run on the loading thread, recycle() may not unmap
            // under them.
            archiveReads.read {
                reader.useEntries { entries ->
                    entries
                        .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                        .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                        .forEach { entry ->
                            File(tmpDir, entry.name.substringAfterLast("/"))
                                .also { it.createNewFile() }
                                .outputStream()
                                .use { output ->
                                    archiveReads.inputStream { reader.getInputStream(entry.name)!! }.use { input ->
                                        input.copyTo(output)
                                    }
                                }
                        }
                }
            }
            // KUN <--
        }
    }
    // SY <--

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        // SY -->
        if (readerPreferences.archiveReaderMode.get() == ReaderPreferences.ArchiveReaderMode.CACHE_TO_DISK) {
            return DirectoryPageLoader(UniFile.fromFile(tmpDir)!!).getPages()
        }
        // SY <--
        // KUN --> guarded: enumerating entries reads the mapped buffer (see [archiveReads]).
        return archiveReads.read {
            reader.useEntries { entries ->
                entries
                    .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                    .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                    .mapIndexed { i, entry ->
                        // SY -->
                        val imageBytesDeferred: Deferred<ByteArray>? =
                            when (readerPreferences.archiveReaderMode.get()) {
                                ReaderPreferences.ArchiveReaderMode.LOAD_INTO_MEMORY -> {
                                    CoroutineScope(Dispatchers.IO).async {
                                        mutex.withLock {
                                            archiveReads.read {
                                                reader.getInputStream(entry.name)!!.buffered().use { stream ->
                                                    stream.readBytes()
                                                }
                                            }
                                        }
                                    }
                                }

                                else -> null
                            }
                        val imageBytes by lazy { runBlocking { imageBytesDeferred?.await() } }
                        // SY <--
                        ReaderPage(i).apply {
                            // SY -->
                            stream = {
                                imageBytes?.copyOf()?.inputStream()
                                    // KUN --> guarded: this stream is read later, from any thread,
                                    // possibly after the chapter was recycled.
                                    ?: archiveReads.inputStream { reader.getInputStream(entry.name)!! }
                                // KUN <--
                            }
                            // SY <--
                            status = Page.State.Ready
                        }
                    }
                    .toList()
            }
        }
        // KUN <--
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        // KUN -->
        // Closing the reader unmaps the archive: wait for the read in flight (one page chunk) and
        // refuse every later one, so no page stream can touch freed memory. See [archiveReads].
        archiveReads.close { reader.close() }
        // KUN <--
        // SY -->
        tmpDir.deleteRecursively()
        // SY <--
    }
}
