// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The single "there is work a queue run would pick up" predicate (`hasRunnableStatuses`). It backs
// `OcrStore.hasRunnableTasks` — which the app's launch/enable resume and `OcrWorker.doWork` gate on —
// so the definition lives once and is pinned here: getting it wrong either raises the worker's
// foreground notification for an empty/error-only/waiting-download queue, or (worse) silently drops
// the `PENDING` tasks a run exists to drain.
// KUN <--
package mihon.core.ocr.cache

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OcrQueueStatusTest {

    @Test
    fun `only PENDING and PROCESSING count as runnable work`() {
        hasRunnableStatuses(listOf(OcrQueueStatus.PENDING)) shouldBe true
        hasRunnableStatuses(listOf(OcrQueueStatus.PROCESSING)) shouldBe true
        hasRunnableStatuses(listOf(OcrQueueStatus.WAITING_DOWNLOAD)) shouldBe false
        hasRunnableStatuses(listOf(OcrQueueStatus.WAITING_DOWNLOAD, OcrQueueStatus.ERROR)) shouldBe false
        hasRunnableStatuses(listOf(OcrQueueStatus.ERROR, OcrQueueStatus.COMPLETED, OcrQueueStatus.CANCELLED)) shouldBe
            false
        hasRunnableStatuses(emptyList()) shouldBe false
        // One runnable task among others is enough.
        hasRunnableStatuses(listOf(OcrQueueStatus.ERROR, OcrQueueStatus.PENDING)) shouldBe true
    }

    @Test
    fun `every status is classified either runnable or not`() {
        // `isRunnable` is an exhaustive `when` over the enum, so a status added later does not compile
        // until it is classified. This pin states *both* halves, so a wrong classification fails too:
        // `WAITING_DOWNLOAD` drifting into "runnable" would raise the foreground worker for a queue
        // that only waits on downloads, and dropping `PROCESSING` would let a run stop resuming its
        // own in-flight task.
        OcrQueueStatus.entries.filter { it.isRunnable() } shouldBe
            listOf(OcrQueueStatus.PENDING, OcrQueueStatus.PROCESSING)
        OcrQueueStatus.entries.filterNot { it.isRunnable() } shouldBe
            listOf(
                OcrQueueStatus.WAITING_DOWNLOAD,
                OcrQueueStatus.COMPLETED,
                OcrQueueStatus.ERROR,
                OcrQueueStatus.CANCELLED,
            )
    }
}
