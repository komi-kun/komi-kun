// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.ui

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import mihon.core.dictionary.model.DictionaryProfile
import mihon.core.dictionary.model.Frequency
import mihon.core.dictionary.model.FrequencyEntry
import mihon.core.dictionary.model.GlossaryEntry
import mihon.core.dictionary.model.LookupResult
import mihon.core.dictionary.model.TermResult
import mihon.core.dictionary.model.TransformGroup
import org.junit.jupiter.api.Test

/**
 * T1 gate for [orderLookupResultsForDisplay] and the Anki switches in [buildConfigPayload]
 * (final review B2 + should-fix #4; spec `docs/design/yomitan-spec/02-lookup-algorithm.md` §c).
 *
 * The comparator is exercised rule by rule: every fixture holds the higher-priority rules equal so
 * exactly one rule decides the order.
 */
class LookupOrderingTest {

    private val context: Context = mockk()
    private val profile = DictionaryProfile(id = "p", name = "P", dictionaryOrder = listOf("A", "B"))
    private val displayName: DictionaryDisplayNameProvider = { it }

    private fun glossary(dict: String = "A", text: String = "def") =
        GlossaryEntry(dictName = dict, glossary = text, definitionTags = "", termTags = "")

    private fun result(
        matched: String,
        deinflected: String = matched,
        expression: String = matched,
        reading: String = matched,
        process: List<TransformGroup> = emptyList(),
        preprocessorSteps: Int = 0,
        score: Int = 0,
        glossaries: List<GlossaryEntry> = listOf(glossary()),
        frequencies: List<FrequencyEntry> = emptyList(),
    ) = LookupResult(
        matched = matched,
        deinflected = deinflected,
        process = process,
        term = TermResult(
            expression = expression,
            reading = reading,
            rules = "",
            score = score,
            glossaries = glossaries,
            frequencies = frequencies,
            pitches = emptyList(),
        ),
        preprocessorSteps = preprocessorSteps,
    )

    private fun order(results: List<LookupResult>): List<String> =
        orderLookupResultsForDisplay(results, profile, context, displayName).map { it.term.expression }

    @Test
    fun `rule 2 - longer matched text ranks first`() {
        val short = result(matched = "ab", expression = "ab")
        val long = result(matched = "abcd", expression = "abcd")
        order(listOf(short, long)) shouldBe listOf("abcd", "ab")
        order(listOf(long, short)) shouldBe listOf("abcd", "ab")
    }

    @Test
    fun `rule 3 - shorter processor chain ranks first`() {
        val chained = result(matched = "abc", preprocessorSteps = 2, glossaries = listOf(glossary(text = "chained")))
        val plain = result(matched = "abc", preprocessorSteps = 0, glossaries = listOf(glossary(text = "plain")))
        orderLookupResultsForDisplay(listOf(chained, plain), profile, context, displayName)
            .map { it.term.glossaries.first().glossary } shouldBe listOf("plain", "chained")
    }

    @Test
    fun `rule 4 - shorter inflection chain ranks first`() {
        val step = TransformGroup(name = "v1", description = "dictionary form")
        val long = result(matched = "abc", process = listOf(step, step), glossaries = listOf(glossary(text = "long")))
        val short = result(matched = "abc", process = emptyList(), glossaries = listOf(glossary(text = "short")))
        orderLookupResultsForDisplay(listOf(long, short), profile, context, displayName)
            .map { it.term.glossaries.first().glossary } shouldBe listOf("short", "long")
    }

    @Test
    fun `rule 5 - exact term hit ranks before a reading-only hit`() {
        val exact = result(matched = "abc", deinflected = "abc", expression = "abc", glossaries = listOf(glossary(text = "exact")))
        val viaReading = result(matched = "abc", deinflected = "xyz", expression = "abc", glossaries = listOf(glossary(text = "viaReading")))
        orderLookupResultsForDisplay(listOf(viaReading, exact), profile, context, displayName)
            .map { it.term.glossaries.first().glossary } shouldBe listOf("exact", "viaReading")
    }

    @Test
    fun `rule 6 - lower frequency value ranks first`() {
        val rare = result(matched = "abc", frequencies = listOf(FrequencyEntry("A", listOf(Frequency(500, "500")))))
        val common = result(matched = "abc", frequencies = listOf(FrequencyEntry("A", listOf(Frequency(100, "100")))))
        orderLookupResultsForDisplay(listOf(rare, common), profile, context, displayName)
            .map { it.term.frequencies.firstOrNull()?.frequencies?.first()?.value } shouldBe listOf(100, 500)
    }

