// SPDX-License-Identifier: Apache-2.0
// KUN -->
// The post-download OCR truth table. Two independent rules are pinned here:
//  * the queue *metadata* is always updated by the caller (`markChapterReadyForOcr`), so being off
//    must never strand a chapter at WAITING_DOWNLOAD;
//  * the *actions* (auto-queue, worker start) never happen while the combined dictionary/OCR opt-in
//    is off — including for a task the metadata just promoted.
// KUN <--
package eu.kanade.tachiyomi.data.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OcrRuntimePolicyTest {

    @Test
    fun `the queue metadata is refreshed even while the feature is off`() {
        // The invariant behind `Downloader`'s always-called `markChapterReadyForOcr`: gating the
        // actions must never gate the promotion, or a chapter queued before the switch went off is
        // stranded at WAITING_DOWNLOAD for good.
        OcrRuntimePolicy.refreshesQueueMetadata(enabled = false) shouldBe true
        OcrRuntimePolicy.refreshesQueueMetadata(enabled = true) shouldBe true
    }

    @Test
    fun `off queues nothing and starts no worker, even when the metadata promoted a task`() {
        // promoted = true is what actually happens while off: the promotion is metadata-only.
        OcrRuntimePolicy.downloadActions(enabled = false, promoted = true, autoOnDownload = true) shouldBe
            DownloadOcrActions(queue = false, startWorker = false)
        OcrRuntimePolicy.downloadActions(enabled = false, promoted = false, autoOnDownload = true) shouldBe
            DownloadOcrActions(queue = false, startWorker = false)
    }

    @Test
    fun `on promotes an already queued chapter by draining it, not by queueing it twice`() {
        OcrRuntimePolicy.downloadActions(enabled = true, promoted = true, autoOnDownload = true) shouldBe
            DownloadOcrActions(queue = false, startWorker = true)
    }

    @Test
    fun `on queues a freshly downloaded chapter only when OCR-on-download is enabled`() {
        OcrRuntimePolicy.downloadActions(enabled = true, promoted = false, autoOnDownload = true) shouldBe
            DownloadOcrActions(queue = true, startWorker = true)
        OcrRuntimePolicy.downloadActions(enabled = true, promoted = false, autoOnDownload = false) shouldBe
            DownloadOcrActions(queue = false, startWorker = false)
    }

    @Test
    fun `the queue run gate follows the switch exactly`() {
        // Used by OcrWorker.doWork (off => success without touching the queue) and by the launch /
        // enable resume in MainActivity (only run while on).
        OcrRuntimePolicy.shouldRunQueue(enabled = false) shouldBe false
        OcrRuntimePolicy.shouldRunQueue(enabled = true) shouldBe true
    }

    @Test
    fun `the launch and rotation resume needs both the switch and runnable work`() {
        // Empty / error-only / waiting-download queue: nothing is started, even while enabled.
        OcrRuntimePolicy.shouldResumeQueue(enabled = true, runnableWork = false) shouldBe false
        // Work waiting while the feature was off: the re-enable (and the next launch) resumes it.
        OcrRuntimePolicy.shouldResumeQueue(enabled = true, runnableWork = true) shouldBe true
        // Off never starts anything, even with runnable work persisted.
        OcrRuntimePolicy.shouldResumeQueue(enabled = false, runnableWork = true) shouldBe false
        OcrRuntimePolicy.shouldResumeQueue(enabled = false, runnableWork = false) shouldBe false
    }
}
