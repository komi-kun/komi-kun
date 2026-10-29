// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Pins the import call site: the delete-and-copy of the live dictionary tree must run through
// `DictionaryRepository.withDictionaryTreeMutation` (session destroyed first, repository monitor held
// for the whole mutation), so a concurrent lookup/media read can never see a half-replaced tree, and
// a same-title re-import is picked up instead of being served from the pre-import native query.
package eu.kanade.tachiyomi.ui.dictionary

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.instanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mihon.core.dictionary.engine.DictionaryEngine
import mihon.core.dictionary.engine.DictionarySession
import mihon.core.dictionary.engine.ImportProgress
import mihon.core.dictionary.model.DictionaryPaths
import mihon.core.dictionary.model.DictionaryProfile
import mihon.core.dictionary.model.DictionaryStyle
import mihon.core.dictionary.model.EntryTypeCounts
import mihon.core.dictionary.model.ImportResult
import mihon.core.dictionary.model.KanjiResult
import mihon.core.dictionary.model.LookupResult
import mihon.core.dictionary.model.TermResult
import mihon.core.dictionary.model.TransformGroup
import mihon.core.dictionary.model.dictionariesDir
import mihon.core.dictionary.model.dictionaryTypeDir
import mihon.core.dictionary.repository.DictionaryRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * App-side call-site test for [DictionaryImportCoordinator.importFromZip].
 *
 * Real filesystem (temp dirs reached through a stubbed `Context`), the real
 * [DictionaryRepository], and a fake engine/session at the `:core:dictionary` interface seam. The
 * engine fake writes the "imported" directory the coordinator copies from; the session fake can block
 * inside `lookup` so the test can pin the repository monitor and observe what the coordinator does
 * while a lookup is in flight.
 */
class DictionaryImportCoordinatorTest {

