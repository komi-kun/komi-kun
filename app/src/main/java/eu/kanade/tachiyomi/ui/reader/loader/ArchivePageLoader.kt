package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import com.hippo.unifile.UniFile
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
import mihon.core.archive.ArchiveInputStream
import mihon.core.archive.ArchiveReader
import tachiyomi.core.common.util.system.ImageUtil
import java.io.File
import java.io.InputStream

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

    /**
     * [GuardedArchiveSource] over [reader]. Every method reads the mapped buffer, so it is only ever
     * called from inside [archiveReads] (see [imageEntryNames] and [readerPage]).
     */
    private val archiveSource = object : GuardedArchiveSource {
        override fun cursor(): ArchiveEntryCursor = Cursor(reader)

        override fun isImage(name: String): Boolean =
            ImageUtil.isImage(name) { reader.getInputStream(name)!! }

        override fun open(name: String): InputStream? = reader.getInputStream(name)
    }

    /**
     * [ArchiveEntryCursor] over an [ArchiveInputStream]: opening it maps libarchive onto
     * [ArchiveReader.address], so a single [next] is one `readNextHeader`.
     */
    private class Cursor(reader: ArchiveReader) : ArchiveEntryCursor {
        private val stream = ArchiveInputStream(reader.address, reader.size, reader.encrypted)

        override fun next(): String? {
            while (true) {
                val entry = stream.getNextEntry() ?: return null
                if (entry.isFile) return entry.name
            }
        }

        override fun close() {
            stream.close()
        }
    }
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
            // KUN --> one guarded hold per entry: a `recycle()` racing this extraction waits for one
            // entry's copy, never for the whole archive.
            archiveReads.imageEntryNames(archiveSource)
                .sortedWith { f1, f2 -> f1.compareToCaseInsensitiveNaturalOrder(f2) }
                .forEach { name ->
                    File(tmpDir, name.substringAfterLast("/"))
                        .also { it.createNewFile() }
                        .outputStream()
                        .use { output ->
                            archiveReads.read {
                                archiveSource.open(name)!!.use { input -> input.copyTo(output) }
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
        // KUN --> guarded per entry: enumerating the directory and testing each entry for an image
        // both read the mapped buffer, so each is its own short hold (see [imageEntryNames]); the
        // page streams stay guarded lazily, one read at a time.
        return archiveReads.imageEntryNames(archiveSource)
            .sortedWith { f1, f2 -> f1.compareToCaseInsensitiveNaturalOrder(f2) }
            .mapIndexed { i, name ->
                // SY -->
                val imageBytesDeferred: Deferred<ByteArray>? =
                    when (readerPreferences.archiveReaderMode.get()) {
                        ReaderPreferences.ArchiveReaderMode.LOAD_INTO_MEMORY -> {
                            CoroutineScope(Dispatchers.IO).async {
                                mutex.withLock {
                                    archiveReads.read {
                                        archiveSource.open(name)!!.buffered().use { stream ->
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
                archiveReads.readerPage(i, name) { archiveSource.open(it) }.apply {
                    // KUN --> LOAD_INTO_MEMORY hands out the copy already read above instead of
                    // re-reading the archive per page.
                    if (imageBytesDeferred != null) {
                        stream = { imageBytes!!.copyOf().inputStream() }
                    }
                    // KUN <--
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
        // Closing the reader unmaps the archive: wait for the read in flight (one guarded step —
        // one header read, one entry's bytes or one page copy) and refuse every later one, so no
        // page stream can touch freed memory. See [archiveReads].
        archiveReads.close { reader.close() }
        // KUN <--
        // SY -->
        tmpDir.deleteRecursively()
        // SY <--
    }
}
