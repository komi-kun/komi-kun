// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Background runner for the persisted OCR queue.
//
// Chimahon used a WorkManager `CoroutineWorker` for this; `:core:ocr` cannot depend on WorkManager
// (contract §1.1 freezes the module's dependencies), so the queue is driven by a plain coroutine
// job here. An app-side WorkManager worker (or the download pipeline) starts this and observes
// [isRunning]; the manager owns the actual scheduling.
// KUN <--
package mihon.core.ocr.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs the OCR queue on [scope] until it drains or [stop] is called. Starting while a run is active
 * is a no-op.
 */
class OcrJob(
    private val manager: OcrManager,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /**
     * Starts a queue run. [inputFor] resolves a queued task into a runnable [ChapterOcrInput]; see
     * [OcrManager.runPendingQueue].
     */
    fun start(inputFor: suspend (OcrTask) -> ChapterOcrInput?) {
        if (job?.isActive == true) return
        _isRunning.value = true
        job = scope.launch {
            try {
                manager.runPendingQueue(inputFor) { _isRunning.value == false }
            } finally {
                _isRunning.value = false
                job = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _isRunning.value = false
    }
}
