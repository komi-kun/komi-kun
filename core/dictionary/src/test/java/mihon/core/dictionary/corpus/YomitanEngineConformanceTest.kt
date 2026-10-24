// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.corpus

import io.kotest.assertions.fail
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.core.dictionary.engine.DictionaryTransforms
import mihon.core.dictionary.engine.HoshiDictionaryEngine
import mihon.core.dictionary.model.DictionaryPaths
import mihon.core.dictionary.ui.KanaConverter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * **T2 engine conformance** (`docs/design/implementation/05-testing-and-anki.md` §A1.5): executes the
 * committed Yomitan corpus against the *real* vendored `hoshidicts` engine — deinflection, the
 * preprocessing pipeline, dictionary import and `lookup`/`query` — instead of only validating the
 * corpus schema (that is [YomitanCorpusTest], T1).
 *
 * The Android `.so` is an NDK cross-build, so this runner loads a **host** build of the same
 * `hoshidicts_jni` bridge:
 *
 * ```
 * ./gradlew :core:dictionary:conformanceHostTest
 * ```
 *
 * ### Oracle semantics
 *
 * The upstream oracle is `test/fixtures/language-transformer-test.js` at the pinned Yomitan commit
 * (see `manifest.json`): a tuple conforms when `transform(source)` yields a candidate with
 * `text == term`, `trace.map(f => f.transform)` equal to `reasons`, and — when `rule` is set —
 * `conditionsMatch(conditions, rule)`. Three mappings are needed because the engine is a C++ port,
 * not the JavaScript implementation:
 *
 * 1. **Trace order.** Yomitan *prepends* each new trace frame (`_extendTrace`), so its `reasons`
 *    list is the reverse of the order the rules were applied. The engine appends (see
 *    [DictionaryTransforms]), so `reasons` is compared against `trace.map { it.name }.reversed()`.
 * 2. **Where each expectation runs.** `convertAlphabeticToKana` is an app-side helper
 *    ([KanaConverter], the IME path), not part of the C++ engine, so it is asserted against that
 *    implementation. `normalizeCJKCompatibilityCharacters` expectations are *intermediate* Yomitan
 *    values (compatibility char → kana + combining dakuten) because Yomitan composes them in the
 *    following `normalizeCombiningCharacters` step; the engine fuses both into one NFKC step, so
 *    the expectation is compared after `NFKC`.
 * 3. **Unmappable POS.** The engine's `pos_to_conditions` ORs the masks of the tags it knows
 *    (`v1/v5/vk/vs/vz/adj-i`) and **ignores** the rest, so a mixed list yields a partial mask rather
 *    than `0` (see [DictionaryTransforms]). The `rule` check therefore only runs when the mask is
 *    non-zero; the single `v1d` row is text+trace-checked and pinned explicitly below.
 *
 * ### What is not hidden
 *
 * Every expectation the engine cannot meet is committed in `yomitan/engine-divergences-ja.json` with
 * the exact corpus row and the engine gap that explains it. The ledger is checked in **both**
 * directions: an unlisted failure fails the test, and a listed row that starts passing fails it too
 * ("stale divergence"), so the gap set can never drift silently.
 */
class YomitanEngineConformanceTest {

    private val json = Json

    // --- Deinflection ----------------------------------------------------------------------

