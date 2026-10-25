// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The post-download OCR truth table. The *actions* (auto-queue, worker start) never happen while
// the combined dictionary/OCR opt-in is off — including for a task the metadata just promoted. The
// companion rule (*the queue metadata is always updated by the caller*, so being off never strands a
// chapter at WAITING_DOWNLOAD) has no policy gate by design and is pinned elsewhere:
// `OcrManagerOptInTest` (promotion works while off) and `OcrPolicySourceWiringTest` (`Downloader`
// calls `markChapterReadyForOcr` unconditionally).
// KUN <--
package eu.kanade.tachiyomi.data.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OcrRuntimePolicyTest {

    @Test
    fun `off queues nothing and starts no worker, even though the metadata promoted a task`() {
        // promoted = true is what actually happens while off: the promotion is metadata-only. The
        // invariant it serves — `markChapterReadyForOcr` runs even while the feature is off, so a
        // chapter queued before the switch went off is not stranded at WAITING_DOWNLOAD — now has no
        // policy gate to pin (there is no `enabled` argument that could gate it): it is asserted
        // behaviourally in `OcrManagerOptInTest` ("download promotion still updates queue metadata
        // while disabled") and as a call-site guard in `OcrPolicySourceWiringTest`.
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

    @Test
    fun `the upgrade migration fires only for an already-on install with superseded models`() {
        // The defect: the readiness gate noticed a superseded/legacy install, but only the Settings
        // row ever read it, so an upgraded install kept running the previous detector. The migration
        // must fire for exactly those installs...
        OcrRuntimePolicy.shouldMigrateModels(
            enabled = true,
            installedRelease = "ocr-manga-v3",
            modelFilesPresent = true,
            selectedRelease = "ocr-manga-v4",
        ) shouldBe true
        // ...including a pre-marker install (bytes on disk, nothing recorded)...
        OcrRuntimePolicy.shouldMigrateModels(
            enabled = true,
            installedRelease = null,
            modelFilesPresent = true,
            selectedRelease = "ocr-manga-v4",
        ) shouldBe true
        // ...and nothing else.
        // Already the selected release: an ordinary later launch never re-downloads.
        OcrRuntimePolicy.shouldMigrateModels(
            enabled = true,
            installedRelease = "ocr-manga-v4",
            modelFilesPresent = true,
            selectedRelease = "ocr-manga-v4",
        ) shouldBe false
        // Fresh install: no marker, no files — the manual download must stay the only way in.
        OcrRuntimePolicy.shouldMigrateModels(
            enabled = true,
            installedRelease = null,
            modelFilesPresent = false,
            selectedRelease = "ocr-manga-v4",
        ) shouldBe false
        // Feature off: never, whatever is installed (the switch gates every other OCR action too).
        OcrRuntimePolicy.shouldMigrateModels(
            enabled = false,
            installedRelease = "ocr-manga-v3",
            modelFilesPresent = true,
            selectedRelease = "ocr-manga-v4",
        ) shouldBe false
        OcrRuntimePolicy.shouldMigrateModels(
            enabled = false,
            installedRelease = null,
            modelFilesPresent = true,
            selectedRelease = "ocr-manga-v4",
        ) shouldBe false
    }
}
