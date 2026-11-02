package eu.kanade.tachiyomi.data.download

import android.util.Log
import com.hippo.unifile.UniFile
import eu.kanade.presentation.more.settings.screen.advanced.OrphanedDownloadsViewModel
import eu.kanade.tachiyomi.data.download.OrphanedDownloadScanManager.State
import eu.kanade.tachiyomi.data.download.OrphanedDownloadScanner.Kind
import eu.kanade.tachiyomi.data.download.OrphanedDownloadScanner.OrphanedDownload
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Guards the ViewModel's half of the orphaned-download screen: it joins the app-scoped scan instead of
 * starting scans of its own, it marks the list busy while a delete runs, and its selection follows the
 * list the scan returned.
 */
class OrphanedDownloadsViewModelTest {

    private val scanState = MutableStateFlow<State>(State.Idle)
    private val manager = mockk<OrphanedDownloadScanManager>(relaxed = true)
    private val scanner = mockk<OrphanedDownloadScanner>(relaxed = true)
    private val file = mockk<UniFile>(relaxed = true)

    private val dispatcher = UnconfinedTestDispatcher()
    private var collector: Job? = null

    @BeforeEach
    fun setUp() {
        // `viewModelScope` runs on Dispatchers.Main; the screen state is a `stateIn` over the manager
        // and this entry's own state, so it only follows them while something collects it.
        Dispatchers.setMain(dispatcher)
        every { manager.state } returns scanState
        // A failed delete is reported through `xLogE`, which falls back to `android.util.Log` — a stub
        // on the JVM.
        mockkStatic(Log::class)
        every { Log.println(any(), any(), any()) } returns 0
        every { Log.getStackTraceString(any()) } returns ""
    }

    @AfterEach
    fun tearDown() {
        collector?.cancel()
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    private fun orphan(path: String, kind: Kind = Kind.CHAPTER) = OrphanedDownload(
        id = path,
        relativePath = path,
        sizeBytes = 1,
        kind = kind,
        file = file,
    )

    private fun viewModel() = OrphanedDownloadsViewModel(manager, scanner).also { viewModel ->
        collector = CoroutineScope(dispatcher).launch { viewModel.state.collect {} }
    }

    @Test
    fun `startScanIfIdle starts a scan only from idle`() = runTest {
        val viewModel = viewModel()

        // A running scan is joined, never restarted; a finished, failed or stopped one is shown as it
        // is. Starting from Stopped would quietly restart the scan the user stopped.
        scanState.value = State.Running(1, 2)
        viewModel.startScanIfIdle()
        scanState.value = State.Ready(listOf(orphan("src/manga/a.cbz")))
        viewModel.startScanIfIdle()
        scanState.value = State.Failed()
        viewModel.startScanIfIdle()
        scanState.value = State.Stopped
        viewModel.startScanIfIdle()

        verify(exactly = 0) { manager.start() }

        scanState.value = State.Idle
        viewModel.startScanIfIdle()

        verify(exactly = 1) { manager.start() }
    }

    @Test
    fun `the list is busy while the delete runs and free again afterwards`() = runTest {
        val item = orphan("src/manga/a.cbz")
        scanState.value = State.Ready(listOf(item))
        val gate = CompletableDeferred<Int>()
        coEvery { scanner.delete(any()) } coAnswers { gate.await() }
        val viewModel = viewModel()
        viewModel.toggleSelection(item.id)

        viewModel.deleteSelected()

        // The manager still holds the old Ready list, so without this flag a second Delete (and a
        // Rescan) would stay live over files that are being deleted.
        viewModel.state.value.deleting shouldBe true
        viewModel.state.value.showConfirmation shouldBe false

        gate.complete(1)

        viewModel.state.value.deleting shouldBe false
        coVerify { scanner.delete(match { it.map(OrphanedDownload::id) == listOf(item.id) }) }
        verify(exactly = 1) { manager.start() }
    }

    @Test
    fun `a failed delete clears the busy state and does not rescan`() = runTest {
        val item = orphan("src/manga/a.cbz")
        scanState.value = State.Ready(listOf(item))
        coEvery { scanner.delete(any()) } throws IllegalStateException("storage gone")
        val viewModel = viewModel()
        viewModel.toggleSelection(item.id)

        viewModel.deleteSelected()

        viewModel.state.value.deleting shouldBe false
        viewModel.state.value.deleteFailed shouldBe true
        verify(exactly = 0) { manager.start() }
    }

    @Test
    fun `the selection is pruned when the item list changes`() = runTest {
        val kept = orphan("src/manga/a.cbz")
        val gone = orphan("src/manga/b.cbz")
        scanState.value = State.Ready(listOf(kept, gone))
        val viewModel = viewModel()
        viewModel.toggleSelection(kept.id)
        viewModel.toggleSelection(gone.id)
        viewModel.state.value.selection shouldBe setOf(kept.id, gone.id)

        scanState.value = State.Ready(listOf(kept))

        // The confirmation counts the selection, so an id no longer listed would inflate it.
        viewModel.state.value.selection shouldBe setOf(kept.id)
    }

    @Test
    fun `select all takes chapter entries only`() = runTest {
        val chapter = orphan("src/manga/a.cbz")
        val mangaFolder = orphan("src/manga2", kind = Kind.MANGA)
        scanState.value = State.Ready(listOf(chapter, mangaFolder))
        val viewModel = viewModel()

        viewModel.toggleSelectAll()

        viewModel.state.value.selection shouldBe setOf(chapter.id)

        viewModel.toggleSelectAll()

        viewModel.state.value.selection shouldBe emptySet()
    }

    @Test
    fun `changing sort or group keeps the selection and its size`() = runTest {
        val big = orphan("src/manga/z.cbz").copy(sizeBytes = 30)
        val small = orphan("src/manga/a.cbz").copy(sizeBytes = 10)
        scanState.value = State.Ready(listOf(big, small))
        val viewModel = viewModel()
        viewModel.toggleSelection(big.id)

        // The default order is size-descending, so PATH reorders the rows around the selection.
        viewModel.setSort(OrphanedDownloadSort.PATH)
        viewModel.state.value.rows.map { (it as OrphanedDownloadRow.Item).item.id } shouldBe
            listOf(small.id, big.id)
        viewModel.state.value.selection shouldBe setOf(big.id)
        selectedSizeBytes(viewModel.state.value.items, viewModel.state.value.selection) shouldBe 30L

        // Grouping rewrites the rows as headers plus entries; the selection is still the same entry.
        viewModel.setGroup(OrphanedDownloadGroup.KIND)
        viewModel.state.value.rows.first() shouldBe OrphanedDownloadRow.Header(
            key = "kind:CHAPTER",
            label = OrphanedDownloadRow.Label.Type(Kind.CHAPTER),
        )
        viewModel.state.value.selection shouldBe setOf(big.id)
        selectedSizeBytes(viewModel.state.value.items, viewModel.state.value.selection) shouldBe 30L
    }
}
