// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The app-side seam over the persisted OCR queue: "is there work a queue run would pick up?".
//
// Why the store and not `OcrManager.queueState`: the manager's flow starts empty and is only
// refreshed by an enqueue/promotion/retry, so on a cold start it reports an empty queue even when
// `PENDING` tasks are persisted — gating a launch-time resume on it would silently drop them. The
// store is the same source `OcrManager.runPendingQueue` iterates.
//
// **Not main-thread work.** Answering the question deserialises the persisted queue JSON, so callers
// run it off the main thread (the launch/enable resume in `MainActivity` uses `Dispatchers.IO`).
// The status predicate itself lives once, in `:core:ocr` (`hasRunnableStatuses`), and is reached
// through [OcrStore.hasRunnableTasks] so the two cannot drift.
//
// Used only to decide *whether starting a run is worth it* (and therefore whether the worker would
// raise its foreground notification); never to modify anything.
// KUN <--
package eu.kanade.tachiyomi.data.ocr

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import mihon.core.ocr.cache.OcrStore

@Inject
@SingleIn(AppScope::class)
class OcrQueueWorkProbe(
    private val store: OcrStore,
) {
    /** True when the persisted queue holds at least one task a run would process. */
    fun hasRunnableWork(): Boolean = store.hasRunnableTasks()
}
