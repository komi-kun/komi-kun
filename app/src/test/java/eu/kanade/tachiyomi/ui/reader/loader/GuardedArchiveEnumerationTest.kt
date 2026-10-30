// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Loader-level tests for the archive read guard's *wiring* (not for the guard itself — see
// `ArchiveReadGuardTest`). A real `ArchiveReader` cannot be enumerated in a unit test (it needs an
// Android `ParcelFileDescriptor` and libarchive), and a read that escapes the guard is a native
// SIGSEGV rather than a failing assertion, so the enumeration was extracted into
// `GuardedArchiveEnumeration.kt` behind `GuardedArchiveSource`. The fake source here asserts, on
// every call, that the calling thread holds the guard's read lock: deleting a `read { }` from the
// enumeration or from the page stream wiring therefore fails a test instead of shipping.
//
// This also pins the granularity finding: `recycle()` closes on the main thread, so it may only
// wait for **one** enumeration step, never for the whole walk.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class GuardedArchiveEnumerationTest {

    /**
     * Fails on the defect this whole seam exists for: a native step (directory open, header read,
     * image check, entry open) called without the guard held would touch an unmapped range on a
     * device. Every method checks it, so a loader that drops one `read { }` fails here.
     */
    @Test
    fun `every enumeration step runs inside the read guard`() {
        val guard = ArchiveReadGuard()
        val source = FakeSource(
            guard = guard,
            names = listOf("a.jpg", "b.txt", "c.png"),
            imageNames = setOf("a.jpg", "c.png"),
        )

        guard.imageEntryNames(source) shouldBe listOf("a.jpg", "c.png")
        source.served.get() shouldBe 3
    }

    /**
     * The lazy page stream is the one the OCR block loader reads long after the chapter was switched
     * away: it must be refused once the archive is closed, not opened over freed memory.
     */
    @Test
    fun `a page stream built by the seam refuses to open after close`() {
        val guard = ArchiveReadGuard()
        val source = FakeSource(guard, listOf("p1.jpg"), setOf("p1.jpg"))
        val page = guard.readerPage(0, "p1.jpg") { source.open(it) }

        page.status shouldBe Page.State.Ready
        page.stream!!().use { it.read() } shouldBe 1

        guard.close { }

        shouldThrow<IOException> { page.stream!!() }
    }

    /** A stream opened *before* the close must refuse its next read: it reads the same mapping. */
    @Test
    fun `a page stream opened before close refuses the later read`() {
        val guard = ArchiveReadGuard()
        val source = FakeSource(guard, listOf("p1.jpg"), setOf("p1.jpg"))
        val stream = guard.readerPage(0, "p1.jpg") { source.open(it) }.stream!!()

        stream.read() shouldBe 1
        guard.close { }

        shouldThrow<IOException> { stream.read() }
    }

    /**
     * `recycle()` (which closes and unmaps) runs on the main thread, so it may only ever wait for
     * **one** enumeration step. Holding the read lock for the whole walk — which is what an
     * `archiveReads.read { reader.useEntries { … } }` does — makes `close()` queue behind every
     * remaining entry: the assertion below then sees the walk serve all of its names before the
     * close returns. With a per-step hold the closer gets in between two steps, so the walk is cut
     * short instead.
     */
    @Test
    fun `close waits for one step, not for the whole walk`() {
        val guard = ArchiveReadGuard()
        val names = (1..40).map { "page$it.jpg" }
        val thirdStep = CountDownLatch(1)
        val source = FakeSource(
            guard = guard,
            names = names,
            imageNames = names.toSet(),
            stepDelayMs = 25,
            onStep = { served -> if (served == 3) thirdStep.countDown() },
        )

        val walk = thread { runCatching { guard.imageEntryNames(source) } }
        thirdStep.await(5, TimeUnit.SECONDS) shouldBe true

        val closeDone = CountDownLatch(1)
        thread {
            guard.close { }
            closeDone.countDown()
        }
        // One 25 ms step (plus scheduling), not the ~39 steps the walk has left.
        closeDone.await(2, TimeUnit.SECONDS) shouldBe true
        // The walk did not get to finish before the closer: it is refused at its next step.
        source.served.get() shouldBeLessThan names.size

        walk.join(5_000)
        walk.isAlive shouldBe false
        guard.isClosed shouldBe true
    }

    /**
     * A fake archive: the buffer is represented by the guard, so "touching the buffer outside the
     * guard" is observable as a failed check instead of a native crash.
     */
    private class FakeSource(
        private val guard: ArchiveReadGuard,
        private val names: List<String>,
        private val imageNames: Set<String>,
        private val stepDelayMs: Long = 0,
        private val onStep: ((Int) -> Unit)? = null,
    ) : GuardedArchiveSource {
        val served = AtomicInteger(0)

        override fun cursor(): ArchiveEntryCursor {
            guard.checkRead("cursor()")
            var index = 0
            return object : ArchiveEntryCursor {
                override fun next(): String? {
                    guard.checkRead("next()")
                    if (stepDelayMs > 0) Thread.sleep(stepDelayMs)
                    val number = index + 1
                    val name = names.getOrNull(index++)
                    if (name != null) {
                        served.incrementAndGet()
                        onStep?.invoke(number)
                    }
                    return name
                }

                // Not checked: freeing the libarchive handle does not read the mapping
                // (see `ArchiveReadGuard.GuardedInputStream.close`).
                override fun close() = Unit
            }
        }

        override fun isImage(name: String): Boolean {
            guard.checkRead("isImage($name)")
            if (stepDelayMs > 0) Thread.sleep(stepDelayMs)
            return name in imageNames
        }

        override fun open(name: String): InputStream? {
            guard.checkRead("open($name)")
            return ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }

        private fun ArchiveReadGuard.checkRead(step: String) {
            check(isHoldingReadLock()) { "$step touched the mapped buffer outside the read guard" }
        }
    }
}
