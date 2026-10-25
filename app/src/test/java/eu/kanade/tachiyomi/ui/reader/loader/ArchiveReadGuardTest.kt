// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The invariant `ArchivePageLoader.recycle()` relies on: `ArchiveReader.close()` unmaps the CBZ, so
// a page read that is still in flight must finish first and every later read must be refused instead
// of touching the mapping. Reading a freed mapping is a native SIGSEGV inside libarchive — no Kotlin
// `catch` sees it, so this cannot be tested through `ArchiveReader` (it needs an Android
// `ParcelFileDescriptor` and a real crash); the ordering contract is what is tested here, with the
// mapped buffer represented by the block that `read` runs.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.loader

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ArchiveReadGuardTest {

    /**
     * The unmap must not happen while a read is inside the buffer: `close` has to wait for it. The
     * guard-less implementation (a plain `reader.close()`) enters `close` immediately and unmaps
     * under the parked read — which is exactly the crash — so `closeRanAfterRead` would be false.
     */
    @Test
    fun `close waits for the read in flight before unmapping`() {
        val guard = ArchiveReadGuard()
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val readFinished = AtomicBoolean(false)
        val closeDone = CountDownLatch(1)
        val closeRanAfterRead = AtomicBoolean(false)

        val reader = thread {
            guard.read {
                readStarted.countDown()
                releaseRead.await(5, TimeUnit.SECONDS)
                readFinished.set(true)
            }
        }
        readStarted.await(5, TimeUnit.SECONDS) shouldBe true

        val closer = thread {
            guard.close { closeRanAfterRead.set(readFinished.get()) }
            closeDone.countDown()
        }
        // The close attempt must block (the read is parked inside the guard) ...
        closeDone.await(200, TimeUnit.MILLISECONDS) shouldBe false

        releaseRead.countDown()
        closeDone.await(5, TimeUnit.SECONDS) shouldBe true

        reader.join(5_000)
        closer.join(5_000)
        // ... and the unmap must have run only after the read left the buffer.
        readFinished.get() shouldBe true
        closeRanAfterRead.get() shouldBe true
        guard.isClosed shouldBe true
    }

    /**
     * After the unmap, a read must be refused **before** it can touch the buffer: the block below
     * stands for the native `archive_read_open1` call over freed memory.
     */
    @Test
    fun `a read after close is refused and never touches the buffer`() {
        val guard = ArchiveReadGuard()
        guard.close { }

        val touched = AtomicBoolean(false)
        shouldThrow<IOException> { guard.read { touched.set(true) } }

        touched.get() shouldBe false
    }

    /**
     * The real consumer is a **lazy** page stream: created while the archive is open, read much later
     * (possibly after a chapter switch). It must keep working while open and refuse after close,
     * without consuming a byte — a stream that silently reads on is the crash.
     */
    @Test
    fun `a page stream created before close refuses to read after it`() {
        val guard = ArchiveReadGuard()
        val inner = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val stream = guard.inputStream { inner }

        stream.read() shouldBe 1

        guard.close { }

        shouldThrow<IOException> { stream.read() }
        // The refused read did not consume the remaining bytes of the (now unmapped) page.
        inner.available() shouldBe 2
    }
}