    @Test
    fun `rule 6 - occurrence-based frequency ranks higher count first`() {
        setFrequencyModeForTesting("OccurrenceDict", "occurrence-based")
        val rare = result(matched = "abc", frequencies = listOf(FrequencyEntry("OccurrenceDict", listOf(Frequency(100, "100")))))
        val common = result(matched = "abc", frequencies = listOf(FrequencyEntry("OccurrenceDict", listOf(Frequency(50000, "50000")))))
        orderLookupResultsForDisplay(listOf(rare, common), profile, context, displayName)
            .map { it.term.frequencies.firstOrNull()?.frequencies?.first()?.value } shouldBe listOf(50000, 100)
    }

    @Test
    fun `rule 7 - higher-priority dictionary ranks first`() {
        val lowPriority = result(matched = "abc", glossaries = listOf(glossary(dict = "B")))
        val highPriority = result(matched = "abc", glossaries = listOf(glossary(dict = "A")))
        orderLookupResultsForDisplay(listOf(lowPriority, highPriority), profile, context, displayName)
            .map { it.term.glossaries.first().dictName } shouldBe listOf("A", "B")
    }

    @Test
    fun `rule 8 - higher score ranks first`() {
        val low = result(matched = "abc", score = 1)
        val high = result(matched = "abc", score = 5)
        orderLookupResultsForDisplay(listOf(low, high), profile, context, displayName)
            .map { it.term.score } shouldBe listOf(5, 1)
    }

    @Test
    fun `rule 9 - longer headword ranks first`() {
        val short = result(matched = "xxx", deinflected = "ab", expression = "ab")
        val long = result(matched = "xxx", deinflected = "abcd", expression = "abcd")
        orderLookupResultsForDisplay(listOf(short, long), profile, context, displayName)
            .map { it.term.expression } shouldBe listOf("abcd", "ab")
    }

    @Test
    fun `rule 9 - equal-length headwords use the en-US collator`() {
        val ba = result(matched = "xxx", deinflected = "ba", expression = "ba")
        val ab = result(matched = "xxx", deinflected = "ab", expression = "ab")
        orderLookupResultsForDisplay(listOf(ba, ab), profile, context, displayName)
            .map { it.term.expression } shouldBe listOf("ab", "ba")
    }

    @Test
    fun `rule 10 - more definitions rank first`() {
        val one = result(matched = "abc", glossaries = listOf(glossary(text = "one")))
        val two = result(matched = "abc", glossaries = listOf(glossary(text = "two-a"), glossary(text = "two-b")))
        orderLookupResultsForDisplay(listOf(one, two), profile, context, displayName)
            .map { it.term.glossaries.size } shouldBe listOf(2, 1)
    }

    @Test
    fun `ties keep the engine order`() {
        val first = result(matched = "abc", glossaries = listOf(glossary(text = "first")))
        val second = result(matched = "abc", glossaries = listOf(glossary(text = "second")))
        orderLookupResultsForDisplay(listOf(first, second), profile, context, displayName)
            .map { it.term.glossaries.first().glossary } shouldBe listOf("first", "second")
    }

    @Test
    fun `ordering still applies when no dictionary order is configured`() {
        val noOrder = DictionaryProfile(id = "q", name = "Q")
        val short = result(matched = "ab", expression = "ab")
        val long = result(matched = "abcd", expression = "abcd")
        orderLookupResultsForDisplay(listOf(short, long), noOrder, context, displayName)
            .map { it.term.expression } shouldBe listOf("abcd", "ab")
    }

    @Test
    fun `glossaries within one result follow the dictionary priority`() {
        val multi = result(matched = "abc", glossaries = listOf(glossary(dict = "B"), glossary(dict = "A")))
        orderLookupResultsForDisplay(listOf(multi), profile, context, displayName)
            .single().term.glossaries.map { it.dictName } shouldBe listOf("A", "B")
    }

    @Test
    fun `buildConfigPayload exposes the live anki switches`() {
        val payload = buildConfigPayload(
            context = context,
            styles = emptyList(),
            mediaDataUris = emptyMap(),
            placeholder = "",
            isDark = false,
            showFrequencyHarmonic = false,
            showFrequencyAverage = false,
            groupTerms = true,
            showPitchDiagram = false,
            showPitchNumber = false,
            showPitchText = false,
            wordAudioAutoplay = false,
            activeProfile = profile,
            ankiEnabled = true,
            ankiDupAction = "overwrite",
            displayNameProvider = displayName,
        )
        payload["ankiEnabled"]!!.jsonPrimitive.boolean shouldBe true
        payload["ankiDupAction"]!!.jsonPrimitive.content shouldBe "overwrite"
    }
}
// KUN <--