    @BeforeEach
    fun stubAndroidLog() {
        // The coordinator reports import failures through android.util.Log, a stub on the JVM.
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @AfterEach
    fun unstubAndroidLog() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `import replaces the tree under the session lock and the next lookup reopens`() = runBlocking<Unit> {
        val h = harness()
        h.seedOldTree()
        h.repository.lookup("食べる", h.paths)
        val before = h.engine.sessions.single()

        val imported = DictionaryImportCoordinator.importFromZip(h.context, File("unused.zip"), h.prefs, h.repository)
            .importedDictionary()

        imported?.title shouldBe TITLE
        imported?.termCount shouldBe 1L
        // Delete + copy really happened (the stale marker was only in the old tree).
        File(h.termDir, "old.marker").exists() shouldBe false
        File(h.termDir, "fresh.marker").exists() shouldBe true
        File(h.termDir, "index.json").readText() shouldBe """{"title":"New Revision"}"""
        // The session the old tree was loaded into is gone, and destroyed exactly once.
        before.closed shouldBe true
        before.closeCount shouldBe 1

        // Same paths as before (same-title replacement): only the close/reopen round trip can make
        // the new files visible.
        h.repository.lookup("食べる", h.paths)
        h.engine.sessions.size shouldBe 2
        h.engine.sessions[1].rebuilds shouldBe 1
        h.engine.sessions[1].lookupCalls shouldBe 1
        before.lookupCalls shouldBe 1
        h.engine.usedAfterClose.get() shouldBe 0
    }

    @Test
    fun `a failed engine import leaves both the tree and the open session alone`() = runBlocking<Unit> {
        val h = harness(engineSucceeds = false)
        h.seedOldTree()
        h.repository.lookup("食べる", h.paths)
        val session = h.engine.sessions.single()

        val outcome = DictionaryImportCoordinator.importFromZip(h.context, File("broken.zip"), h.prefs, h.repository)

        outcome shouldBe DictionaryImportOutcome.Failed(DictionaryImportFailure.InvalidDictionary)
        File(h.termDir, "old.marker").exists() shouldBe true
        File(h.termDir, "index.json").readText() shouldBe OLD_INDEX
        session.closed shouldBe false
        session.closeCount shouldBe 0
        h.engine.sessions.size shouldBe 1
    }

    /**
     * The defect this pins: `importFromUri` used to return `null` for *every* failure, so both entry
     * points rendered nothing at all after the user picked a file. A non-ZIP must now carry a reason.
     */
    @Test
    fun `importing a corrupt non-zip uri reports a reason instead of nothing`() = runBlocking<Unit> {
        val h = harness(acceptsZip = { zip -> zip.readText().startsWith("PK") })
        val uri = mockk<Uri>()
        every { h.resolver.openInputStream(uri) } returns "this is not a zip".byteInputStream()

        val outcome = DictionaryImportCoordinator.importFromUri(h.context, uri, h.prefs, h.repository)

        outcome shouldBe DictionaryImportOutcome.Failed(DictionaryImportFailure.InvalidDictionary)
        // Nothing was copied into the live tree, and the temp ZIP is not left behind in the cache.
        h.termDir.exists() shouldBe false
        h.context.cacheDir.listFiles().orEmpty().map { it.name } shouldBe emptyList()
    }

    /**
     * The defect this pins: the ZIP's own `index.json` title went straight into
     * `dictionaryTypeDir`, so a title of `../../evil` made the delete-and-copy recursively delete
     * and then overwrite `<externalFiles>/evil` — an arbitrary directory outside the dictionary root.
     */
    @Test
    fun `a traversal title never deletes or writes outside the dictionary root`() = runBlocking<Unit> {
        val h = harness(title = "../../evil")
        val externalRoot = h.context.getExternalFilesDir(null)!!
        // A dictionary already exists, so `dictionaries/term` does too — otherwise the traversal
        // path simply fails to resolve and nothing is demonstrated.
        File(dictionariesDir(h.context), "term").mkdirs()
        val victim = File(externalRoot, "evil").apply { mkdirs() }
        File(victim, "victim.marker").writeText("keep")

        val outcome = DictionaryImportCoordinator.importFromZip(h.context, File("unused.zip"), h.prefs, h.repository)

        // The unrelated directory is untouched: neither deleted (marker still there) nor written to.
        File(victim, "victim.marker").readText() shouldBe "keep"
        File(victim, "index.json").exists() shouldBe false
        File(victim, "fresh.marker").exists() shouldBe false
        // The import still landed, as one flat dictionary directory inside the dictionary root.
        outcome.importedDictionary()?.termCount shouldBe 1L
        h.importedTypeDirs("term").single().let { dir ->
            File(dir, "index.json").readText() shouldBe """{"title":"New Revision"}"""
        }
    }

    /**
     * The defect this pins: the engine itself joins `output_dir / index.title` and creates that
     * directory, so an unsafe title from the ZIP escaped `tempImportDir` *before* the coordinator
     * saw it — the post-import sanitiser could not prevent the write, only redo it. The title is
     * therefore read from the archive and refused before the engine is invoked at all.
     */
    @Test
    fun `an unsafe title in the zip is refused before the engine runs`() = runBlocking<Unit> {
        val h = harness()
        val zip = zipWithIndexTitle("""{"title":"../../evil"}""")

        val outcome = DictionaryImportCoordinator.importFromZip(h.context, zip, h.prefs, h.repository)

        // The engine must never have been handed a ZIP whose title it would use as a path.
        h.engine.importCalls.get() shouldBe 0
        outcome shouldBe DictionaryImportOutcome.Failed(DictionaryImportFailure.InvalidDictionary)
        // Nothing was created inside the dictionary tree, and nothing outside it either.
        h.importedTypeDirs("term") shouldBe emptyList()
        File(h.context.getExternalFilesDir(null)!!, "evil").exists() shouldBe false
        File(h.context.cacheDir, "evil").exists() shouldBe false
    }

    /**
     * The defect this pins: the pre-check read the title through `ZipFile.getEntry` — a hash lookup
     * over the central directory — while the engine takes the *first* entry named `index.json`
     * (`hoshidicts/src/zip/zip.cpp`, `Zip::find`). A ZIP carrying two `index.json` entries
     * therefore made the two readers disagree: this check judged `{"title":"Safe"}` while the
     * engine used `{"title":"../../evil"}`, and wrote and deleted outside the dictionary root.
     * Neither order may be imported.
     */
    @Test
    fun `a zip with two index json entries is refused in either order`() = runBlocking<Unit> {
        val h = harness()
        val externalRoot = h.context.getExternalFilesDir(null)!!

        // (a) the engine's (first) entry is safe and the later duplicate is the traversal ...
        val safeFirst = zipWithEntries(
            "index.json" to """{"title":"Safe"}""",
            "index.json" to """{"title":"../../evil"}""",
        )
        val firstOutcome = DictionaryImportCoordinator.importFromZip(h.context, safeFirst, h.prefs, h.repository)

        // (b) ... and the other way round: whichever entry each reader picks, a ZIP the two cannot
        // agree on is never handed to the engine.
        val evilFirst = zipWithEntries(
            "index.json" to """{"title":"../../evil"}""",
            "index.json" to """{"title":"Safe"}""",
        )
        val secondOutcome = DictionaryImportCoordinator.importFromZip(h.context, evilFirst, h.prefs, h.repository)

        firstOutcome shouldBe DictionaryImportOutcome.Failed(DictionaryImportFailure.InvalidDictionary)
        secondOutcome shouldBe DictionaryImportOutcome.Failed(DictionaryImportFailure.InvalidDictionary)
        // No title reached the engine, and nothing appeared outside the dictionary root.
        h.engine.importCalls.get() shouldBe 0
        File(externalRoot, "evil").exists() shouldBe false
        h.importedTypeDirs("term") shouldBe emptyList()
    }

    /**
     * The selection rule one level down: the fence works from exactly one `index.json` entry — the
     * exact name the engine's `Zip::find` matches, never a lookalike — and a *repeated* entry is
     * refused instead of one of the two being picked and hoped to be the one the engine picked.
     * `java.util.zip` resolves entries by name, so re-introducing `ZipFile.getEntry` here makes a
     * duplicate ZIP answer `Title(...)` again and this fails.
     */
    @Test
    fun `the title fence reads the exact index json name and refuses a repeated one`() {
        // A lookalike entry carrying a traversal title is not the title entry: the engine ignores it
        // too, so the import is judged on `index.json` alone.
        DictionaryImportCoordinator.readIndexTitle(
            zipWithEntries(
                "index.json" to """{"title":"$TITLE"}""",
                "index.json.bak" to """{"title":"../../evil"}""",
            ),
        ) shouldBe DictionaryImportCoordinator.IndexTitleCheck.Title(TITLE)

        // Two entries of the exact name: refused in either order, whatever the two titles are.
        DictionaryImportCoordinator.readIndexTitle(
            zipWithEntries(
                "index.json" to """{"title":"Safe"}""",
                "index.json" to """{"title":"../../evil"}""",
            ),
        ) shouldBe DictionaryImportCoordinator.IndexTitleCheck.Duplicate
        DictionaryImportCoordinator.readIndexTitle(
            zipWithEntries(
                "index.json" to """{"title":"../../evil"}""",
                "index.json" to """{"title":"Safe"}""",
            ),
        ) shouldBe DictionaryImportCoordinator.IndexTitleCheck.Duplicate
    }

    /**
     * The read is capped: `index.json` is only consulted for `title`, so an entry large enough to
     * make the fence read a whole file is refused instead of streamed into memory. It is also
     * *refused* rather than skipped — an unreadable entry is not "nothing to judge".
     */
    @Test
    fun `an index json larger than the read cap is refused instead of read`() = runBlocking<Unit> {
        val h = harness()
        val huge = """{"title":"Safe","padding":"${"x".repeat(16 * 1024)}"}"""

        val outcome = DictionaryImportCoordinator.importFromZip(
            h.context,
            zipWithIndexTitle(huge),
            h.prefs,
            h.repository,
        )

        outcome shouldBe DictionaryImportOutcome.Failed(DictionaryImportFailure.InvalidDictionary)
        h.engine.importCalls.get() shouldBe 0
        // A normal-sized index.json still imports: the cap is generous, not a ban.
        DictionaryImportCoordinator.importFromZip(
            h.context,
            zipWithIndexTitle("""{"title":"$TITLE"}"""),
            h.prefs,
            h.repository,
        ) shouldBe DictionaryImportOutcome.Imported(
            ImportedDictionary(
                title = TITLE,
                termCount = 1,
                freqCount = 0,
                pitchCount = 0,
                kanjiCount = 0,
                mediaCount = 0,
            ),
        )
    }

    /**
     * The defect this pins: a title containing `/` became a *nested* path under the type dir, so the
     * "dictionary" was a directory (`Evil/`) holding no `index.json` — invisible to
     * `allDictionaryNames` and to the engine, and leftovers of a nested tree could not be deleted.
     */
    @Test
    fun `a title containing a separator still lands as one flat dictionary directory`() = runBlocking<Unit> {
        val h = harness(title = "Evil/Name")

        val outcome = DictionaryImportCoordinator.importFromZip(h.context, File("unused.zip"), h.prefs, h.repository)

        outcome.importedDictionary()?.termCount shouldBe 1L
        h.importedTypeDirs("term").single().let { dir ->
            File(dir, "index.json").readText() shouldBe """{"title":"New Revision"}"""
        }
    }

    /**
     * The defect this pins: an `IOException` raised while *reading* the picked document escaped the
     * inner catch (only `SecurityException` was handled), reached `failureOf(IOException)` and was
     * reported as "storage full or read-only" — the wrong string, for the wrong cause.
     */
    @Test
    fun `an unreadable picked document is reported as an unreadable source, not storage`() = runBlocking<Unit> {
        val h = harness()
        val uri = mockk<Uri>()
        every { h.resolver.openInputStream(uri) } returns object : InputStream() {
            override fun read(): Int = throw IOException("device went away")
        }

        val outcome = DictionaryImportCoordinator.importFromUri(h.context, uri, h.prefs, h.repository)

        outcome shouldBe DictionaryImportOutcome.Failed(DictionaryImportFailure.UnreadableSource)
        h.context.cacheDir.listFiles().orEmpty().map { it.name } shouldBe emptyList()
    }

    /**
     * The defect this pins: a title of `/` made `File(typeDir, "/")` resolve to the filesystem root
     * itself, so the coordinator's `deleteRecursively()` ran on `/`. This case is deliberately only
     * exercised after the guard exists — running it against the old code would try to delete the
     * filesystem root.
     */
    @Test
    fun `an absolute title cannot escape the dictionary root`() = runBlocking<Unit> {
        val h = harness(title = "/")
        val externalRoot = h.context.getExternalFilesDir(null)!!

        val outcome = DictionaryImportCoordinator.importFromZip(h.context, File("unused.zip"), h.prefs, h.repository)

        outcome.importedDictionary()?.termCount shouldBe 1L
        // Everything landed inside the dictionary root; nothing else was created or removed.
        externalRoot.listFiles().orEmpty().map { it.name }.sorted() shouldBe listOf("dictionaries")
        h.importedTypeDirs("term").single().let { dir ->
            File(dir, "index.json").readText() shouldBe """{"title":"New Revision"}"""
        }
    }

    /**
     * The defect this pins: the coordinator could be entered twice (two taps, two pickers) and both
     * engine imports + delete-and-copy ran interleaved over the same type dirs.
     */
    @Test
    fun `a second import while one is in flight is rejected instead of interleaved`() {
        val h = harness()
        h.engine.blockNextImport()
        var first: DictionaryImportOutcome? = null
        val importer = thread(name = "first-import") {
            first = runBlocking {
                DictionaryImportCoordinator.importFromZip(h.context, File("unused.zip"), h.prefs, h.repository)
            }
        }
        h.engine.importEntered.await(5, TimeUnit.SECONDS) shouldBe true

        val second = runBlocking {
            DictionaryImportCoordinator.importFromZip(h.context, File("unused.zip"), h.prefs, h.repository)
        }

        // Rejected straight away, and it never reached the engine: one import ran, not two.
        second shouldBe DictionaryImportOutcome.Failed(DictionaryImportFailure.AlreadyRunning)
        h.engine.importCalls.get() shouldBe 1

        h.engine.releaseImport()
        importer.join(5_000)
        (first as DictionaryImportOutcome.Imported).dictionary.title shouldBe TITLE
        h.engine.importCalls.get() shouldBe 1
    }

    @Test
    fun `a copy failure still closes the session and reports failure`() = runBlocking<Unit> {
        val h = harness()
        // A file where the `term` type directory belongs makes the copy into it fail.
        val typeDir = h.termDir.parentFile!!
        typeDir.parentFile?.mkdirs()
        typeDir.writeText("not a directory")
        h.repository.lookup("食べる", DictionaryPaths(termPaths = listOf("/nope")))
        val session = h.engine.sessions.single()

        val outcome = DictionaryImportCoordinator.importFromZip(h.context, File("unused.zip"), h.prefs, h.repository)

        // A write that fails is a storage problem, not "not a dictionary".
        outcome shouldBe DictionaryImportOutcome.Failed(DictionaryImportFailure.StorageFailure)
        // The mutation section closed the session, and the tree write failed inside it, so the
        // pre-mutation session must not survive it either way.
        session.closed shouldBe true
        session.closeCount shouldBe 1
        // The next lookup opens a session over whatever is actually on disk.
        h.repository.lookup("食べる", DictionaryPaths(termPaths = listOf("/nope")))
        h.engine.sessions.size shouldBe 2
        h.engine.usedAfterClose.get() shouldBe 0
    }

    /**
     * The defect this pins: the swapped-in tree used to be written only for the types with a
     * non-zero count in the *new* import, so a re-import that dropped a type left the previous
     * dictionary's files in `dictionaries/<type>/<name>` — and the engine kept serving them.
     */
    @Test
    fun `a re-import that drops a type deletes that type's stale directory`() = runBlocking<Unit> {
        val h = harness()
        h.seedOldTree()
        // What an earlier import that *did* have pitches left behind.
        val stalePitchDir = dictionaryTypeDir(h.context, "pitch", TITLE).apply { mkdirs() }
        File(stalePitchDir, "old.pitches").writeText("stale")

        val outcome = DictionaryImportCoordinator.importFromZip(h.context, File("unused.zip"), h.prefs, h.repository)

        outcome.importedDictionary()?.termCount shouldBe 1L
        File(h.termDir, "fresh.marker").exists() shouldBe true
        // The type the new import has no data for is gone, not shadowed by the old files.
        stalePitchDir.exists() shouldBe false
        // And the successful swap leaves no staging directories behind.
        File(dictionariesDir(h.context), "term").listFiles().orEmpty().map { it.name } shouldBe listOf(TITLE)
    }

    /**
     * The defect this pins: the live directory was deleted and then copied onto, so any failure of
     * that copy (disk full, an I/O error, an interrupted re-import) left **no working dictionary** —
     * the old files were gone and the new ones were half-written. Staging next to each destination
     * and swapping only once every type is staged keeps the previous dictionary intact.
     */
    @Test
    fun `a staging failure leaves the previous dictionary intact`() = runBlocking<Unit> {
        val h = harness(freqCount = 1)
        h.seedOldTree()
        // A file where the `frequency` type directory belongs makes that type's staging fail, after
        // `term` was already staged (targets are `term` then `frequency`).
        File(dictionariesDir(h.context), "frequency").writeText("not a directory")

        val outcome = DictionaryImportCoordinator.importFromZip(h.context, File("unused.zip"), h.prefs, h.repository)

        outcome shouldBe DictionaryImportOutcome.Failed(DictionaryImportFailure.StorageFailure)
        // The dictionary that worked before the failed re-import still works: same files, untouched.
        File(h.termDir, "old.marker").readText() shouldBe "stale"
        File(h.termDir, "index.json").readText() shouldBe OLD_INDEX
        File(h.termDir, "fresh.marker").exists() shouldBe false
        // No half-staged copy is left for the next import to trip over.
        File(dictionariesDir(h.context), "term").listFiles().orEmpty().map { it.name } shouldBe listOf(TITLE)
    }

    @Test
    fun `the coordinator cannot touch the tree while a lookup holds the session`() {
        val h = harness()
        h.seedOldTree()
        h.engine.blockNextLookup()
        val lookup = thread(name = "lookup") { h.repository.lookup("食べる", h.paths) }
        h.engine.lookupEntered.await(5, TimeUnit.SECONDS) shouldBe true

        val importDone = AtomicBoolean(false)
        var imported: DictionaryImportOutcome? = null
        val importer = thread(name = "import") {
            imported = runBlocking {
                DictionaryImportCoordinator.importFromZip(h.context, File("unused.zip"), h.prefs, h.repository)
            }
            importDone.set(true)
        }

        // The lookup is inside the single non-thread-safe session and holds the repository monitor,
        // so the delete+copy must not have started: the old tree is still intact.
        Thread.sleep(250)
        importDone.get() shouldBe false
        File(h.termDir, "old.marker").exists() shouldBe true
        File(h.termDir, "index.json").readText() shouldBe OLD_INDEX

        h.engine.releaseLookup()
        lookup.join(5_000)
        importer.join(5_000)

        importDone.get() shouldBe true
        imported?.importedDictionary()?.title shouldBe TITLE
        File(h.termDir, "old.marker").exists() shouldBe false
        File(h.termDir, "index.json").readText() shouldBe """{"title":"New Revision"}"""
        h.engine.sessions.single().closed shouldBe true
        h.engine.usedAfterClose.get() shouldBe 0
    }

    @Test
    fun `delete removes the tree and the profile entry through the serialized mutation`() {
        val h = harness()
        h.seedOldTree()
        h.seedProfile()
        h.repository.lookup("食べる", h.paths)
        val session = h.engine.sessions.single()

        DictionaryImportCoordinator.delete(h.context, TITLE, h.prefs, h.repository)

        h.termDir.exists() shouldBe false
        val profile = h.prefs.profileStore.getProfiles().single()
        profile.dictionaryOrder shouldBe listOf("Other")
        profile.enabledDictionaries shouldBe emptySet()
        // The deleted dictionary was loaded in that session, so it must not survive.
        session.closed shouldBe true
        session.closeCount shouldBe 1

        h.repository.lookup("食べる", DictionaryPaths(termPaths = listOf("/gone")))
        h.engine.sessions.size shouldBe 2
        h.engine.sessions[1].closed shouldBe false
        h.engine.usedAfterClose.get() shouldBe 0
    }

    @Test
    fun `delete cannot remove the tree while a lookup holds the session`() {
        val h = harness()
        h.seedOldTree()
        h.seedProfile()
        h.engine.blockNextLookup()
        val lookup = thread(name = "lookup") { h.repository.lookup("食べる", h.paths) }
        h.engine.lookupEntered.await(5, TimeUnit.SECONDS) shouldBe true

        val deleteDone = AtomicBoolean(false)
        val deleter = thread(name = "delete") {
            DictionaryImportCoordinator.delete(h.context, TITLE, h.prefs, h.repository)
            deleteDone.set(true)
        }

        // Neither half of `delete` may run before the session monitor is acquired: the tree and
        // the profile both still describe the dictionary.
        Thread.sleep(250)
        deleteDone.get() shouldBe false
        h.termDir.exists() shouldBe true
        File(h.termDir, "old.marker").exists() shouldBe true
        h.prefs.profileStore.getProfiles().single().dictionaryOrder shouldBe listOf(TITLE, "Other")
        h.prefs.profileStore.getProfiles().single().enabledDictionaries shouldBe setOf(TITLE)

        h.engine.releaseLookup()
        lookup.join(5_000)
        deleter.join(5_000)

        deleteDone.get() shouldBe true
        h.termDir.exists() shouldBe false
        h.engine.sessions.single().closed shouldBe true
        h.engine.usedAfterClose.get() shouldBe 0
    }

    /**
     * The defect this pins: both `catch (e: Throwable)` blocks in the coordinator swallowed
     * `CancellationException` too, so leaving the import screen mid-import was reported to the user as
     * "import failed" (`Failed(Unknown)`) and the coroutine kept its cancellation suppressed. A
     * cancelled import must surface as cancellation.
     */
    @Test
    fun `cancellation during the engine import is rethrown, not reported as a failure`() {
        val h = harness(cancels = true)

        val thrown = runBlocking {
            runCatching {
                DictionaryImportCoordinator.importFromZip(h.context, File("unused.zip"), h.prefs, h.repository)
            }.exceptionOrNull()
        }

        thrown shouldBe instanceOf<CancellationException>()
        thrown?.message shouldBe IMPORT_CANCELLED
        // Nothing reached the tree: a cancelled import never rewrites the dictionary.
        h.termDir.exists() shouldBe false
    }

    /**
     * The other `catch (e: Throwable)`: the one wrapping the picked document. An `IOException` there
     * is a source failure, but a `CancellationException` is the caller going away — it must not be
     * folded into an outcome the UI reports as "import failed".
     */
    @Test
    fun `cancellation while the picked document is copied is rethrown too`() {
        val h = harness()
        val uri = mockk<Uri>()
        every { h.resolver.openInputStream(uri) } returns object : InputStream() {
            override fun read(): Int = throw CancellationException(IMPORT_CANCELLED)
        }

        val thrown = runBlocking {
            runCatching {
                DictionaryImportCoordinator.importFromUri(h.context, uri, h.prefs, h.repository)
            }.exceptionOrNull()
        }

        thrown shouldBe instanceOf<CancellationException>()
        thrown?.message shouldBe IMPORT_CANCELLED
    }

    private class Harness(
        val context: Context,
        val resolver: ContentResolver,
        val prefs: DictionaryPreferences,
        val engine: FakeEngine,
        val repository: DictionaryRepository,
    ) {
        val termDir: File get() = dictionaryTypeDir(context, "term", TITLE)
        val paths: DictionaryPaths get() = DictionaryPaths(termPaths = listOf(termDir.absolutePath))

        /** Every directory directly under the `dictionaries/<type>` root, sorted. */
        fun importedTypeDirs(type: String): List<File> =
            File(dictionariesDir(context), type).listFiles().orEmpty()
                .filter { it.isDirectory }
                .sortedBy { it.name }

        /** The tree a previous import left behind, with a marker only the old files have. */
        fun seedOldTree() {
            termDir.mkdirs()
            File(termDir, "index.json").writeText(OLD_INDEX)
            File(termDir, "old.marker").writeText("stale")
        }

        /** An active profile that lists the dictionary being deleted. */
        fun seedProfile() {
            val profile = DictionaryProfile.createDefault(dictionaryOrder = listOf(TITLE, "Other"))
                .copy(enabledDictionaries = setOf(TITLE))
            prefs.profileStore.saveProfiles(listOf(profile))
            prefs.profileStore.setActiveProfile(profile.id)
        }
    }

    private fun harness(
        engineSucceeds: Boolean = true,
        acceptsZip: (File) -> Boolean = { true },
        title: String = TITLE,
        freqCount: Int = 0,
        cancels: Boolean = false,
    ): Harness {
        val root = File(System.getProperty("java.io.tmpdir"), "dict-coordinator-${System.nanoTime()}")
        val cache = File(root, "cache").apply { mkdirs() }
        val files = File(root, "files").apply { mkdirs() }
        val resolver = mockk<ContentResolver>()
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns cache
        every { context.getExternalFilesDir(null) } returns files
        every { context.contentResolver } returns resolver
        val engine = FakeEngine(
            succeeds = engineSucceeds,
            accepts = acceptsZip,
            title = title,
            freqCount = freqCount,
            cancels = cancels,
        )
        return Harness(
            context = context,
            resolver = resolver,
            // The shared fakes: `InMemoryPreferenceStore` writes through by key (like the real
            // `AndroidPreferenceStore`), so profiles written by the coordinator are read back by the
            // same store — no test-local `PreferenceStore` reimplementation needed.
            prefs = DictionaryPreferences(InMemoryPreferenceStore()),
            engine = engine,
            // The import/delete call sites only do engine work when the user has opted in; with the
            // switch off `probeEntryTypes`/`importDictionary` short-circuit (`:core:dictionary` gate).
            repository = DictionaryRepository(
                engine,
                context,
                DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(true) },
            ),
        )
    }

