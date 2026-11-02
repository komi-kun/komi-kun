package eu.kanade.tachiyomi.data.download

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.OrphanedDownloadScanner.OrphanedDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import tachiyomi.core.common.util.system.logcat

/**
 * Owns the application's one orphaned-download scan, so a scan outlives the screen that started it.
 *
 * The screen is only a view of this: leaving it (or navigating anywhere else) does not stop the walk,
 * and opening it again shows whatever this manager is currently holding — a running scan with its
 * progress, the results of the last one, its failure, or the fact that the user stopped it. It runs on
 * the application scope for that reason, not a `viewModelScope`.
 *
 * There is no persisted or background execution: the scan is an in-process walk of the downloads tree,
 * so process death drops it and the next [start] begins from [State.Idle].
 */
@Inject
@SingleIn(AppScope::class)
class OrphanedDownloadScanManager(
    private val scanner: OrphanedDownloadScanner,
    @AppCoroutineScope private val scope: CoroutineScope,
) {

    sealed interface State {
        data object Idle : State

        data class Running(val scanned: Int, val total: Int) : State

        data class Ready(val items: List<OrphanedDownload>) : State

        data class Failed(val message: String? = null) : State

        /**
         * A scan the user stopped: no walk is running, and none is started on its own — not when the
         * screen is re-entered and not when it is recreated by a rotation.
         */
        data object Stopped : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Bumped by every [start] and [cancel], so a scan that was superseded can never publish over the
     * one that replaced it: cancellation is asynchronous, and a progress callback from the outgoing
     * walk would otherwise put [State.Running] back after a [cancel] already returned to
     * [State.Stopped].
     *
     * `@Volatile` because the walk reads it on its own dispatcher while [start] and [cancel] write it
     * on the caller's thread: without a happens-before edge a superseded walk can see a stale value,
     * believe it is still the current one, and publish over the state that replaced it. The writes
     * themselves are serialised by the methods that make them, so `++` cannot lose an increment.
     */
    @Volatile
    private var generation = 0

    /**
     * Starts a scan unless one is already running, and publishes [State.Running] immediately.
     *
     * Synchronised so two starts cannot both pass the running check: the second one would otherwise
     * overwrite [job] and leave the walk the first started with no reference to be cancelled by.
     */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        val run = ++generation
        _state.value = State.Running(0, 0)
        job = scope.launch { scan(run) }
    }

    /**
     * Stops a running scan and reports it as [State.Stopped], which the screen shows as a stopped scan
     * instead of a scan that never ends.
     *
     * With nothing running the current state is left alone: a stray stop must not discard the list a
     * finished scan produced, and must not turn a fresh [State.Idle] into a stop the user never asked
     * for (which would also keep [start] from starting the first scan).
     *
     * Synchronised with [start], so a stop and a start cannot interleave into a walk that is running
     * with no [job] naming it.
     */
    @Synchronized
    fun cancel() {
        val wasRunning = job?.isActive == true
        generation++
        job?.cancel()
        job = null
        if (wasRunning) _state.value = State.Stopped
    }

    private suspend fun scan(run: Int) {
        fun publish(state: State) {
            if (generation == run) _state.value = state
        }

        try {
            val items = scanner.scan { scanned, total -> publish(State.Running(scanned, total)) }
            publish(State.Ready(items))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to scan for orphaned downloads" }
            publish(State.Failed(e.message))
        }
    }
}
