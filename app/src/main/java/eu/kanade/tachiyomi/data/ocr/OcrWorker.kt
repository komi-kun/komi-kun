// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// App-side WorkManager wrapper for the OCR queue.
//
// `:core:ocr`'s `OcrJob` is a plain coroutine runner because the module may not depend on
// WorkManager (contract §1.1); the app wraps it in a [CoroutineWorker] here and supplies the
// resolver that turns a queued chapter into a runnable `ChapterOcrInput` (03 §5). The resolver reads
// page bytes off disk through [DownloadProvider] — never a rendered view bitmap (03 §5.2).
// KUN <--
package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import logcat.LogPriority
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.cache.ChapterImageProvider
import mihon.core.ocr.cache.ChapterOcrInput
import mihon.core.ocr.cache.OcrJob
import mihon.core.ocr.cache.OcrManager
import mihon.core.ocr.cache.OcrTask
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.kun.KUNMR
import java.util.zip.ZipInputStream

/**
 * Drives the persisted OCR queue in a foreground worker. Constructed by the app's `WorkerFactory`
 * with the Metro-built [OcrJob] and [OcrChapterResolver] (contract §3.1, §3.6).
 */
class OcrWorker(
    context: Context,
    workerParams: WorkerParameters,
    private val ocrJob: OcrJob,
    private val resolver: OcrChapterResolver,
) : CoroutineWorker(context, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_OCR) {
            setContentTitle(applicationContext.stringResource(KUNMR.strings.ocr_running))
            setSmallIcon(android.R.drawable.stat_sys_download_done)
            setOngoing(true)
        }.build()
        return ForegroundInfo(
            Notifications.ID_OCR_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        setForegroundSafely()
        return try {
            ocrJob.start { task -> resolver.resolve(task) }
            // `OcrJob.start` sets `isRunning` before launching, so this waits for the run to drain.
            ocrJob.isRunning.first { !it }
            Result.success()
        } catch (e: CancellationException) {
            logcat(LogPriority.WARN, e) { "OcrWorker: cancelled while processing the OCR queue" }
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "OcrWorker: OCR queue failed" }
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "OcrWorker"

        /** Enqueues a queue run (no-op while one is already pending/running). */
        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<OcrWorker>()
                .addTag(TAG)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }

        suspend fun isRunning(context: Context): Boolean = context.workManager.isRunning(TAG)
    }
}

/**
 * Resolves a queued [OcrTask] into a runnable [ChapterOcrInput]: locate the downloaded chapter, pick
 * the OCR language from the active dictionary profile, and open the chapter's page bytes.
 */
@Inject
@SingleIn(AppScope::class)
class OcrChapterResolver(
    private val downloadProvider: DownloadProvider,
    private val getChapter: GetChapter,
    private val getManga: GetManga,
    private val sourceManager: SourceManager,
    private val ocrManager: OcrManager,
    private val dictionaryPreferences: DictionaryPreferences,
) {
    suspend fun resolve(task: OcrTask): ChapterOcrInput? {
        val chapter = getChapter.await(task.chapterId) ?: return null
        val manga = getManga.await(chapter.mangaId) ?: return null
        val source = sourceManager.get(manga.source) ?: return null

        val chapterDir = downloadProvider.findChapterDir(
            chapterName = chapter.name,
            chapterScanlator = chapter.scanlator,
            chapterUrl = chapter.url,
            mangaTitle = manga.ogTitle,
            source = source,
        ) ?: return null

        return ChapterOcrInput(
            key = ocrManager.cacheKey(
                sourceId = manga.source,
                mangaId = chapter.mangaId,
                chapterId = chapter.id,
            ),
            language = resolveLanguage(chapter.mangaId, manga.source),
            source = ocrManager.sourceFor(chapter.mangaId),
            provider = UniFileChapterImageProvider(chapterDir),
        )
    }

    private fun resolveLanguage(mangaId: Long, sourceId: Long): OcrLanguage {
        val profile = dictionaryPreferences.profileResolver.resolve(mangaId = mangaId, sourceId = sourceId)
        return OcrLanguage.entries.firstOrNull { it.bcp47.equals(profile.languageCode, ignoreCase = true) }
            ?: OcrLanguage.JAPANESE
    }
}

/**
 * [ChapterImageProvider] over a downloaded chapter [UniFile]: a directory of image files or a CBZ
 * archive. Reading through `UniFile` keeps it working on storage-access-framework locations where no
 * plain [File] path exists (03 §5.2).
 */
class UniFileChapterImageProvider(
    private val chapterFile: UniFile,
) : ChapterImageProvider {

    private val directoryPages: List<UniFile> = if (chapterFile.isDirectory) {
        chapterFile.listFiles()
            ?.filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
            ?.sortedWith { a, b -> a.name.orEmpty().compareToCaseInsensitiveNaturalOrder(b.name.orEmpty()) }
            .orEmpty()
    } else {
        emptyList()
    }

    private val archiveEntries: List<String> = if (!chapterFile.isDirectory) {
        runCatching {
            chapterFile.openInputStream().use { input ->
                ZipInputStream(input).use { zip ->
                    generateSequence { zip.nextEntry }
                        .filter { !it.isDirectory && ImageUtil.isImage(it.name) }
                        .map { it.name }
                        .sortedWith { a, b -> a.compareToCaseInsensitiveNaturalOrder(b) }
                        .toList()
                }
            }
        }.getOrDefault(emptyList())
    } else {
        emptyList()
    }

    override val pageCount: Int
        get() = if (chapterFile.isDirectory) directoryPages.size else archiveEntries.size

    override fun pageBytes(pageIndex: Int): ByteArray? = if (chapterFile.isDirectory) {
        directoryPages.getOrNull(pageIndex)?.let { page ->
            runCatching { page.openInputStream().use { it.readBytes() } }.getOrNull()
        }
    } else {
        val wanted = archiveEntries.getOrNull(pageIndex) ?: return null
        runCatching {
            chapterFile.openInputStream().use { input ->
                ZipInputStream(input).use { zip ->
                    generateSequence { zip.nextEntry }
                        .firstOrNull { !it.isDirectory && it.name == wanted }
                        ?.let { zip.readBytes() }
                }
            }
        }.getOrNull()
    }
}
