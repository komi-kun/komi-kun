// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The guard-aware half of archive page enumeration, split out of `ArchivePageLoader` so the wiring
// itself is testable. A real `ArchiveReader` needs a `ParcelFileDescriptor` and libarchive, and a
// read that escapes `ArchiveReadGuard` does not fail an assertion — it touches an unmapped range and
// kills the process inside `archive_read_open1`. A fake `GuardedArchiveSource` can therefore prove
// what a real archive cannot: that **every** native step of an enumeration goes through the guard.
//
// Granularity is part of the contract: `recycle()` closes (and unmaps) on the main thread, so it may
// never wait for a whole enumeration. Each step — one header read, one entry's leading bytes, one
// page stream open — is its own short read hold.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import java.io.InputStream

/** A directory walk over one archive: every [next] is a single native header read. */
internal interface ArchiveEntryCursor : AutoCloseable {
    /** The next file entry's name, or null at the end of the archive. */
    fun next(): String?
}

/**
 * The archive operations a page enumeration performs over the mapped buffer. Every method must be
 * called while the matching [ArchiveReadGuard] read lock is held: the production implementation is
 * libarchive (`ArchiveReader`/`EpubReader`), and calling it after `close()` faults in native code.
 */
internal interface GuardedArchiveSource {
    /** Opens a directory cursor over the archive. */
    fun cursor(): ArchiveEntryCursor

    /** True when [name] is an image, decided from its leading bytes (`ImageUtil.isImage`). */
    fun isImage(name: String): Boolean

    /** Opens [name] over the mapped buffer, or null when the archive has no such entry. */
    fun open(name: String): InputStream?
}

/**
 * The names of [source]'s image entries, in archive order.
 *
 * Every call into [source] is a separate read hold, so a concurrent [ArchiveReadGuard.close] waits
 * for **one** native step — one header read, or one entry's leading bytes — instead of for the walk.
 * The walk is still O(entries²) in total (each `getInputStream` rescans the headers from the start)
 * and each `close()` during it can only interrupt it between steps, but it is never O(entries²)
 * *inside* one hold, which is what would freeze the main thread (or ANR) for a large archive.
 *
 * The cursor is closed outside the guard for the same reason a page stream's `close` is: freeing the
 * libarchive handle allocates and frees its own memory and does not read the mapped range.
 */
internal fun ArchiveReadGuard.imageEntryNames(source: GuardedArchiveSource): List<String> {
    val names = read { source.cursor() }.use { cursor ->
        buildList {
            while (true) {
                val name = read { cursor.next() } ?: break
                add(name)
            }
        }
    }
    return names.filter { name -> read { source.isImage(name) } }
}

/**
 * A [ReaderPage] whose lazy stream opens [entryName] through this guard, so a page decoded after the
 * chapter was recycled is refused with a [java.io.IOException] instead of touching freed memory.
 */
internal fun ArchiveReadGuard.readerPage(
    index: Int,
    entryName: String,
    open: (String) -> InputStream?,
): ReaderPage = ReaderPage(index).apply {
    stream = { inputStream { open(entryName)!! } }
    status = Page.State.Ready
}
