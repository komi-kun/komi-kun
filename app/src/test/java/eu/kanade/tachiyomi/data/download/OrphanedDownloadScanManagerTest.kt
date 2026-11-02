package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.data.download.OrphanedDownloadScanManager.State
import eu.kanade.tachiyomi.data.download.OrphanedDownloadScanner.Kind
import eu.kanade.tachiyomi.data.download.OrphanedDownloadScanner.OrphanedDownload
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Guards that a scan belongs to the manager, not to whoever started it: its progress, result, failure
 * and cancellation are published here, and only one walk can ever be running.
 */
class OrphanedDownloadScanManagerTest {

    private val scanner = mockk<OrphanedDownloadScanner>()

    private fun orphan(path: String) = OrphanedDownload(
        id = path,
        relativePath = path,
        sizeBytes = 0,
        kind = Kind.CHAPTER,
        file = mockk(relaxed = true),
    )

    @Test
    fun `start reports running immediately and publishes progress before going ready`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val items = listOf(orphan("src/manga/ch1.cbz"))
        coEvery { scanner.scan(any()) } coAnswers {
            firstArg<(Int, Int) -> Unit>()(3, 7)
            gate.await()
            items
        }
        val manager = OrphanedDownloadScanManager(scanner, this)

        manager.start()
        manager.state.value shouldBe State.Running(0, 0)

        advanceUntilIdle()
        // The walk is still suspended: what is shown is the last progress the scanner reported, so a
        // manager that dropped the callback (or published Ready early) fails here.
        manager.state.value shouldBe State.Running(3, 7)

        gate.complete(Unit)
        advanceUntilIdle()
        manager.state.value shouldBe State.Ready(items)
    }

    @Test
    fun `a scan that throws ends failed with the exception message`() = runTest {
        coEvery { scanner.scan(any()) } throws IllegalStateException("Downloads directory unavailable")
        val manager = OrphanedDownloadScanManager(scanner, this)

        manager.start()
        advanceUntilIdle()

        manager.state.value shouldBe State.Failed("Downloads directory unavailable")
    }

    @Test
    fun `stopping a scan cancels the walk and reports it stopped`() = runTest {
        var cancelled = false
        coEvery { scanner.scan(any()) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val manager = OrphanedDownloadScanManager(scanner, this)
        manager.start()
        advanceUntilIdle()
        manager.state.value shouldBe State.Running(0, 0)

        manager.cancel()
        advanceUntilIdle()

        manager.state.value shouldBe State.Stopped
        cancelled shouldBe true
    }

    @Test
    fun `a progress callback from a cancelled scan cannot overwrite stopped`() = runTest {
        lateinit var onProgress: (Int, Int) -> Unit
        coEvery { scanner.scan(any()) } coAnswers {
            onProgress = firstArg()
            awaitCancellation()
        }
        val manager = OrphanedDownloadScanManager(scanner, this)
        manager.start()
        advanceUntilIdle()

        manager.cancel()
        // The walk is cancelled asynchronously: a callback it already handed out can still arrive.
        onProgress(1, 1)

        manager.state.value shouldBe State.Stopped
    }

    @Test
    fun `cancel with nothing running leaves the state untouched`() = runTest {
        coEvery { scanner.scan(any()) } returns emptyList()
        val manager = OrphanedDownloadScanManager(scanner, this)
        // A fresh manager is idle: cancel must not turn it into a stop the user never asked for, or
        // opening the screen would never start the first scan.
        manager.state.value shouldBe State.Idle
        manager.cancel()
        manager.state.value shouldBe State.Idle

        // A finished scan keeps its result: a stray stop must not discard the list it produced.
        manager.start()
        advanceUntilIdle()
        manager.state.value shouldBe State.Ready(emptyList())

        manager.cancel()

        manager.state.value shouldBe State.Ready(emptyList())
        coVerify(exactly = 1) { scanner.scan(any()) }
    }

    @Test
    fun `start after a completed scan runs a second scan`() = runTest {
        coEvery { scanner.scan(any()) } returns emptyList()
        val manager = OrphanedDownloadScanManager(scanner, this)
        manager.start()
        advanceUntilIdle()
        manager.state.value shouldBe State.Ready(emptyList())

        // The first job has completed but is not null: a manager that guarded on `job != null` would
        // silently refuse every rescan after the first one.
        manager.start()
        advanceUntilIdle()

        coVerify(exactly = 2) { scanner.scan(any()) }
        manager.state.value shouldBe State.Ready(emptyList())
    }

    @Test
    fun `starting while a scan is running does not start a second one`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { scanner.scan(any()) } coAnswers {
            gate.await()
            emptyList<OrphanedDownload>()
        }
        val manager = OrphanedDownloadScanManager(scanner, this)

        manager.start()
        manager.start()
        advanceUntilIdle()

        coVerify(exactly = 1) { scanner.scan(any()) }

        gate.complete(Unit)
        advanceUntilIdle()
        manager.state.value shouldBe State.Ready(emptyList())
    }
}