    @Test
    fun `every transform tuple conforms to the Yomitan oracle or is a recorded divergence`() {
        val rows = readTransformRows()
        rows.size shouldBe 1446

        val verdicts = rows.associate { row -> row.index to DeinflectionVerdict.of(row) }
        val divergences = divergenceIndexes("ja-transforms.jsonl")

        // A recorded divergence must still be a real corpus row and must really not conform.
        divergences.forEach { (index, entry) ->
            val row = rows.getOrNull(index)
                ?: error("ledger references ja-transforms.jsonl:$index, past the end of the corpus (${rows.size} rows)")
            row.source shouldBe entry.subject
            row.term shouldBe entry.detail
            verdicts.getValue(index).conforms shouldBe false
        }

        val failing = verdicts.filterValues { !it.conforms }.keys
        if (failing != divergences.keys) {
            // Name the rows, not just the indexes: an unlisted failure is a regression and a listed
            // row that now passes is a stale exemption.
            fail(
                buildString {
                    appendLine("conformance verdicts drifted from engine-divergences-ja.json")
                    (failing - divergences.keys).forEach { index ->
                        val row = rows[index]
                        appendLine(
                            "  unlisted failure #$index [${row.category}] ${row.source} -> " +
                                "${row.term} reasons=${row.reasons} rule=${row.rule}",
                        )
                    }
                    (divergences.keys - failing).forEach { index ->
                        appendLine("  stale divergence #$index ${rows[index].source} -> ${rows[index].term} (delete the entry)")
                    }
                },
            )
        }

        val positives = rows.filter { it.valid }
        positives.size shouldBe 1396
        positives.count { verdicts.getValue(it.index).conforms } shouldBe 1262
        positives.count { !verdicts.getValue(it.index).conforms } shouldBe 134

        // Negative categories ("incorrect chains", …) assert that *no* candidate matches at all.
        val negatives = rows.filterNot { it.valid }
        negatives.size shouldBe 50
        negatives.count { verdicts.getValue(it.index).conforms } shouldBe 50

        // The engine POS map has no `v1d`, so exactly that row is text+trace-checked only.
        verdicts.filterValues { it.conditionUnchecked }.keys shouldBe setOf(
            rows.single { it.rule == "v1d" }.index,
        )

        // Gap distribution: a new failure class cannot be absorbed by an existing gap id.
        divergences.values.groupingBy { it.gap }.eachCount() shouldBe TRANSFORM_DIVERGENCE_GAPS
    }

    // --- Normalization ---------------------------------------------------------------------

    @Test
    fun `every normalization tuple conforms to the Yomitan oracle or is a recorded divergence`() {
        val rows = readNormalizationRows()
        rows.size shouldBe 405
        rows.groupingBy { it.op }.eachCount() shouldBe mapOf(
            "normalizeCombiningCharacters" to 163,
            "normalizeCJKCompatibilityCharacters" to 149,
            "collapseEmphaticSequences" to 54,
            "convertKatakanaToHiragana" to 11,
            "convertHiraganaToKatakana" to 9,
            "convertHalfWidthKanaToFullWidth" to 8,
            "convertAlphabeticToKana" to 7,
            "convertAlphanumericToFullWidth" to 4,
        )

        val failing = rows.filterNot { normalizationConforms(it) }.map { it.index }
        val divergences = divergenceIndexes("ja-normalization.jsonl")

        divergences.forEach { (index, entry) ->
            val row = rows.getOrNull(index)
                ?: error("ledger references ja-normalization.jsonl:$index, past the end of the corpus (${rows.size} rows)")
            row.op shouldBe entry.subject
            row.input shouldBe entry.detail
            normalizationConforms(row) shouldBe false
        }

        failing shouldBe divergences.keys.toList()

        // The op split is pinned: 397 rows match the engine output exactly, 1 is the documented
        // "keep the prolonged sound mark" gap.
        failing.size shouldBe 1
        divergences.values.single().gap shouldBe GAP_KATAKANA_KEEP_PROLONGED

        // `normalizeCJKCompatibilityCharacters` is split between exact hits and NFKC-equivalent
        // hits; pin the split so the mapping cannot quietly cover more of the op than it does now.
        val cjk = rows.filter { it.op == "normalizeCJKCompatibilityCharacters" }
        cjk.count { row -> DictionaryTransforms.normalizationVariants(row.input).contains(row.expected) } shouldBe 107
        cjk.count { row -> nfkc(row.expected) != row.expected } shouldBe 42

        // The engine has no keep-mode: it collapses the mark instead of keeping it.
        val keepRow = rows.single { it.args["keepProlongedSoundMarks"] == true }
        DictionaryTransforms.normalizationVariants(keepRow.input) shouldContain "かあなあ"
    }

    /**
     * The engine exposes one fused preprocessing pipeline, so an expectation is "reachable as a
     * variant". Op-specific adapters are documented in the class doc.
     */
    private fun normalizationConforms(row: NormalizationRow): Boolean {
        if (row.op == "convertAlphabeticToKana") {
            // App-side IME helper (Yomitan's `convertAlphabeticToKana`); not part of the C++ engine.
            return KanaConverter.convertAlphabeticToKana(row.input) == row.expected
        }
        val variants = DictionaryTransforms.normalizationVariants(row.input)
        return when (row.op) {
            // Yomitan returns the decomposed compatibility form; the engine's NFKC step composes it.
            "normalizeCJKCompatibilityCharacters" -> nfkc(row.expected) in variants
            // Everything else is a direct variant lookup. The single
            // `convertKatakanaToHiragana` + `keepProlongedSoundMarks: true` row has no reachable
            // variant: `katakana_to_hiragana` always rewrites the mark (recorded in the ledger).
            else -> row.expected in variants
        }
    }

