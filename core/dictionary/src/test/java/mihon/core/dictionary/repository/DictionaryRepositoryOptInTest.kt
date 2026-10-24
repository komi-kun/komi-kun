// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Opt-in gate tests for the dictionary repository. Until the user enables the feature the engine
// must never be touched (no import, no session, no lookup, no kanji/media reads) and a lookup must
// not serve the previous session's results or styles.
//
// The disabled cases deliberately establish a *real* working session first (available engine, open
// session, stubbed styles/results/kanji/media) and only then turn the preference off, so removing a
// gate cannot pass by accident (a relaxed `isAvailable == false` or an absent session would have
// produced the same empty/null result). Each disabled case also pins that the engine/session call
// count did not move after the switch was turned off. The enabled cases are the positive control —
// they fail if a gate accidentally blocks the real path.
// KUN <--
package mihon.core.dictionary.repository

import android.content.Context
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import mihon.core.dictionary.engine.DictionaryEngine
import mihon.core.dictionary.engine.DictionarySession
import mihon.core.dictionary.model.DictionaryPaths
import mihon.core.dictionary.model.DictionaryStyle
import mihon.core.dictionary.model.EntryTypeCounts
import mihon.core.dictionary.model.GlossaryEntry
import mihon.core.dictionary.model.ImportResult
import mihon.core.dictionary.model.KanjiEntry
import mihon.core.dictionary.model.KanjiResult
import mihon.core.dictionary.model.LookupResult
import mihon.core.dictionary.model.TermResult
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File

class DictionaryRepositoryOptInTest {

    private val engine = mockk<DictionaryEngine>()
    private val session = mockk<DictionarySession>()
    private val context = mockk<Context>(relaxed = true)

    private val paths = DictionaryPaths(termPaths = listOf("/dicts/term/jmdict"))
    private val otherPaths = DictionaryPaths(termPaths = listOf("/dicts/term/jitendex"))
    private val styles = listOf(DictionaryStyle(dictName = "jmdict", styles = ".x{}"))

    private val term = TermResult(
        expression = "猫",
        reading = "ねこ",
        rules = "",
        score = 100,
        glossaries = listOf(
            // A direct image path, so `loadMediaAsync` really asks the session for media bytes.
            GlossaryEntry(dictName = "jmdict", glossary = "img/stroke.png", definitionTags = "", termTags = ""),
        ),
        frequencies = emptyList(),
        pitches = emptyList(),
    )
    private val lookupResult = LookupResult(
        matched = "猫",
        deinflected = "猫",
        process = emptyList(),
        term = term,
        preprocessorSteps = 0,
    )
    private val kanjiResult = KanjiResult(
        character = "猫",
        entries = listOf(
            KanjiEntry(
                dictName = "jmdict",
                onyomi = "ビョウ",
                kunyomi = "ねこ",
                tags = "",
                definitions = listOf("cat"),
                stats = emptyMap(),
            ),
        ),
    )

    /**
     * Makes the engine look like a fully working install: available, one open session that serves
     * the styles and the term/kanji results above.
     */
    private fun availableEngine() {
        every { engine.isAvailable } returns true
        every { engine.openSession(paths) } returns session
        every { session.rebuild(any()) } just Runs
        every { session.styles() } returns styles
        every { session.lookup("猫", 20, 25) } returns listOf(lookupResult)
        every { session.queryKanji("猫") } returns kanjiResult
    }

