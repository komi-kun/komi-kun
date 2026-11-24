package komikun.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.util.system.workManager
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.app.di.AppGraph
import mihon.app.di.appGraph
import mihon.core.metro.metroGraph
import java.util.concurrent.TimeUnit

/**
 * Runs [FilteredDownloadCleanupService] once per hour, plus once on demand when the setting is turned
 * on ([enqueueImmediate]).
 *
 * The service is a plain class rather than worker logic so the sweep can be unit-tested with fakes;
 * this worker only owns scheduling and the cheap "the feature is off" exit. A plain reflected worker
 * is enough here: `KomikunWorkerFactory` returns `null` for anything that is not `OcrWorker`, so
 * WorkManager's default factory builds this one from its `(Context, WorkerParameters)` constructor.
 *
 * The per-run work is bounded by the service (at most
 * [FilteredDownloadCleanupService.DISCOVERY_BATCH] mangas for new hidden chapters and
 * [FilteredDownloadCleanupService.EXPIRY_BATCH] for due queue rows), which is why a one-hour period
 * with a small flex window is affordable.
 */
class FilteredDownloadCleanupWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    // Reached through the graph rather than through injected fields: that keeps the worker a plain
    // `(Context, WorkerParameters)` worker, so WorkManager's default factory can build it.
    private val graph: AppGraph = context.metroGraph()

    override suspend fun doWork(): Result {
        // Cheap exit before anything else is touched: with the feature off this pass costs one
        // preference read. (`setupTask` also cancels the job in that case; this covers a pass already
        // queued before the user turned it off.)
        if (!graph.downloadPreferences.removeFilteredDownloads.get()) return Result.success()

        // The immediate pass of an off → on toggle is discovery only; the periodic one sweeps both
        // phases.
        val discoverOnly = inputData.getBoolean(KEY_DISCOVER_ONLY, false)

        return withContext(Dispatchers.IO) {
            try {
                graph.filteredDownloadCleanupService.run(discoverOnly)
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Deliberately a success, not a retry: one manga failing is already isolated inside
                // the service, so a failure here is a broken database, and a backoff retry would only
                // hammer it. The next scheduled pass runs within the hour, and the queue rows are
                // still there for it.
                xLogE("Failed to clean up filtered downloads", e)
                Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "FilteredDownloadCleanup"

        /** The one-off retrofit pass of an off → on toggle: its own unique name, not the period's. */
        private const val WORK_NAME_IMMEDIATE = "FilteredDownloadCleanup-immediate"

        /** Marks the immediate request's discovery-only mode for [FilteredDownloadCleanupService.run]. */
        private const val KEY_DISCOVER_ONLY = "discover_only"

        private const val PERIOD_HOURS = 1L

        /** Lets WorkManager run the pass alongside a wakeup it already has, instead of its own. */
        private const val FLEX_MINUTES = 15L

        /**
         * Enqueues or cancels the hourly sweep.
         *
         * The request carries no constraints on purpose: no network (nothing here goes online), no
         * charging, no battery-not-low, and no expedited or foreground run. The pass only reads the
         * database and the in-memory download index and deletes a bounded handful of directories, so
         * it is cheap enough to run whenever the system is already awake — while any of those
         * constraints could defer it indefinitely, leaving a download the user filtered away on disk
         * far past its grace period.
         *
         * Turning the setting off also drops the queue: a re-enable must not resurrect timestamps from
         * before the pause, since the first pass would then delete that whole backlog at once. The
         * delete is launched on the app scope, so this main-thread path never touches the database
         * itself, and it is one indexed `DELETE` on an empty table — cheap enough for the unconditional
         * call in `MainActivity.RearmJobs` at every startup.
         */
        fun setupTask(context: Context, enabled: Boolean) {
            if (!enabled) {
                context.workManager.cancelUniqueWork(TAG)
                context.workManager.cancelUniqueWork(WORK_NAME_IMMEDIATE)
                context.appGraph.filteredDownloadCleanupService.clearPendingAsync()
                return
            }

            val request = PeriodicWorkRequestBuilder<FilteredDownloadCleanupWorker>(
                PERIOD_HOURS,
                TimeUnit.HOURS,
                FLEX_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(Constraints.NONE)
                .addTag(TAG)
                .build()

            context.workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /**
         * Runs one discovery-only pass now, as the retrofit path of an off → on toggle.
         *
         * Downloads that a filter already hid *before* the feature (or the switch) existed have never
         * been stamped, and the next periodic pass is up to an hour away; this stamps them right away
         * so their grace period starts now instead of an hour from now. It is user-initiated and only
         * runs once per toggle (`REPLACE`, so a double tap cannot queue two passes), it carries no
         * constraints and is not expedited (it is one bounded discovery pass over the in-memory index,
         * not urgent work), and it cannot delete: the worker passes
         * [FilteredDownloadCleanupService.run]'s `discoverOnly`, which returns before the expiry phase.
         */
        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<FilteredDownloadCleanupWorker>()
                .setInputData(workDataOf(KEY_DISCOVER_ONLY to true))
                .build()

            context.workManager.enqueueUniqueWork(WORK_NAME_IMMEDIATE, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