    // --- Lookup / query --------------------------------------------------------------------

    @Test
    fun `lookup and query execute the synthetic dictionary fixture`(@TempDir tempDir: File) {
        val dictionary = readDictionaryFixture()
        val queries = readLookupRows()
        dictionary.size shouldBe 24
        queries.size shouldBe 12

        val engine = HoshiDictionaryEngine()
        engine.isAvailable shouldBe true

        val zip = buildFixtureZip(File(tempDir, "komikun-synthetic-ja.zip"), dictionary)
        val importResult = runBlocking { engine.importDictionary(zip, File(tempDir, "dictionaries")) }
        importResult.success shouldBe true
        importResult.title shouldBe SYNTHETIC_DICT
        importResult.termCount shouldBe 24L
        importResult.errors shouldBe emptyList()

        val dictionaryDir = File(File(tempDir, "dictionaries"), SYNTHETIC_DICT)
        dictionaryDir.isDirectory shouldBe true

        val session = engine.openSession(DictionaryPaths(termPaths = listOf(dictionaryDir.absolutePath)))
        try {
            for (query in queries) {
                val results = session.lookup(query.query)
                val expectation = query.expect.single()
                val reasons = { steps: List<mihon.core.dictionary.model.TransformGroup> ->
                    steps.map { it.name }.reversed()
                }

                val matching = results.filter { result ->
                    result.term.expression == expectation.expression &&
                        result.term.reading == expectation.reading &&
                        reasons(result.process) == expectation.reasons
                }
                withClue(query) {
                    matching.isNotEmpty() shouldBe true
                    // Ranking: the longest matched span first, then the shortest trace — for these
                    // fixtures the deinflected candidate is therefore the top hit.
                    results.first().term.expression shouldBe expectation.expression
                    reasons(results.first().process) shouldBe expectation.reasons
                    // The engine reports the span it scanned and the form it matched in the dict.
                    results.first().matched shouldBe query.query
                    results.first().deinflected shouldBe expectation.expression
                }

                // `query` is the exact-expression path; it must return the fixture entry as stored.
                val exact = session.query(expectation.expression)
                val entry = dictionary.single {
                    it.expression == expectation.expression && it.reading == expectation.reading
                }
                withClue(query) {
                    exact.map { term -> term.expression to term.reading } shouldContain
                        (expectation.expression to expectation.reading)
                    exact.single { term -> term.reading == expectation.reading }.rules shouldBe
                        entry.rules.joinToString(" ")
                }
            }
        } finally {
            session.close()
        }
    }

    // --- Host engine identity ---------------------------------------------------------------

    /**
     * The corpus assertions only mean something if the library the JVM loaded is the one the host
     * build produced from the *current* engine sources. Gradle already rebuilds the library when
     * anything under `src/main/cpp` changes and re-runs this task when the library's bytes change;
     * this test closes the remaining gap at run time: the digest baked into the loaded library must
     * match the build record written next to it, and the file on disk must still hash to that
     * record's `sha256`. A hand-copied, half-rebuilt or otherwise stale engine therefore fails here
     * instead of quietly producing verdicts for the wrong binary.
     */
    @Test
    fun `the executed engine matches the recorded host build`() {
        val libraryDir = System.getProperty("java.library.path").orEmpty()
        libraryDir shouldNotBe ""
        val buildInfoFile = File(libraryDir, "BUILD_INFO.txt")
        buildInfoFile.isFile shouldBe true

        val info = buildInfoFile.readLines()
            .filter { '=' in it }
            .associate { line -> line.substringBefore('=') to line.substringAfter('=') }

        val library = File(info.getValue("library"))
        library.isFile shouldBe true
        info.getValue("sha256") shouldBe sha256Hex(library.readBytes())
        info.getValue("bytes").toLong() shouldBe library.length()

        // The digest baked into the *loaded* library, not just the file we can see.
        val fingerprint = DictionaryTransforms.buildFingerprint()
        fingerprint.isNotBlank() shouldBe true
        fingerprint shouldBe info.getValue("source_fingerprint")

        println(
            "T2 engine: ${library.name} ${info.getValue("bytes")} bytes " +
                "sha256=${info.getValue("sha256")} engine=${info.getValue("source_fingerprint")} " +
                "compiler=${info.getValue("cxx_version")}",
        )
    }

