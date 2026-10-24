// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.repository

import android.content.Context
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mihon.core.dictionary.engine.DictionaryEngine
import mihon.core.dictionary.engine.DictionarySession
import mihon.core.dictionary.engine.ImportProgress
import mihon.core.dictionary.model.DictionaryPaths
import mihon.core.dictionary.model.DictionaryStyle
import mihon.core.dictionary.model.EntryTypeCounts
import mihon.core.dictionary.model.ImportResult
import mihon.core.dictionary.model.KanjiResult
import mihon.core.dictionary.model.LookupResult
import mihon.core.dictionary.model.TermResult
import mihon.core.dictionary.model.TransformGroup
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/**
 * Session lifecycle and serialisation (gap: `DictionaryRepository.close()` had no callers, and the
 * dictionary tree was mutated outside any session lock).
 *
 * The seam is the [DictionaryEngine]/[DictionarySession] interfaces — the real collaborators the
 * repository drives — so the fakes model the property that matters: a session wraps a single
 * non-thread-safe native handle that is destroyed on close. Every call, **including `close()`**, is
 * traced for in-use overlap, use-after-close, and a close that lands while a call is in flight; each
 * of those is a real native use-after-free in `hoshidicts_jni`.
 *
 * Not covered here (no JVM-reachable seam): the app hooks `App.onStop`; the coordinator call site is
 * covered by `app/src/test/.../DictionaryImportCoordinatorTest.kt`.
 */
class DictionaryRepositoryLifecycleTest {

    private fun repositoryWith(engine: FakeEngine) = DictionaryRepository(engine, mockk<Context>(relaxed = true))

    private val paths = DictionaryPaths(termPaths = listOf("/dict/term/a"))

    @Test
    fun `an unchanged dictionary set keeps one session and rebuilds once`() {
        val engine = FakeEngine()
        val repository = repositoryWith(engine)

        val first = repository.lookup("食べる", paths)
        val second = repository.lookup("飲む", paths)

        engine.sessions.size shouldBe 1
        engine.sessions[0].rebuilds shouldBe 1
        engine.sessions[0].lookupCalls shouldBe 2
        first.results shouldBe listOf(SAMPLE_RESULT)
        second.styles shouldBe listOf(SAMPLE_STYLE)
    }

    @Test
    fun `a changed path rebuilds the existing session instead of leaking a second one`() {
        val engine = FakeEngine()
        val repository = repositoryWith(engine)

        repository.lookup("食べる", paths)
        repository.lookup("食べる", DictionaryPaths(termPaths = listOf("/dict/term/a", "/dict/term/b")))

        engine.sessions.size shouldBe 1
        engine.sessions[0].rebuilds shouldBe 2
    }

    @Test
    fun `close drops the session and the next lookup reopens a fresh one`() {
        val engine = FakeEngine()
        val repository = repositoryWith(engine)

        repository.lookup("食べる", paths)
        repository.close()
        val afterClose = repository.lookup("飲む", paths)

        val (first, reopened) = engine.sessions
        first.closed shouldBe true
        first.closeCount shouldBe 1
        first.lookupCalls shouldBe 1
        reopened.closed shouldBe false
        reopened.rebuilds shouldBe 1
        reopened.lookupCalls shouldBe 1
        afterClose.results shouldBe listOf(SAMPLE_RESULT)
        engine.usedAfterClose.get() shouldBe 0
    }

    @Test
    fun `close is idempotent and destroys the native handle only once`() {
        val engine = FakeEngine()
        val repository = repositoryWith(engine)

        repository.lookup("食べる", paths)
        repository.close()
        repository.close()
        repository.close()

        engine.sessions.size shouldBe 1
        engine.sessions[0].closeCount shouldBe 1
    }

    @Test
    fun `close without an open session is a no-op`() {
        val engine = FakeEngine()
        val repository = repositoryWith(engine)

        repository.close()

        engine.sessions.size shouldBe 0
        repository.lookup("食べる", paths).results shouldBe listOf(SAMPLE_RESULT)
    }

