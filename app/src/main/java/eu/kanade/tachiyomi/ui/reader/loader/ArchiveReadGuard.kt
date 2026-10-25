// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Serialises page reads against the archive teardown.
//
// `ArchiveReader` mmaps a CBZ (`Os.mmap`) and `close()` unmaps it (`Os.munmap`), which happens from
// `ArchivePageLoader.recycle()` — on the main thread, as soon as the chapter stops being referenced
// (a chapter switch) or the reader is destroyed. Every page of that chapter is a lazy stream over
// the mapped range (`stream = { reader.getInputStream(entry.name)!! }`), and those streams are read
// from background threads too (the OCR block loader, the Anki screenshot capture, image decoding
// when a viewer decodes off the main thread). A read that lands after the unmap touches an address
// that is no longer mapped: a native `SIGSEGV`/`SEGV_MAPERR` inside libarchive
// (`archive_read_open1`), which no `catch` in Kotlin can handle — the process dies.
//
// The guard closes that window instead of narrowing it: a read holds the read lock from the check to
// the native call, and `close` takes the write lock, so the unmap either happens before a read (which
// is then refused with an `IOException`, and every caller already treats an unreadable page as "not
// available yet") or after it. Cancelling the coroutine that reads cannot do this on its own — a
// thread already inside the JNI call is not cancellable.
//
// Deliberately not a lock around the *stream* lifetime: a stream that is never closed would then
// hang `recycle()` (an ANR). A leaked stream simply fails its next read after close.
//
// What `recycle()`'s wait is bounded by is therefore *one guarded step*, and every caller is
// responsible for keeping its holds short: a buffered read here (one chunk), and in the page loaders
// one directory header read, one entry's leading bytes or one page copy — never a whole
// `getPages()` enumeration or a whole `CACHE_TO_DISK` extraction. See `imageEntryNames`.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.loader

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Read/write guard for one archive's mapped buffer: [read] refuses to touch it once [close] ran, and
 * [close] waits for the read in flight.
 *
 * @see ArchiveReadGuard file header for why this exists.
 */
internal class ArchiveReadGuard {

    private val lock = ReentrantReadWriteLock()

    @Volatile
    var isClosed = false
        private set

    /**
     * Runs [block] while holding the read lock, or throws [IOException] when the archive is already
     * closed. Reentrant (a read may open a stream inside another read).
     */
    fun <T> read(block: () -> T): T {
        val readLock = lock.readLock()
        readLock.lock()
        try {
            if (isClosed) throw IOException("Archive is closed")
            return block()
        } finally {
            readLock.unlock()
        }
    }

    /** Marks the archive closed and runs [block] (the unmap) once no read is in flight. */
    fun close(block: () -> Unit) {
        val writeLock = lock.writeLock()
        writeLock.lock()
        try {
            isClosed = true
            block()
        } finally {
            writeLock.unlock()
        }
    }

    /**
     * Wraps a stream opened over the mapped buffer so **every** read (the lazy page stream is read
     * long after it was created, possibly after a chapter switch) is guarded.
     */
    fun inputStream(open: () -> InputStream): InputStream = GuardedInputStream(read(open), this)

    /**
     * True while the **calling** thread holds the read lock. A test seam: a fake archive source
     * asserts it, so a page enumeration that forgot to go through [read] fails a test instead of
     * touching an unmapped range on a device. Production code never needs it.
     */
    internal fun isHoldingReadLock(): Boolean = lock.readHoldCount > 0

    private class GuardedInputStream(
        private val inner: InputStream,
        private val guard: ArchiveReadGuard,
    ) : InputStream() {
        override fun read(): Int = guard.read { inner.read() }

        override fun read(b: ByteArray, off: Int, len: Int): Int = guard.read { inner.read(b, off, len) }

        override fun available(): Int = guard.read { inner.available() }

        // Not guarded: freeing the libarchive handle allocates/frees its own memory and does not
        // read the mapped range, and a `use { }` must never turn a closed archive into a throw here.
        override fun close() {
            inner.close()
        }
    }
}
