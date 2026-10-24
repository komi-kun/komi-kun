// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Pins the import call site: the delete-and-copy of the live dictionary tree must run through
// `DictionaryRepository.withDictionaryTreeMutation` (session destroyed first, repository monitor held
// for the whole mutation), so a concurrent lookup/media read can never see a half-replaced tree, and
// a same-title re-import is picked up instead of being served from the pre-import native query.
package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import android.util.Log
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
import mihon.core.dictionary.model.dictionaryTypeDir
import mihon.core.dictionary.repository.DictionaryRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

        val imported = DictionaryImportCoordinator.importFromZip(h.context, File("broken.zip"), h.prefs, h.repository)

        imported shouldBe null
        File(h.termDir, "old.marker").exists() shouldBe true
        File(h.termDir, "index.json").readText() shouldBe OLD_INDEX
        session.closed shouldBe false
        session.closeCount shouldBe 0
        h.engine.sessions.size shouldBe 1
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

        val imported = DictionaryImportCoordinator.importFromZip(h.context, File("unused.zip"), h.prefs, h.repository)

        imported shouldBe null
        // The tree was partially mutated, so the pre-mutation session must not survive it.
        session.closed shouldBe true
        session.closeCount shouldBe 1
        // The next lookup opens a session over whatever is actually on disk.
        h.repository.lookup("食べる", DictionaryPaths(termPaths = listOf("/nope")))
        h.engine.sessions.size shouldBe 2
        h.engine.usedAfterClose.get() shouldBe 0
    }

    @Test
    fun `the coordinator cannot touch the tree while a lookup holds the session`() {
        val h = harness()
        h.seedOldTree()
        h.engine.blockNextLookup()
        val lookup = thread(name = "lookup") { h.repository.lookup("食べる", h.paths) }
        h.engine.lookupEntered.await(5, TimeUnit.SECONDS) shouldBe true

        val importDone = AtomicBoolean(false)
        var imported: ImportedDictionary? = null
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
        imported?.title shouldBe TITLE
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

    private class Harness(
        val context: Context,
        val prefs: DictionaryPreferences,
        val engine: FakeEngine,
        val repository: DictionaryRepository,
    ) {
        val termDir: File get() = dictionaryTypeDir(context, "term", TITLE)
        val paths: DictionaryPaths get() = DictionaryPaths(termPaths = listOf(termDir.absolutePath))

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

    private fun harness(engineSucceeds: Boolean = true): Harness {
        val root = File(System.getProperty("java.io.tmpdir"), "dict-coordinator-${System.nanoTime()}")
        val cache = File(root, "cache").apply { mkdirs() }
        val files = File(root, "files").apply { mkdirs() }
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns cache
        every { context.getExternalFilesDir(null) } returns files
        val engine = FakeEngine(succeeds = engineSucceeds)
        return Harness(
            context = context,
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

    private companion object {
        const val TITLE = "TestDict"
        const val OLD_INDEX = """{"title":"Old Revision"}"""
    }
}

private class FakeEngine(
    private val succeeds: Boolean = true,
) : DictionaryEngine {
    override val isAvailable: Boolean = true

    val sessions = CopyOnWriteArrayList<FakeSession>()
    val usedAfterClose = AtomicInteger()
    val lookupEntered = CountDownLatch(1)

    @Volatile
    private var lookupRelease: CountDownLatch? = null

    fun blockNextLookup() {
        lookupRelease = CountDownLatch(1)
    }

    fun releaseLookup() {
        lookupRelease?.countDown()
        lookupRelease = null
    }

    override suspend fun importDictionary(
        zip: File,
        outputDir: File,
        onProgress: (ImportProgress) -> Unit,
    ): ImportResult {
        if (succeeds) {
            val imported = File(outputDir, "imported").apply { mkdirs() }
            File(imported, "index.json").writeText("""{"title":"New Revision"}""")
            File(imported, "fresh.marker").writeText("new")
        }
        return ImportResult(
            success = succeeds,
            title = "TestDict",
            termCount = if (succeeds) 1 else 0,
            metaCount = 0,
            freqCount = 0,
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
