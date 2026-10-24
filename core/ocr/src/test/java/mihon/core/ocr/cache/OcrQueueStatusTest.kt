// SPDX-License-Identifier: Apache-2.0
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
        // Exhaustive on purpose: adding a status without deciding whether a run picks it up is the
        // failure mode this catches.
        val runnable = OcrQueueStatus.entries.filter { it.isRunnable() }
        runnable shouldBe listOf(OcrQueueStatus.PENDING, OcrQueueStatus.PROCESSING)
    }
}
