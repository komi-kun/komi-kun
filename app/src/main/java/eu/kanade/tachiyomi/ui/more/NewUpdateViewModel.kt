package eu.kanade.tachiyomi.ui.more

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.updater.AppUpdateDownloadJob
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.storage.getUriCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException

@AssistedInject
class NewUpdateViewModel(
    // KUN -->
    @Assisted private val versionName: String,
    // KUN <--
    @Assisted changelogInfo: String,
    @Assisted private val downloadLink: String,
    // KUN -->
    @Assisted private val digest: String? = null,
    // KUN <--
    private val context: Context,
    private val preferences: BasePreferences,
    private val network: NetworkHelper,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        // KUN -->
        fun create(
            versionName: String,
            changelogInfo: String,
            downloadLink: String,
            digest: String? = null,
        ): NewUpdateViewModel
        // KUN <--
    }

    private val apkFile: File
        get() = AppUpdateDownloadJob.updateApk(context)

    val state: StateFlow<NewUpdateViewModel.State>
        field = MutableStateFlow<NewUpdateViewModel.State>(
            State(
                changelogInfo = changelogInfo,
            ),
        )

    init {
        // KUN -->
        viewModelScope.launch(Dispatchers.IO) {
            checkCachedApk()
        }
        // KUN <--
    }

    // KUN -->
    // The notification path and the scheduled auto-update still fetch the APK through
    // [AppUpdateDownloadJob]. When that already landed, the screen must offer Install instead of a
    // redundant download; an outdated APK from a previous update must be cleaned up, but in-progress
    // or unparsed downloads must not be clobbered while AppUpdateDownloadJob is running.
    internal fun checkCachedApk() {
        if (!apkFile.isFile || apkFile.length() == 0L) return
        val expectedSha256 = Hash.extractSha256(digest)
        if (expectedSha256 != null) {
            val actualSha256 = Hash.sha256(apkFile)
            if (actualSha256.equals(expectedSha256, ignoreCase = true)) {
                val archiveInfo = getApkPackageInfo(apkFile)
                if (archiveInfo != null && archiveInfo.packageName == context.packageName) {
                    state.update { it.copy(downloadProgress = 100, stage = Stage.Downloaded) }
                    return
                }
            }
            if (!AppUpdateDownloadJob.isRunning() && downloadJob?.isActive != true) {
                apkFile.delete()
            }
            return
        }

        val archiveInfo = getApkPackageInfo(apkFile) ?: return
        if (isMatchingUpdate(archiveInfo, versionName)) {
            state.update { it.copy(downloadProgress = 100, stage = Stage.Downloaded) }
        } else {
            // Stale update APK from an older release; delete it
            apkFile.delete()
        }
    }

    private fun getApkPackageInfo(file: File): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    file.absolutePath,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun isMatchingUpdate(info: PackageInfo, targetVersion: String): Boolean {
        if (info.packageName != context.packageName) return false
        val apkVersion = info.versionName ?: return false
        val cleanTarget = targetVersion.removePrefix("r").removePrefix("v")
        return apkVersion == cleanTarget ||
            apkVersion.substringAfterLast('-') == cleanTarget ||
            apkVersion.endsWith("-$cleanTarget")
    }

    internal fun verifyDownloadedApk(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val archiveInfo = getApkPackageInfo(file) ?: return false
        if (archiveInfo.packageName != context.packageName) return false
        val expectedSha256 = Hash.extractSha256(digest)
        if (expectedSha256 != null) {
            val actualSha256 = Hash.sha256(file)
            return actualSha256.equals(expectedSha256, ignoreCase = true)
        }
        return isMatchingUpdate(archiveInfo, versionName)
    }
    // KUN <--

    private var downloadJob: Job? = null

    fun startDownload() {
        if (downloadJob?.isActive == true) return

        downloadJob = viewModelScope.launch {
            state.update { it.copy(downloadProgress = 0, stage = Stage.Downloading) }
            try {
                withIOContext { downloadApk() }
                state.update { it.copy(downloadProgress = 100, stage = Stage.Downloaded) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                // KUN -->
                withIOContext {
                    if (apkFile.isFile && !verifyDownloadedApk(apkFile)) {
                        apkFile.delete()
                    }
                }
                // KUN <--
                state.update { it.copy(stage = Stage.Failed) }
            }
        }
    }

    private fun downloadApk() {
        val progressListener = object : ProgressListener {
            // Progress of the download
            var savedProgress = 0

            // Keep track of the last update sent to avoid updating the state too often.
            var lastTick = 0L

            override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                // A response without a length (e.g. a resumed one that lost its total) has no
                // meaningful percentage to report.
                if (contentLength <= 0L) return

                val progress = (100 * (bytesRead.toFloat() / contentLength)).toInt()
                val currentTime = System.currentTimeMillis()
                if (progress != savedProgress && currentTime - 200 > lastTick) {
                    savedProgress = progress
                    lastTick = currentTime
                    state.update { it.copy(downloadProgress = progress) }
                }
            }
        }

        // KUN -->
        // Komikku's resumable downloader (retry + `Range` request) with checksum verification
        network.downloadFileWithResume(downloadLink, apkFile, progressListener, expectedDigest = digest)

        val archiveInfo = getApkPackageInfo(apkFile)
        val expectedSha256 = Hash.extractSha256(digest)
        val isValid = archiveInfo != null && archiveInfo.packageName == context.packageName &&
            (expectedSha256 != null || isMatchingUpdate(archiveInfo, versionName))
        if (!isValid) {
            apkFile.delete()
            throw IOException("Downloaded file is not a valid APK for ${context.packageName}")
        }
        // KUN <--
    }

    fun skipUpdate(versionName: String) {
        // KUN -->
        // Notification-initiated and scheduled updates still download through
        // [AppUpdateDownloadJob]; cancel that as well as this screen's own download.
        downloadJob?.cancel()
        AppUpdateDownloadJob.stop(context)
        // KUN <--
        preferences.skippedAppUpdate.set(versionName)
    }

    fun installUpdate() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkFile.getUriCompat(context), ExtensionInstaller.APK_MIME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    @Immutable
    data class State(
        val changelogInfo: String,
        val downloadProgress: Int = 0,
        val stage: Stage = Stage.Available,
    )

    enum class Stage {
        Available,
        Downloading,
        Downloaded,
        Failed,
    }
}