    @Test
    fun `an engine import alone does not touch the open session`() = runBlocking<Unit> {
        val engine = FakeEngine()
        val repository = repositoryWith(engine)
        repository.lookup("食べる", paths)

        // The engine import only writes the ZIP into a scratch directory; the live tree (and so the
        // session) changes only inside `withDictionaryTreeMutation`.
        repository.importDictionary(File("scratch.zip"), File("scratch-out"))
        repository.lookup("飲む", paths)

        engine.importCalls shouldBe 1
        engine.sessions.size shouldBe 1
        engine.sessions[0].closed shouldBe false
        engine.sessions[0].closeCount shouldBe 0
        engine.sessions[0].rebuilds shouldBe 1
        engine.sessions[0].lookupCalls shouldBe 2
    }

    @Test
    fun `the tree mutation runs with the session already destroyed and leaves none behind`() {
        val engine = FakeEngine()
        val repository = repositoryWith(engine)
        repository.lookup("食べる", paths)
        val before = engine.sessions.single()

        var closedWhenMutationRan = false
        var closesWhenMutationRan = -1
        repository.withDictionaryTreeMutation {
            closedWhenMutationRan = before.closed
            closesWhenMutationRan = before.closeCount
        }

        closedWhenMutationRan shouldBe true
        closesWhenMutationRan shouldBe 1
        // Destroyed once (before the mutation); the post-mutation close has nothing to destroy.
        before.closeCount shouldBe 1
        engine.sessions.size shouldBe 1
    }

    @Test
    fun `a same-title replacement is picked up by the next lookup`() {
        val engine = FakeEngine()
        val repository = repositoryWith(engine)
        repository.lookup("食べる", paths)

        // The real tree would be deleted and re-copied here; the paths are byte-identical, which is
        // exactly what made this stale before `withDictionaryTreeMutation`.
        repository.withDictionaryTreeMutation { }

        repository.lookup("食べる", paths)

        val (before, after) = engine.sessions
        engine.sessions.size shouldBe 2
        before.closed shouldBe true
        before.lookupCalls shouldBe 1
        after.closed shouldBe false
        after.rebuilds shouldBe 1
        after.lookupCalls shouldBe 1
        engine.usedAfterClose.get() shouldBe 0
    }

    @Test
    fun `a throwing tree mutation still destroys the session and reopens lazily`() {
        val engine = FakeEngine()
        val repository = repositoryWith(engine)
        repository.lookup("食べる", paths)

        val thrown = runCatching {
            repository.withDictionaryTreeMutation<Unit> { throw IllegalStateException("copy failed") }
        }.exceptionOrNull()

        thrown?.message shouldBe "copy failed"
        engine.sessions.single().closed shouldBe true
        engine.sessions.single().closeCount shouldBe 1

        // A partially copied tree is never served from the pre-mutation query.
        repository.lookup("食べる", paths)
        engine.sessions.size shouldBe 2
        engine.usedAfterClose.get() shouldBe 0
    }

    @Test
    fun `the tree mutation waits for an in-flight lookup before it runs`() {
        val engine = FakeEngine()
        val repository = repositoryWith(engine)
        engine.blockNextLookup()

        val lookupThread = thread(name = "lookup") { repository.lookup("食べる", paths) }
        engine.lookupEntered.await(5, TimeUnit.SECONDS) shouldBe true

        var mutationRan = false
        val mutationThread = thread(name = "mutation") {
            repository.withDictionaryTreeMutation {
                // Appended with the repository monitor held, like the fake's "lookup" event.
                engine.events += "mutation"
                mutationRan = true
            }
        }

        // The lookup holds the repository monitor, so the mutation cannot start (and therefore
        // cannot delete half of the on-disk tree) until it is released.
        Thread.sleep(200)
        mutationRan shouldBe false

        engine.releaseLookup()
        lookupThread.join(5_000)
        mutationThread.join(5_000)

        mutationRan shouldBe true
        engine.events.toList() shouldBe listOf("lookup", "mutation")
        engine.usedAfterClose.get() shouldBe 0
        engine.closedDuringCall.get() shouldBe 0
        engine.overlaps.get() shouldBe 0
    }