    // --- Corpus readers --------------------------------------------------------------------

    private fun readTransformRows(): List<TransformRow> =
        readJsonl("ja-transforms.jsonl").mapIndexed { index, record ->
            TransformRow(
                index = index,
                category = record.string("category"),
                source = record.string("source"),
                term = record.string("term"),
                rule = record.stringOrNull("rule"),
                reasons = record.stringListOrNull("reasons"),
                valid = record.getValue("valid").jsonPrimitive.boolean,
            )
        }

    private fun readNormalizationRows(): List<NormalizationRow> =
        readJsonl("ja-normalization.jsonl").mapIndexed { index, record ->
            NormalizationRow(
                index = index,
                op = record.string("op"),
                args = record.getValue("args").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.boolean },
                input = record.string("input"),
                expected = record.string("expected"),
            )
        }

    private fun readLookupRows(): List<LookupRow> =
        readJsonl("lookup-ja.jsonl").map { record ->
            record.string("dict") shouldBe SYNTHETIC_DICT
            LookupRow(
                query = record.string("query"),
                expect = record.getValue("expect").jsonArray.map { element ->
                    val candidate = element.jsonObject
                    LookupExpectation(
                        expression = candidate.string("expression"),
                        reading = candidate.string("reading"),
                        reasons = candidate.getValue("rules").jsonArray.map { it.jsonPrimitive.content },
                    )
                },
            )
        }

    private fun readDictionaryFixture(): List<DictionaryEntry> =
        readJsonl("lookup-ja.dict.jsonl").map { record ->
            record.string("dict") shouldBe SYNTHETIC_DICT
            DictionaryEntry(
                expression = record.string("expression"),
                reading = record.string("reading"),
                rules = record.getValue("rules").jsonArray.map { it.jsonPrimitive.content },
            )
        }

    private fun readJsonl(name: String): List<JsonObject> =
        resourceBytes(name).toString(Charsets.UTF_8)
            .trimEnd('\n')
            .split('\n')
            .map { line -> json.parseToJsonElement(line).jsonObject }

    // --- Divergence ledger -----------------------------------------------------------------

    private fun divergenceIndexes(corpus: String): Map<Int, DivergenceEntry> {
        val root = json.parseToJsonElement(resourceBytes(DIVERGENCE_LEDGER).toString(Charsets.UTF_8)).jsonObject
        root.getValue("spdx").jsonPrimitive.content shouldBe "GPL-3.0-or-later"
        val gaps = root.getValue("gaps").jsonObject.keys

        val entries = root.getValue("entries").jsonArray
            .map { element -> element.jsonObject }
            .filter { it.string("corpus") == corpus }
            .map { entry ->
                val gap = entry.string("gap")
                require(gap in gaps) { "ledger entry for $corpus uses unknown gap '$gap'" }
                val index = entry.string("index").toInt()
                index to DivergenceEntry(
                    gap = gap,
                    subject = entry.string("subject"),
                    detail = entry.string("detail"),
                )
            }
        entries.map { it.first }.toSet().size shouldBe entries.size
        return entries.toMap()
    }

    // --- Synthetic Yomitan dictionary ------------------------------------------------------

    /**
     * Write the `lookup-ja.dict.jsonl` fixture as a real Yomitan (format 3) dictionary ZIP so the
     * engine's own importer, blob/hash layout and query are exercised end to end.
     *
     * Term bank tuple: `[expression, reading, definitionTags, rules, score, glossary, sequence,
     * termTags]` (Yomitan dictionary format).
     */
    private fun buildFixtureZip(zip: File, entries: List<DictionaryEntry>): File {
        val index = JsonObject(
            mapOf(
                "title" to JsonPrimitive(SYNTHETIC_DICT),
                "format" to JsonPrimitive(3),
                "revision" to JsonPrimitive("conformance"),
                "isUpdatable" to JsonPrimitive(false),
                "indexUrl" to JsonPrimitive(""),
                "downloadUrl" to JsonPrimitive(""),
            ),
        )
        val termBank = JsonArray(
            entries.mapIndexed { sequence, entry ->
                JsonArray(
                    listOf(
                        JsonPrimitive(entry.expression),
                        JsonPrimitive(entry.reading),
                        JsonPrimitive(""),
                        JsonPrimitive(entry.rules.joinToString(" ")),
                        JsonPrimitive(0),
                        JsonArray(listOf(JsonPrimitive("conformance fixture for ${entry.expression}"))),
                        JsonPrimitive(sequence),
                        JsonPrimitive(""),
                    ),
                )
            },
        )

        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            writeZipEntry(out, "index.json", index.toString())
            writeZipEntry(out, "term_bank_1.json", termBank.toString())
            writeZipEntry(out, "styles.css", "/* conformance fixture */\n")
        }
        return zip
    }

    private fun writeZipEntry(out: ZipOutputStream, name: String, contents: String) {
        out.putNextEntry(ZipEntry(name))
        out.write(contents.toByteArray(Charsets.UTF_8))
        out.closeEntry()
    }

    // --- Helpers ---------------------------------------------------------------------------

    private fun nfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun resourceBytes(name: String): ByteArray =
        javaClass.getResourceAsStream("/yomitan/$name")?.readBytes()
            ?: error("missing test resource /yomitan/$name")

    private inline fun <T> withClue(row: LookupRow, block: () -> T): T =
        try {
            block()
        } catch (error: AssertionError) {
            throw AssertionError("lookup fixture '${row.query}': ${error.message}", error)
        }

    private data class TransformRow(
        val index: Int,
        val category: String,
        val source: String,
        val term: String,
        val rule: String?,
        val reasons: List<String>?,
        val valid: Boolean,
    )

    private data class NormalizationRow(
        val index: Int,
        val op: String,
        val args: Map<String, Boolean>,
        val input: String,
        val expected: String,
    )

    private data class LookupRow(val query: String, val expect: List<LookupExpectation>)

    private data class LookupExpectation(val expression: String, val reading: String, val reasons: List<String>)

    private data class DictionaryEntry(val expression: String, val reading: String, val rules: List<String>)

    /** `subject`/`detail` identify the corpus row (source/term for transforms, op/input for normalization). */
    private data class DivergenceEntry(val gap: String, val subject: String, val detail: String)

    /**
     * The upstream `hasTermReasons` oracle with the documented trace-order mapping.
     *
     * [conditionUnchecked] marks rows whose `rule` the engine's POS map cannot express (`v1d`):
     * those are verified on text + trace only.
     */
    private data class DeinflectionVerdict(val conforms: Boolean, val conditionUnchecked: Boolean) {
        companion object {
            fun of(row: TransformRow): DeinflectionVerdict {
                val expectedConditions = row.rule?.let { rule -> DictionaryTransforms.posConditions(listOf(rule)) }
                val conditionUnchecked = expectedConditions == 0
                val has = DictionaryTransforms.deinflect(row.source).any { candidate ->
                    candidate.text == row.term &&
                        (row.reasons == null || candidate.trace.map { it.name }.reversed() == row.reasons) &&
                        (
                            expectedConditions == null || conditionUnchecked ||
                                conditionsMatch(candidate.conditions, expectedConditions)
                            )
                }
                return DeinflectionVerdict(conforms = has == row.valid, conditionUnchecked = conditionUnchecked)
            }

            /** Yomitan `LanguageTransformer.conditionsMatch`: `0` means "no condition constraint". */
            private fun conditionsMatch(current: Int, expected: Int): Boolean =
                current == 0 || (current and expected) != 0
        }
    }

    private companion object {
        private const val SYNTHETIC_DICT = "komikun-synthetic-ja"
        private const val DIVERGENCE_LEDGER = "engine-divergences-ja.json"
        private const val GAP_KATAKANA_KEEP_PROLONGED =
            "katakana-to-hiragana-keep-prolonged-sound-marks-unsupported"

        /** Per-gap ledger counts; see `yomitan/engine-divergences-ja.json` and the module README. */
        private val TRANSFORM_DIVERGENCE_GAPS = mapOf(
            "historical-kana-forms-unsupported" to 93,
            "missing-transform-group:-やしない" to 35,
            "single-character-input-identity-only" to 4,
            "missing-transform-group:-ふ" to 2,
        )
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.stringOrNull(key: String): String? = when (val value = this[key]) {
    null, is JsonNull -> null
    else -> value.jsonPrimitive.content
}

private fun JsonObject.stringListOrNull(key: String): List<String>? = when (val value = this[key]) {
    null, is JsonNull -> null
    else -> value.jsonArray.map { it.jsonPrimitive.content }
}