    /** A real ZIP whose `index.json` carries [indexJson] — the only entry the pre-check reads. */
    private fun zipWithIndexTitle(indexJson: String): File {
        val zip = File(System.getProperty("java.io.tmpdir"), "dict-index-${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("index.json"))
            out.write(indexJson.toByteArray())
            out.closeEntry()
        }
        return zip
    }

    /**
     * A stored (uncompressed) ZIP whose central directory lists [entries] in this order, duplicate
     * names included: `ZipOutputStream` refuses to write the same name twice, so the bytes are laid
     * out by hand — local headers, then the central directory, then the end record.
     */
    private fun zipWithEntries(vararg entries: Pair<String, String>): File {
        val body = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        for ((name, content) in entries) {
            val data = content.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(data) }
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val offset = body.size()
            body.le32(LOCAL_HEADER_SIG)
            body.le16(ZIP_VERSION)
            body.le16(0) // flags
            body.le16(STORED_METHOD)
            body.le16(0) // mod time
            body.le16(0) // mod date
            body.le32(crc.value.toInt())
            body.le32(data.size)
            body.le32(data.size)
            body.le16(nameBytes.size)
            body.le16(0) // extra field length
            body.write(nameBytes)
            body.write(data)

            central.le32(CENTRAL_HEADER_SIG)
            central.le16(ZIP_VERSION) // version made by
            central.le16(ZIP_VERSION) // version needed
            central.le16(0) // flags
            central.le16(STORED_METHOD)
            central.le16(0) // mod time
            central.le16(0) // mod date
            central.le32(crc.value.toInt())
            central.le32(data.size)
            central.le32(data.size)
            central.le16(nameBytes.size)
            central.le16(0) // extra field length
            central.le16(0) // comment length
            central.le16(0) // disk number start
            central.le16(0) // internal attributes
            central.le32(0) // external attributes
            central.le32(offset)
            central.write(nameBytes)
        }
        val end = ByteArrayOutputStream().apply {
            le32(END_OF_CENTRAL_DIRECTORY_SIG)
            le16(0) // this disk
            le16(0) // disk with the central directory
            le16(entries.size) // entries on this disk
            le16(entries.size) // entries in total
            le32(central.size())
            le32(body.size()) // central directory offset
            le16(0) // comment length
        }
        val zip = File(System.getProperty("java.io.tmpdir"), "dict-entries-${System.nanoTime()}.zip")
        zip.outputStream().use { out ->
            out.write(body.toByteArray())
            out.write(central.toByteArray())
            out.write(end.toByteArray())
        }
        return zip
    }

    private fun ByteArrayOutputStream.le16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.le32(value: Int) {
        le16(value and 0xffff)
        le16((value ushr 16) and 0xffff)
    }

    private companion object {
        const val TITLE = "TestDict"
        const val OLD_INDEX = """{"title":"Old Revision"}"""

        // The three records a reader needs, little-endian, and the one method we write.
        const val LOCAL_HEADER_SIG = 0x04034b50
        const val CENTRAL_HEADER_SIG = 0x02014b50
        const val END_OF_CENTRAL_DIRECTORY_SIG = 0x06054b50
        const val ZIP_VERSION = 20
        const val STORED_METHOD = 0
    }
}