    @Test
    fun `no lookup can run while the tree mutation is in progress`() {
        val engine = FakeEngine()
        val repository = repositoryWith(engine)

        val mutationEntered = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val mutationThread = thread(name = "mutation") {
            repository.withDictionaryTreeMutation {
                mutationEntered.countDown()
                releaseMutation.await(5, TimeUnit.SECONDS)
                engine.events += "mutation"
            }
        }
        mutationEntered.await(5, TimeUnit.SECONDS) shouldBe true

        val lookupThread = thread(name = "lookup") { repository.lookup("食べる", paths) }
        // The monitor is held for the whole delete/copy, so the lookup can neither open a session
        // over the half-replaced tree nor run before the mutation finishes.
        Thread.sleep(200)
        engine.sessions.isEmpty() shouldBe true
        engine.events.toList() shouldBe emptyList()

        releaseMutation.countDown()
        mutationThread.join(5_000)
        lookupThread.join(5_000)

        engine.events.toList() shouldBe listOf("mutation", "lookup")
        engine.sessions.size shouldBe 1
        engine.usedAfterClose.get() shouldBe 0
    }

    @Test
    fun `concurrent lookups, closes and tree mutations never overlap or reuse a closed handle`() =
        runBlocking<Unit> {
            val engine = FakeEngine(traceMicros = 100)
            val repository = repositoryWith(engine)
            val other = DictionaryPaths(termPaths = listOf("/dict/term/a", "/dict/term/b"))

            val readers = (0 until 8).map { worker ->
                launch(Dispatchers.Default) {
                    repeat(50) { i -> repository.lookup("w$worker-$i", if (i % 7 == 0) other else paths) }
                }
            }
            // The `App.onStop` path: a bare close, not wrapped in a mutation.
            val closers = (0 until 2).map {
                launch(Dispatchers.Default) { repeat(50) { repository.close() } }
            }
            val replacements = (0 until 2).map {
                launch(Dispatchers.Default) { repeat(50) { repository.withDictionaryTreeMutation { } } }
            }
            (readers + closers + replacements).joinAll()

            // An unsynchronised close is visible three ways: a call into the destroyed handle, a
            // handle destroyed under an in-flight call, or two callers inside one handle.
            withClue("native call on a destroyed handle") { engine.usedAfterClose.get() shouldBe 0 }
            withClue("handle destroyed while a call was in flight") { engine.closedDuringCall.get() shouldBe 0 }
            withClue("two callers inside one handle") { engine.overlaps.get() shouldBe 0 }
            withClue("peak concurrent handle use") { engine.peakConcurrentUse.get() shouldBe 1 }
            // Non-vacuous: the closes/replacements really interleaved with the readers and every one
            // of the 400 lookups reached a session (a racing close can also make one return empty).
            (engine.sessions.size > 1) shouldBe true
            engine.sessions.sumOf { it.lookupCalls } shouldBe 400
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

/**
 * One fake "native" handle. Every entry — `lookup`/`rebuild`/`mediaBytes`/`close` — is traced on the
 * engine's shared counters, so the test can prove the repository serialises access and never touches
 * a destroyed handle.
 */
private class FakeSession(
    private val engine: FakeEngine,
) : DictionarySession {
    private val lock = Any()

    @Volatile
    var closed: Boolean = false

    /** Every `destroySession` the repository issued; a second call is a native double free. */
    var closeCount: Int = 0
        private set
    var lookupCalls: Int = 0
        private set
    var rebuilds: Int = 0
        private set

    override fun lookup(text: String, maxResults: Int, scanLength: Int): List<LookupResult> = traced {
        lookupCalls += 1
        engine.awaitLookupReleaseIfBlocking()
        // Recorded inside the session call, i.e. while the repository monitor is still held, so the
        // ordering against `mutation` below is a total order and not a cross-thread race.
        engine.events += "lookup"
        listOf(SAMPLE_RESULT)
    }

    override fun query(text: String): List<TermResult> = traced { emptyList() }

    override fun queryKanji(character: String): KanjiResult = traced { KanjiResult(character, emptyList()) }

    override fun styles(): List<DictionaryStyle> = traced { listOf(SAMPLE_STYLE) }

    override fun mediaBytes(dictName: String, path: String): ByteArray? = traced { null }

    override fun rebuild(paths: DictionaryPaths) = traced { rebuilds += 1 }

    /**
     * Closing joins the same trace: an unsynchronised close would show up as two callers inside one
     * handle, or as a call that entered while open and left after it was destroyed.
     */
    override fun close() {
        engine.enter()
        try {
            synchronized(lock) {
                closeCount += 1
                closed = true
            }
        } finally {
            engine.exit()
        }
    }

    private inline fun <T> traced(block: () -> T): T {
        val wasOpen = !closed
        if (!wasOpen) engine.usedAfterClose.incrementAndGet()
        engine.enter()
        try {
            return block()
        } finally {
            if (wasOpen && closed) engine.closedDuringCall.incrementAndGet()
            engine.exit()
        }
    }
}

private class FakeEngine(
    /** Widen the traced critical section so a missing monitor shows up as overlap, not luck. */
    val traceMicros: Long = 0,
) : DictionaryEngine {
    override val isAvailable: Boolean = true

    val sessions = CopyOnWriteArrayList<FakeSession>()
    val inUse = AtomicInteger()
    val peakConcurrentUse = AtomicInteger()
    val overlaps = AtomicInteger()
    val usedAfterClose = AtomicInteger()
    val closedDuringCall = AtomicInteger()

    /** Events appended while a session call holds the repository monitor; see the wait test. */
    val events = CopyOnWriteArrayList<String>()

    val lookupEntered = CountDownLatch(1)
    private var lookupRelease: CountDownLatch? = null

    @Volatile
    var importCalls: Int = 0

    /** Make the next lookup block inside the session until [releaseLookup], to pin the monitor. */
    fun blockNextLookup() {
        lookupRelease = CountDownLatch(1)
    }

    fun awaitLookupReleaseIfBlocking() {
        val release = lookupRelease
        if (release == null) return
        lookupEntered.countDown()
        release.await(5, TimeUnit.SECONDS)
    }

    fun releaseLookup() {
        lookupRelease?.countDown()
        lookupRelease = null
    }

    fun enter() {
        val now = inUse.incrementAndGet()
        peakConcurrentUse.updateAndGet { peak -> maxOf(peak, now) }
        if (now > 1) overlaps.incrementAndGet()
        if (traceMicros > 0) LockSupport.parkNanos(traceMicros * 1_000L)
    }

    fun exit() {
        inUse.decrementAndGet()
    }

    override suspend fun importDictionary(
        zip: File,
        outputDir: File,
        onProgress: (ImportProgress) -> Unit,
    ): ImportResult {
        importCalls += 1
        return IMPORT_RESULT
    }

    override fun probeEntryTypes(dictDir: File): EntryTypeCounts = EntryTypeCounts(0, 0, 0, 0)

    override fun openSession(paths: DictionaryPaths): DictionarySession =
        FakeSession(this).also { sessions += it }
}

private val IMPORT_RESULT = ImportResult(
    success = true,
    title = "a",
    termCount = 1,
    metaCount = 0,
    freqCount = 0,
    pitchCount = 0,
    kanjiCount = 0,
    mediaCount = 0,
)
// KUN <--
