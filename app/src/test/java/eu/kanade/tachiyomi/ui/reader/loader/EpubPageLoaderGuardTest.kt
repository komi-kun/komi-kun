// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Loader-level test for the **granularity** of the EPUB enumeration's guarded reads (the guard
// itself is tested in `ArchiveReadGuardTest`, the call-site wiring in `ArchiveLoaderGuardWiringTest`).
//
// `recycle()` closes — and unmaps — the archive from the main thread, so it may only ever wait for
// **one** guarded step. `EpubPageLoader.getPages()` used to be a single hold,
// `archiveReads.read { reader.getImagesFromPages() }`, which opens the container, the package
// document and every spine page's xhtml inside the read lock: switching a chapter of a large EPUB
// then blocked past the whole spine walk. A real `ArchiveReader` needs a `ParcelFileDescriptor` and
// libarchive, so the archive here is a mocked `ArchiveReader` serving a synthetic EPUB, with a
// deliberate delay per spine page entry. The closer must land after one page entry read, not after
// `PAGE_COUNT` of them.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.loader

import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.core.archive.ArchiveReader
import mihon.core.archive.EpubReader
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class EpubPageLoaderGuardTest {

    /**
     * Fails on the one-hold enumeration: with `archiveReads.read { reader.getImagesFromPages() }` the
     * closer queues behind every remaining spine page, so all [PAGE_COUNT] page entries are served
     * before `close()` returns. With one hold per step it waits for the page entry being read, and the
     * walk is refused (closed archive) at its next step.
     */
    @Test
    fun `recycle waits for one spine step, not for the whole walk`() {
        val entries = syntheticEpub(PAGE_COUNT)
        val served = AtomicInteger(0)
        val thirdPageServed = CountDownLatch(1)
        val closeDone = CountDownLatch(1)

        val archive = mockk<ArchiveReader>(relaxed = true)
        every { archive.getInputStream(any()) } answers {
            val name = firstArg<String>()
            val content = entries[name] ?: return@answers null
            if (name.startsWith("OEBPS/p") && name.endsWith(".xhtml")) {
                val number = served.incrementAndGet()
                if (number == 3) thirdPageServed.countDown()
                // One spine page takes long enough that the walk is still running when the closer
                // arrives; a per-step hold then keeps the closer waiting for this one read only.
                Thread.sleep(STEP_MS)
            }
            ByteArrayInputStream(content.toByteArray())
        }
        // `EpubReader` is `Closeable by reader`, so `EpubReader.close()` is this call. It must not
        // block: `recycle()` already holds the write lock while it runs it.
        every { archive.close() } answers { closeDone.countDown() }

        val loader = EpubPageLoader(EpubReader(archive))
        val walk = thread { runCatching { runBlocking { loader.getPages() } } }
        thirdPageServed.await(5, TimeUnit.SECONDS) shouldBe true

        val closeStartedAt = System.nanoTime()
        val closer = thread { loader.recycle() }

        closeDone.await(2, TimeUnit.SECONDS) shouldBe true
        val closeMs = (System.nanoTime() - closeStartedAt) / 1_000_000
        // The walk still had PAGE_COUNT - 3 page entries left when the closer arrived, and it was
        // not allowed to serve them: the enumeration is refused at its next guarded step.
        served.get() shouldBeLessThan PAGE_COUNT
        // Nor did the close queue behind what was left of the walk (one 25 ms step, against ~1 s of
        // remaining steps for the whole spine).
        closeMs shouldBeLessThan ALL_STEPS_MS / 2

        walk.join(5_000)
        closer.join(5_000)
        walk.isAlive shouldBe false
        closer.isAlive shouldBe false
        loader.isRecycled shouldBe true
    }

    /**
     * A flat synthetic EPUB: a container pointing at the package document, and [pageCount]
     * one-image spine pages `OEBPS/pN.xhtml`.
     */
    private fun syntheticEpub(pageCount: Int): Map<String, String> {
        val manifest = (1..pageCount).joinToString("") {
            """<item id="p$it" href="p$it.xhtml" media-type="application/xhtml+xml"/>"""
        }
        val spine = (1..pageCount).joinToString("") { """<itemref idref="p$it"/>""" }
        return buildMap {
            // `EpubReader`'s constructor probes this Windows-shaped name to pick the path separator,
            // before the loader (and its guard) exists: a null answer is the normal Android case.
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container><rootfiles>""" +
                    """<rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            )
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0"?><package><manifest>$manifest</manifest>""" +
                    "<spine>$spine</spine></package>",
            )
            (1..pageCount).forEach {
                put("OEBPS/p$it.xhtml", """<html><body><img src="p$it.jpg"/></body></html>""")
            }
        }
    }

    private companion object {
        private const val PAGE_COUNT = 40
        private const val STEP_MS = 25L
        private const val ALL_STEPS_MS = PAGE_COUNT * STEP_MS
    }
}
