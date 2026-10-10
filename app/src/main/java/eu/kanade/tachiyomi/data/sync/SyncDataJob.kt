package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.SyncStatus
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.isOnline
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.app.di.appGraph
import mihon.core.metro.metroGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.domain.category.interactor.GetCategories
import java.util.concurrent.TimeUnit

class SyncDataJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject private lateinit var database: Database

    @Inject private lateinit var syncPreferences: SyncPreferences

    @Inject private lateinit var getCategories: GetCategories

    @Inject private lateinit var backupCreatorFactory: BackupCreator.Factory

    @Inject private lateinit var mangaRestorer: MangaRestorer

    private val notifier by lazy { SyncNotifier(context, graph.securityPreferences, graph.syncStatus) }

    override suspend fun doWork(): Result {
        graph.inject(this)
        if (tags.contains(TAG_AUTO)) {
            if (!context.isOnline()) {
                return Result.retry()
            }
            // Find a running manual worker. If exists, try again later
            if (context.workManager.isRunning(TAG_MANUAL)) {
                return Result.retry()
            }
        }

        // KMK -->
        graph.syncStatus.start()
        // KMK <--

        setForegroundSafely()

        return try {
            SyncManager(
                context = context,
                database = database,
                syncPreferences = syncPreferences,
                getCategories = getCategories,
                backupCreatorFactory = backupCreatorFactory,
                notifier = notifier,
                restorer = mangaRestorer,
                json = graph.json,
            ).syncData()
            Result.success()
            // SY -->
        } catch (e: OutOfMemoryError) {
            logcat(LogPriority.ERROR, e)
            notifier.showSyncError("Not enough memory to sync this library")
            Result.success()
            // SY <--
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            notifier.showSyncError(e.message)
            Result.success() // try again next time
        } finally {
            // KMK -->
            graph.syncStatus.stop()
            // KMK <--
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_SYNC_PROGRESS,
            notifier.showSyncProgress().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        private const val TAG_JOB = "SyncDataJob"
        private const val TAG_AUTO = "$TAG_JOB:auto"
        const val TAG_MANUAL = "$TAG_JOB:manual"

        fun isRunning(context: Context): Boolean {
            return context.workManager.isRunning(TAG_JOB)
        }

        // SY -->
        fun isRunning(workManager: WorkManager): Boolean {
            return workManager.isRunning(TAG_JOB)
        }
        // SY <--

        fun setupTask(context: Context, prefInterval: Int? = null) {
            val syncPreferences = context.appGraph.syncPreferences
            val interval = prefInterval ?: syncPreferences.syncInterval.get()

            if (interval > 0) {
                val request = PeriodicWorkRequestBuilder<SyncDataJob>(
                    interval.toLong(),
                    TimeUnit.MINUTES,
                    10,
                    TimeUnit.MINUTES,
                )
                    .addTag(TAG_JOB)
                    .addTag(TAG_AUTO)
                    .build()

                context.workManager.enqueueUniquePeriodicWork(TAG_AUTO, ExistingPeriodicWorkPolicy.UPDATE, request)
            } else {
                context.workManager.cancelUniqueWork(TAG_AUTO)
            }
        }

        fun startNow(context: Context, manual: Boolean = false) {
            val wm = context.workManager
            if (wm.isRunning(TAG_JOB)) {
                // Already running either as a scheduled or manual job
                return
            }
            val tag = if (manual) TAG_MANUAL else TAG_AUTO
            val request = OneTimeWorkRequestBuilder<SyncDataJob>()
                .addTag(TAG_JOB)
                .addTag(tag)
                .build()
            context.workManager.enqueueUniqueWork(tag, ExistingWorkPolicy.KEEP, request)
        }

        fun stop(context: Context) {
            // KMK -->
            val syncPreferences = context.appGraph.syncPreferences
            val syncEnabled = syncPreferences.isSyncEnabled()
            // KMK <--
            val wm = context.workManager
            val workQuery = WorkQuery.Builder.fromTags(listOf(TAG_JOB, TAG_AUTO, TAG_MANUAL))
                .addStates(listOf(WorkInfo.State.RUNNING))
                .build()
            wm.getWorkInfos(workQuery).get()
                // Should only return one work but just in case
                .forEach {
                    wm.cancelWorkById(it.id)
                    // KMK -->
                    val syncStatus: SyncStatus = context.appGraph.syncStatus
                    runBlocking { syncStatus.stop() }
                    // KMK <--

                    // Re-enqueue cancelled scheduled work
                    if (/* KMK --> */ syncEnabled /* KMK <-- */ && it.tags.contains(TAG_AUTO)) {
                        setupTask(context)
                    }
                }
        }
    }
}