/** The message a cancelled import carries, so both cancellation tests can tell it apart. */
private const val IMPORT_CANCELLED = "left the import screen"

/** The success payload of an outcome, or `null` — keeps the pre-outcome assertions readable. */
private fun DictionaryImportOutcome.importedDictionary(): ImportedDictionary? =
    (this as? DictionaryImportOutcome.Imported)?.dictionary

private class FakeEngine(
    private val succeeds: Boolean = true,
    /** Mirrors the engine rejecting a file it cannot read as a Yomitan ZIP. */
    private val accepts: (File) -> Boolean = { true },
    /** The `index.json` title the engine reports — untrusted input in the real importer. */
    private val title: String = "TestDict",
    /** Frequency entries in the imported dictionary; drives which type dirs the import targets. */
    private val freqCount: Int = 0,
    /** Mirrors the caller being cancelled mid-import (the screen was left). */
    private val cancels: Boolean = false,
) : DictionaryEngine {
    override val isAvailable: Boolean = true

    val sessions = CopyOnWriteArrayList<FakeSession>()
    val usedAfterClose = AtomicInteger()
    val lookupEntered = CountDownLatch(1)
    val importEntered = CountDownLatch(1)
    val importCalls = AtomicInteger()

    @Volatile
    private var lookupRelease: CountDownLatch? = null

    @Volatile
    private var importRelease: CountDownLatch? = null

    fun blockNextLookup() {
        lookupRelease = CountDownLatch(1)
    }

    fun releaseLookup() {
        lookupRelease?.countDown()
        lookupRelease = null
    }

    fun blockNextImport() {
        importRelease = CountDownLatch(1)
    }

    fun releaseImport() {
        importRelease?.countDown()
        importRelease = null
    }

    override suspend fun importDictionary(
        zip: File,
        outputDir: File,
        onProgress: (ImportProgress) -> Unit,
    ): ImportResult {
        importCalls.incrementAndGet()
        if (cancels) throw CancellationException(IMPORT_CANCELLED)
        importRelease?.let { release ->
            importEntered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val ok = succeeds && accepts(zip)
        if (ok) {
            val imported = File(outputDir, "imported").apply { mkdirs() }
            File(imported, "index.json").writeText("""{"title":"New Revision"}""")
            File(imported, "fresh.marker").writeText("new")
        }
        return ImportResult(
            success = ok,
            title = title,
            termCount = if (ok) 1 else 0,
            metaCount = 0,
            freqCount = if (ok) freqCount.toLong() else 0L,
            pitchCount = 0,
            kanjiCount = 0,
            mediaCount = 0,
        )
    }

    override fun probeEntryTypes(dictDir: File): EntryTypeCounts = EntryTypeCounts(0, 0, 0, 0)

    override fun openSession(paths: DictionaryPaths): DictionarySession = FakeSession(this).also { sessions += it }

    fun awaitLookupReleaseIfBlocking() {
        val release = lookupRelease ?: return
        lookupEntered.countDown()
        release.await(5, TimeUnit.SECONDS)
    }
}

/** Models one non-thread-safe native handle: touching it after `close` is the native crash we guard. */
private class FakeSession(
    private val engine: FakeEngine,
) : DictionarySession {
    @Volatile
    var closed: Boolean = false
        private set

    var closeCount: Int = 0
        private set
    var lookupCalls: Int = 0
        private set
    var rebuilds: Int = 0
        private set

    override fun lookup(text: String, maxResults: Int, scanLength: Int): List<LookupResult> {
        if (closed) engine.usedAfterClose.incrementAndGet()
        lookupCalls += 1
        engine.awaitLookupReleaseIfBlocking()
        return listOf(SAMPLE_RESULT)
    }

    override fun query(text: String): List<TermResult> = emptyList()

    override fun queryKanji(character: String): KanjiResult = KanjiResult(character, emptyList())

    override fun styles(): List<DictionaryStyle> = listOf(SAMPLE_STYLE)

    override fun mediaBytes(dictName: String, path: String): ByteArray? = null

    override fun rebuild(paths: DictionaryPaths) {
        if (closed) engine.usedAfterClose.incrementAndGet()
        rebuilds += 1
    }

    override fun close() {
        closeCount += 1
        closed = true
    }
}

private val SAMPLE_STYLE = DictionaryStyle(dictName = "dict", styles = "")
private val SAMPLE_RESULT = LookupResult(
    matched = "食べる",
    deinflected = "食べる",
    process = listOf(TransformGroup(name = "v1", description = "non-past")),
    term = TermResult(
        expression = "食べる",
        reading = "たべる",
        rules = "v1",
        score = 10,
        glossaries = emptyList(),
        frequencies = emptyList(),
        pitches = emptyList(),
    ),
    preprocessorSteps = 0,
)
// KUN <--
