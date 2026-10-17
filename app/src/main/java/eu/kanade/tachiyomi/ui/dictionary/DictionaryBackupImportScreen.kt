// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.core.dictionary.repository.DictionaryRepository
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/**
 * In-app import of a Yomitan **Dexie backup** (`yomitan-dictionaries-*.json`): SAF file picker →
 * disk-space warning → streaming conversion + engine import with progress → cancel →
 * imported/skipped/failed summary.
 *
 * It never loads the file into memory; [DictionaryBackupImporter] streams it and routes each
 * reconstructed ZIP through the existing [DictionaryImportCoordinator].
 */
class DictionaryBackupImportScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<DictionaryBackupImportViewModel>()
        val state by viewModel.state.collectAsState()
        var confirmCancel by remember { mutableStateOf(false) }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                viewModel.onPicked(uri)
            }
        }

        val running = state.phase == DictionaryBackupImportViewModel.Phase.Running

        BackHandler(enabled = running) { confirmCancel = true }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(KMR.strings.pref_import_yomitan_backup),
                    navigateUp = { if (running) confirmCancel = true else navigator.pop() },
                    scrollBehavior = it,
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state.phase) {
                    DictionaryBackupImportViewModel.Phase.Idle ->
                        Intro(onChoose = { picker.launch(BACKUP_MIME_TYPES) })

                    DictionaryBackupImportViewModel.Phase.Ready -> {
                        val inspection = state.inspection
                        if (inspection != null) Ready(inspection = inspection)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = viewModel::start) {
                                Text(stringResource(KMR.strings.yomitan_backup_import))
                            }
                            OutlinedButton(onClick = { picker.launch(BACKUP_MIME_TYPES) }) {
                                Text(stringResource(KMR.strings.yomitan_backup_choose_file))
                            }
                        }
                    }

                    DictionaryBackupImportViewModel.Phase.Running ->
                        Running(state = state, onCancel = { confirmCancel = true })

                    DictionaryBackupImportViewModel.Phase.Done -> {
                        state.summary?.let { Done(summary = it) }
                        Button(onClick = navigator::pop) {
                            Text(stringResource(KMR.strings.yomitan_backup_done))
                        }
                    }

                    DictionaryBackupImportViewModel.Phase.Error -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(
                                    KMR.strings.yomitan_backup_error,
                                    state.error.orEmpty(),
                                ),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        OutlinedButton(onClick = viewModel::reset) {
                            Text(stringResource(KMR.strings.yomitan_backup_choose_file))
                        }
                        Button(onClick = navigator::pop) {
                            Text(stringResource(KMR.strings.yomitan_backup_done))
                        }
                    }
                }
            }
        }

        if (confirmCancel) {
            AlertDialog(
                onDismissRequest = { confirmCancel = false },
                title = { Text(stringResource(KMR.strings.yomitan_backup_cancel_confirm_title)) },
                text = { Text(stringResource(KMR.strings.yomitan_backup_cancel_confirm_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmCancel = false
                            viewModel.cancel()
                            navigator.pop()
                        },
                    ) { Text(stringResource(KMR.strings.yomitan_backup_cancel)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmCancel = false }) {
                        Text(stringResource(KMR.strings.yomitan_backup_keep_importing))
                    }
                },
            )
        }
    }

    @Composable
    private fun Intro(onChoose: () -> Unit) {
        Text(stringResource(KMR.strings.yomitan_backup_intro))
        Button(onClick = onChoose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(KMR.strings.yomitan_backup_choose_file))
        }
    }

    @Composable
    private fun Ready(inspection: BackupInspection) {
        val context = LocalContext.current
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(inspection.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        KMR.strings.yomitan_backup_warning_space,
                        formatBytes(context, inspection.sizeBytes),
                        formatBytes(context, inspection.requiredBytes),
                        formatBytes(context, inspection.freeBytes),
                    ),
                )
                if (!inspection.hasRecommendedSpace) {
                    Text(
                        text = stringResource(
                            KMR.strings.yomitan_backup_warning_low_space,
                            formatBytes(context, inspection.freeBytes),
                            formatBytes(context, inspection.requiredBytes),
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(stringResource(KMR.strings.yomitan_backup_warning_no_styles))
            }
        }
    }

    @Composable
    private fun Running(state: DictionaryBackupImportViewModel.State, onCancel: () -> Unit) {
        val progress = when {
            state.dictTotal > 0 -> state.dictIndex.toFloat() / state.dictTotal
            state.rowsTotal > 0 -> state.rowsRead.toFloat() / state.rowsTotal
            else -> null
        }
        Text(
            if (state.currentTitle.isNotEmpty()) {
                stringResource(
                    KMR.strings.yomitan_backup_importing,
                    state.dictIndex,
                    state.dictTotal,
                    state.currentTitle,
                )
            } else {
                stringResource(KMR.strings.yomitan_backup_parsing, state.rowsRead, state.rowsTotal)
            },
        )
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (state.importedCount > 0 || state.failedCount > 0) {
            Text(
                stringResource(
                    KMR.strings.yomitan_backup_running_counts,
                    state.importedCount,
                    state.failedCount,
                ),
            )
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onCancel) {
            Text(stringResource(KMR.strings.yomitan_backup_cancel))
        }
    }

    @Composable
    private fun Done(summary: BackupImportSummary) {
        Text(
            text = stringResource(KMR.strings.yomitan_backup_done_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(
                KMR.strings.yomitan_backup_summary,
                summary.imported.size,
                summary.skipped.size,
                summary.failed.size,
                formatElapsed(summary.elapsedMs),
            ),
        )
        if (summary.malformedRows > 0) {
            Text(stringResource(KMR.strings.yomitan_backup_summary_malformed, summary.malformedRows))
        }
        if (summary.imported.isEmpty() && summary.skipped.isEmpty() && summary.failed.isEmpty()) {
            Text(stringResource(KMR.strings.yomitan_backup_none))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            summary.imported.forEach { dictionary ->
                Text(
                    stringResource(
                        KMR.strings.yomitan_backup_imported_item,
                        dictionary.title,
                        dictionary.termCount,
                    ),
                )
            }
            summary.skipped.forEach { title ->
                Text(stringResource(KMR.strings.yomitan_backup_skipped_item, title))
            }
            summary.failed.forEach { failure ->
                Text(
                    text = stringResource(KMR.strings.yomitan_backup_failed_item, failure.title, failure.reason),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    private fun formatBytes(context: Context, bytes: Long): String =
        Formatter.formatFileSize(context, bytes)

    private fun formatElapsed(millis: Long): String {
        val seconds = millis / 1000
        return if (seconds < 60) {
            "${seconds}s"
        } else {
            "${seconds / 60}m ${seconds % 60}s"
        }
    }

    private companion object {
        val BACKUP_MIME_TYPES = arrayOf("application/json", "application/octet-stream", "*/*")
    }
}

/**
 * Owns the running import. The work lives in [viewModelScope] on [Dispatchers.IO] with a partial
 * wakelock; leaving the screen cancels it (the user is asked to confirm first) and the resume
 * manifest keeps already-imported dictionaries.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class DictionaryBackupImportViewModel(
    private val context: Context,
    repository: DictionaryRepository,
    prefs: DictionaryPreferences,
) : ViewModel() {

    enum class Phase { Idle, Ready, Running, Done, Error }

    @Immutable
    data class State(
        val phase: Phase = Phase.Idle,
        val inspection: BackupInspection? = null,
        val uri: Uri? = null,
        val rowsRead: Long = 0L,
        val rowsTotal: Long = 0L,
        val table: String = "",
        val dictIndex: Int = 0,
        val dictTotal: Int = 0,
        val currentTitle: String = "",
        val importedCount: Int = 0,
        val failedCount: Int = 0,
        val summary: BackupImportSummary? = null,
        val error: String? = null,
    )

    private val importer = DictionaryBackupImporter(context, repository, prefs)
    private val stateFlow = MutableStateFlow(State())
    val state: StateFlow<State> = stateFlow

    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    fun onPicked(uri: Uri) {
        val inspection = runCatching { importer.inspect(uri) }.getOrNull()
        stateFlow.value = State(phase = Phase.Ready, uri = uri, inspection = inspection)
    }

    fun start() {
        val uri = stateFlow.value.uri ?: return
        if (job?.isActive == true) return
        stateFlow.value = State(
            phase = Phase.Running,
            uri = uri,
            inspection = stateFlow.value.inspection,
        )
        job = viewModelScope.launch {
            acquireWakeLock()
            try {
                val summary = withContext(Dispatchers.IO) {
                    importer.import(uri) { progress -> applyProgress(progress) }
                }
                stateFlow.value = stateFlow.value.copy(phase = Phase.Done, summary = summary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                stateFlow.value = stateFlow.value.copy(phase = Phase.Error, error = e.message ?: "error")
            } finally {
                releaseWakeLock()
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun reset() {
        stateFlow.value = State()
    }

    private fun applyProgress(progress: BackupImportProgress) {
        stateFlow.update { current ->
            when (progress) {
                BackupImportProgress.Preparing -> current.copy(table = "")
                is BackupImportProgress.Parsing -> current.copy(
                    rowsRead = progress.rowsRead,
                    rowsTotal = progress.totalRows,
                    table = progress.table,
                    currentTitle = "",
                )
                is BackupImportProgress.Importing -> current.copy(
                    dictIndex = progress.index,
                    dictTotal = progress.total,
                    currentTitle = progress.title,
                    // Counts are refreshed from the summary at the end; keep live counters monotonic.
                    importedCount = maxOf(current.importedCount, progress.index - 1),
                )
            }
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "komikun:yomitanBackup").apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onCleared() {
        job?.cancel()
        releaseWakeLock()
        super.onCleared()
    }

    private companion object {
        const val WAKELOCK_TIMEOUT_MS = 60L * 60L * 1000L
    }
}
// KUN <--