    /** `enabled = false` mirrors the real post-decline state (the key is stored `false`). */
    private fun preferences(enabled: Boolean) =
        DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(enabled) }

    private fun repository(preferences: DictionaryOcrPreferences) =
        DictionaryRepository(engine = engine, context = context, dictionaryOcr = preferences)

    /**
     * A repository whose session is already open (the state a user reaches by using the feature),
     * with the preference then turned off.
     */
    private fun warmRepositoryThenDisable(): DictionaryRepository {
        availableEngine()
        val preferences = preferences(enabled = true)
        val repository = repository(preferences)
        repository.warmUp(paths, profileId = "profile")
        verify(exactly = 1) { engine.openSession(paths) }

        preferences.choose(false)
        return repository
    }

    // ------------------------------------------------------------------ import / probe

    @Test
    fun `importing reports a failure and never calls the engine while disabled`(): Unit = runBlocking {
        coEvery { engine.importDictionary(any(), any(), any()) } returns ImportResult(
            success = true,
            title = "jmdict",
            termCount = 42,
            metaCount = 0,
            freqCount = 0,
            pitchCount = 0,
            kanjiCount = 0,
            mediaCount = 0,
        )

        val result = repository(preferences(enabled = false)).importDictionary(File("jpdict.zip"), File("out"))

        result.success shouldBe false
        result.errors.size shouldBe 1
        result.title shouldBe ""
        result.termCount shouldBe 0L
        coVerify(exactly = 0) { engine.importDictionary(any(), any(), any()) }
    }

    @Test
    fun `importing still delegates to the engine while enabled`(): Unit = runBlocking {
        coEvery { engine.importDictionary(any(), any(), any()) } returns ImportResult(
            success = true,
            title = "jmdict",
            termCount = 42,
            metaCount = 0,
            freqCount = 0,
            pitchCount = 0,
            kanjiCount = 0,
            mediaCount = 0,
        )

        val result = repository(preferences(enabled = true)).importDictionary(File("jpdict.zip"), File("out"))

        result.success shouldBe true
        result.title shouldBe "jmdict"
        result.termCount shouldBe 42L
        coVerify(exactly = 1) { engine.importDictionary(any(), any(), any()) }
    }

    @Test
    fun `probing entry types returns zeros without the engine while disabled`() {
        every { engine.probeEntryTypes(any()) } returns EntryTypeCounts(term = 7, freq = 8, pitch = 9, kanji = 10)

        repository(preferences(enabled = false)).probeEntryTypes(File("dicts/term/jmdict")) shouldBe
            EntryTypeCounts(term = 0, freq = 0, pitch = 0, kanji = 0)

        verify(exactly = 0) { engine.probeEntryTypes(any()) }
    }

    // ------------------------------------------------------------------ warm up

    @Test
    fun `warming up opens no session while disabled`() {
        availableEngine()

        repository(preferences(enabled = false)).warmUp(paths, profileId = "profile")

        verify(exactly = 0) { engine.isAvailable }
        verify(exactly = 0) { engine.openSession(any()) }
        verify(exactly = 0) { session.rebuild(any()) }
    }

    @Test
    fun `warming up opens and rebuilds a session while enabled`() {
        availableEngine()

        repository(preferences(enabled = true)).warmUp(paths, profileId = "profile")

        verify(exactly = 1) { engine.openSession(paths) }
        verify(exactly = 1) { session.rebuild(paths) }
    }

    @Test
    fun `warming up again after the feature is turned off does not touch the session`() {
        val repository = warmRepositoryThenDisable()

        // Different paths on purpose: an ungated warmUp would rebuild the open session for them.
        repository.warmUp(otherPaths, profileId = "other")

        verify(exactly = 1) { engine.isAvailable }
        verify(exactly = 1) { engine.openSession(any()) }
        verify(exactly = 1) { session.rebuild(any()) }
    }

    // ------------------------------------------------------------------ lookup

    @Test
    fun `lookups return nothing and open no session while disabled`() {
        availableEngine()

        val result = repository(preferences(enabled = false)).lookup("猫", paths)

        result.results.shouldBeEmpty()
        result.styles.shouldBeEmpty()
        result.mediaDataUris shouldBe emptyMap()
        result.error shouldBe null
        verify(exactly = 0) { engine.isAvailable }
        verify(exactly = 0) { engine.openSession(any()) }
        verify(exactly = 0) { session.lookup(any(), any(), any()) }
    }

    @Test
    fun `lookups after the feature is turned off do not serve the open session or its styles`() {
        val repository = warmRepositoryThenDisable()

        val result = repository.lookup("猫", paths)

        // The session below *would* answer — it is open, available and stubbed to return a result.
        result.results.shouldBeEmpty()
        result.styles.shouldBeEmpty()
        verify(exactly = 1) { engine.isAvailable } // the pre-disable warm-up only
        verify(exactly = 1) { engine.openSession(any()) } // the pre-disable warm-up only
        verify(exactly = 0) { session.lookup(any(), any(), any()) }
    }

    @Test
    fun `lookups still delegate to the session while enabled`() {
        availableEngine()

        val result = repository(preferences(enabled = true)).lookup("猫", paths)

        result.results shouldBe listOf(lookupResult)
        result.styles shouldBe styles
        verify(exactly = 1) { session.lookup("猫", 20, 25) }
    }

    // ------------------------------------------------------------------ kanji / media

    @Test
    fun `kanji queries after the feature is turned off do not reach the open session`() {
        val repository = warmRepositoryThenDisable()

        repository.queryKanji("猫") shouldBe null

        // The open session is stubbed to return a kanji result; the gate must not ask it.
        verify(exactly = 0) { session.queryKanji(any()) }
        verify(exactly = 1) { engine.openSession(any()) } // the pre-disable warm-up only
    }

    @Test
    fun `media loading after the feature is turned off does not resolve media through the session`() {
        val repository = warmRepositoryThenDisable()
        every { session.mediaBytes(any(), any()) } returns null

        val media = repository.loadMediaAsync(results = listOf(lookupResult))

        media shouldBe emptyMap()
        verify(exactly = 0) { session.mediaBytes(any(), any()) }
    }

    @Test
    fun `media loading still resolves the referenced media while enabled`() {
        availableEngine()
        // `null` bytes keep `android.util.Base64` (unavailable on the JVM stub) out of the path; the
        // assertion is that the non-empty lookup input really reaches the session's media lookup.
        every { session.mediaBytes(any(), any()) } returns null
        val repository = repository(preferences(enabled = true))
        repository.warmUp(paths, profileId = "profile")

        repository.loadMediaAsync(results = listOf(lookupResult)) shouldBe emptyMap()

        verify(exactly = 1) { session.mediaBytes("jmdict", "img/stroke.png") }
    }

    @Test
    fun `closing still releases an open session while disabled`() {
        val repository = warmRepositoryThenDisable()
        every { session.close() } just Runs

        repository.close()

        verify(exactly = 1) { session.close() }
    }
}
