// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The post-download OCR hook decisions and the OCR worker's run gate, as pure functions.
//
// Two different rules live here on purpose, because they must not be confused:
//
//  * the queue **metadata** (`OcrManager.markChapterReadyForOcr`) is always maintained, even while
//    the combined dictionary/OCR opt-in is off, so a chapter queued before the user switched the
//    feature off is promoted from `WAITING_DOWNLOAD` to `PENDING` as soon as its download finishes
//    instead of being stranded there;
//  * the **actions** (auto-queue, starting the worker) are gated: while off, nothing is queued and
//    no worker run is started.
//
// Keeping the action truth table here (rather than inline in `Downloader`/`OcrWorker`, whose
// Android construction is not unit-testable) is what makes "promotion does not bypass the switch"
// an executable test. The other half — "metadata does not depend on the switch" — is pinned by
// `OcrManager.markChapterReadyForOcr` being ungated (`OcrManagerOptInTest`) and by the call-site
// guard that `Downloader` calls it unconditionally (`OcrPolicySourceWiringTest`).
// KUN <--
package eu.kanade.tachiyomi.data.ocr

/** What the download pipeline may do after a chapter finished downloading. */
data class DownloadOcrActions(
    /** Queue this chapter for OCR now (`OcrPreferences.autoOnDownload`). */
    val queue: Boolean,
    /** Kick [OcrWorker] so a promoted/queued task drains. */
    val startWorker: Boolean,
)

object OcrRuntimePolicy {

    /**
     * The download hook's action truth table. It is evaluated **after**
     * `OcrManager.markChapterReadyForOcr` was called unconditionally: that promotion is queue
     * metadata, not a policy decision (there is deliberately no `enabled` argument here that could
     * gate it, and `OcrManagerOptInTest` pins that it still works while off). [promoted] is that
     * call's result; [autoOnDownload] is the user's queue-on-download preference.
     */
    fun downloadActions(
        enabled: Boolean,
        promoted: Boolean,
        autoOnDownload: Boolean,
    ): DownloadOcrActions = when {
        // Off: the metadata was already updated, but nothing may run.
        !enabled -> DownloadOcrActions(queue = false, startWorker = false)
        // A task queued earlier is now ready: it is already in the queue, just drain it.
        promoted -> DownloadOcrActions(queue = false, startWorker = true)
        autoOnDownload -> DownloadOcrActions(queue = true, startWorker = true)
        else -> DownloadOcrActions(queue = false, startWorker = false)
    }

    /**
     * Whether a queue run may be started ([OcrWorker.start] / `OcrWorker.doWork` / the launch-time
     * resume). Off means: no run, and the persisted queue is left untouched for when it is on.
     */
    fun shouldRunQueue(enabled: Boolean): Boolean = enabled

    /**
     * Whether to kick a queue run now: the feature must be on **and** there must be work a run would
     * process. This is what keeps a launch/rotation from starting a foreground worker on an empty
     * queue, while still resuming a promoted `PENDING` task after the switch is turned back on.
     *
     * [runnableWork] comes from `OcrQueueWorkProbe` → `OcrStore.hasRunnableTasks`, i.e. the one
     * predicate in `:core:ocr` (`hasRunnableStatuses`); this policy only composes it with the switch.
     */
    fun shouldResumeQueue(enabled: Boolean, runnableWork: Boolean): Boolean = enabled && runnableWork
}
