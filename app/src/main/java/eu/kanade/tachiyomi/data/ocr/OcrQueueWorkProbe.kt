// SPDX-License-Identifier: Apache-2.0
// KUN -->
// Reads the **persisted** OCR queue metadata to answer "is there work a queue run could pick up?".
//
// Why the store and not `OcrManager.queueState`: the manager's flow starts empty and is only
// refreshed by an enqueue/promotion/retry, so on a cold start it reports an empty queue even when
// `PENDING` tasks are persisted — gating a launch-time resume on it would silently drop them. The
// store is the same source `OcrManager.runPendingQueue` iterates, so this is the authoritative
// answer, and it is a cheap in-memory SharedPreferences read.
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
    fun hasRunnableWork(): Boolean = OcrRuntimePolicy.hasRunnableWork(store.getAll().map { it.status })
}
