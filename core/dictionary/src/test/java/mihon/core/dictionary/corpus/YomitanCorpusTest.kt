// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.corpus

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.security.MessageDigest

/**
 * T1 "corpus is sane" gate (plan 05 §A1.5): parses every committed Yomitan corpus file, checks
 * it against the committed `manifest.json` (sha256 + record count) and validates the schema of
 * each tuple. No native engine is required — the T2/T3 conformance runners (W5) consume the same
 * files.
 *
 * The manifest check here is also the in-CI sync gate (plan 05 §A1.3/§A4): `testDebugUnitTest`
 * already runs on every PR, so drift between the committed corpus and its manifest fails CI.
 * `tools/yomitan-corpus/verify-manifest.mjs` is the standalone equivalent.
 */
@Execution(ExecutionMode.CONCURRENT)
class YomitanCorpusTest {

    private val manifest: Map<String, Any?> by lazy {
        jsonFile("manifest.json")
    }

    // --- Manifest / drift ------------------------------------------------------------------

    @Test
    fun `manifest records the pinned Yomitan provenance and the upstream inputs`() {
        manifest["spdx"] shouldBe "GPL-3.0-or-later"

        val yomitan = manifest.obj("yomitan")
        yomitan["commit"] shouldBe "e7334a96106a6c74e6fa75bd33a5ea1219d92712"
        yomitan["commitShort"] shouldBe "e7334a9"

        manifest.obj("inputs").keys shouldBe setOf(
            "test/language/japanese-transforms.test.js",
            "test/japanese-util.test.js",
        )
    }

    @Test
    fun `every corpus file's sha256 and record count match the manifest`() {
        val outputs = manifest.obj("outputs")
        outputs.isNotEmpty() shouldBe true

        for ((name, raw) in outputs) {
            val entry = raw as? Map<*, *> ?: error("manifest.outputs.$name must be an object")
            val bytes = resourceBytes(name)
            sha256Hex(bytes) shouldBe entry["sha256"]
            bytes.size.toLong() shouldBe entry["bytes"]
            readRecords(name).size.toLong() shouldBe entry["count"]
        }
    }

    // --- Transforms ------------------------------------------------------------------------

    @Test
    fun `transform tuples parse and match the schema`() {
        val records = readRecords("ja-transforms.jsonl")

        records.size shouldBe 1446

        val negativeCategories = mutableSetOf<String>()
        val negativeCategoryNames = setOf(
            "irregular verbs",
            "incorrect chains",
            "incorrect short causative -> passive chains",
        )

        for ((lineNumber, record) in records.withIndex()) {
            val where = "ja-transforms.jsonl:${lineNumber + 1}"
            record.requireKeysExactly(where, "category", "source", "term", "rule", "reasons", "valid")
            record.nonBlankString("category", where)
            record.nonBlankString("source", where)
            record.nonBlankString("term", where)
            record.nullableString("rule", where)
            record.nullableStringList("reasons", where)
            record.boolean("valid", where)

            if (record["valid"] == false) {
                negativeCategories.add(record["category"] as String)
            }
        }

        negativeCategories shouldBe negativeCategoryNames
    }

    @Test
    fun `transform tuples preserve the reference deinflections from the spec`() {
        val records = bySource("ja-transforms.jsonl")

        val negativePast = records.getValue("愛しくありませんでした").single()
        negativePast["term"] shouldBe "愛しい"
        negativePast["rule"] shouldBe "adj-i"
        negativePast["reasons"] shouldBe listOf("-ます", "negative", "-た")

        records.getValue("食べさせられない").single()["reasons"] shouldBe
            listOf("causative", "potential or passive", "negative")

        records.getValue("行った").single()["term"] shouldBe "行く"
    }

    @Test
    fun `negative categories never carry a matching candidate`() {
        val records = readRecords("ja-transforms.jsonl")
            .filter { it["category"] == "incorrect chains" || it["category"] == "incorrect short causative -> passive chains" }

        records.isNotEmpty() shouldBe true
        records.all { it["valid"] == false } shouldBe true
    }

    // --- Normalization ---------------------------------------------------------------------

    @Test
    fun `normalization fixtures parse and match the schema`() {
        val knownOps = setOf(
            "convertKatakanaToHiragana",
            "convertHiraganaToKatakana",
            "convertHalfWidthKanaToFullWidth",
            "convertAlphanumericToFullWidth",
            "convertAlphabeticToKana",
            "normalizeCombiningCharacters",
            "normalizeCJKCompatibilityCharacters",
            "collapseEmphaticSequences",
        )

        val records = readRecords("ja-normalization.jsonl")
        records.size shouldBe 405

        val seenOps = mutableSetOf<String>()
        for ((lineNumber, record) in records.withIndex()) {
            val where = "ja-normalization.jsonl:${lineNumber + 1}"
            record.requireKeysExactly(where, "op", "input", "args", "expected")
            val op = record.nonBlankString("op", where)
            seenOps.add(op)
            record["input"] as? String ?: error("$where: input must be a string")
            (record["args"] as? Map<*, *>) ?: error("$where: args must be an object")
            record["expected"] as? String ?: error("$where: expected must be a string")
        }

        seenOps shouldBe knownOps
    }

    // --- Lookup ----------------------------------------------------------------------------

    @Test
    fun `lookup fixtures parse and match the schema`() {
        val dict = readRecords("lookup-ja.dict.jsonl")
        dict.size shouldBe 24
        val dictNames = dict.map { record ->
            record.requireKeysExactly("lookup-ja.dict.jsonl", "dict", "expression", "reading", "rules")
            record.nonBlankString("dict", "lookup-ja.dict.jsonl")
            record.nonBlankString("expression", "lookup-ja.dict.jsonl")
            record.nonBlankString("reading", "lookup-ja.dict.jsonl")
            record.nonBlankStringList("rules", "lookup-ja.dict.jsonl")
            record["dict"] as String
        }.toSet()
        dictNames shouldBe setOf("komikun-synthetic-ja")

        val lookups = readRecords("lookup-ja.jsonl")
        lookups.size shouldBe 12
        for ((lineNumber, record) in lookups.withIndex()) {
            val where = "lookup-ja.jsonl:${lineNumber + 1}"
            record.requireKeysExactly(where, "dict", "query", "expect")
            record.nonBlankString("dict", where)
            record.nonBlankString("query", where)
            val expect = record["expect"] as? List<*> ?: error("$where: expect must be an array")
            expect.isNotEmpty() shouldBe true
            for (candidate in expect) {
                val entry = candidate as? Map<*, *> ?: error("$where: expect entries must be objects")
                entry.keys shouldBe setOf("expression", "reading", "rules")
                entry["expression"] as? String ?: error("$where: expression must be a string")
                entry["reading"] as? String ?: error("$where: reading must be a string")
                (entry["rules"] as? List<*>)?.all { it is String } ?: error("$where: rules must be a string array")
            }
        }
    }

    // --- Helpers ---------------------------------------------------------------------------

    private fun readRecords(name: String): List<Map<String, Any?>> {
        val text = resourceBytes(name).toString(Charsets.UTF_8)
        if (text.isEmpty()) return emptyList()
        return text.trimEnd('\n').split('\n').mapIndexed { index, line ->
            (Json.parse(line) as? Map<*, *>)?.entries
                ?.associate { it.key as String to it.value }
                ?: error("$name:${index + 1}: not a JSON object")
        }
    }

    private fun bySource(name: String): Map<String, List<Map<String, Any?>>> =
        readRecords(name).groupBy { it["source"] as String }

    private fun jsonFile(name: String): Map<String, Any?> =
        (Json.parse(resourceBytes(name).toString(Charsets.UTF_8)) as? Map<*, *>)
            ?.entries
            ?.associate { it.key as String to it.value }
            ?: error("$name: not a JSON object")

    private fun resourceBytes(name: String): ByteArray =
        javaClass.getResourceAsStream("/yomitan/$name")?.readBytes()
            ?: error("missing test resource /yomitan/$name")

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private fun Map<String, Any?>.requireKeysExactly(where: String, vararg keys: String) {
    val expected = keys.toSet()
    require(this.keys == expected) { "$where: expected keys $expected but found ${this.keys}" }
}

private fun Map<String, Any?>.obj(key: String): Map<String, Any?> =
    (this[key] as? Map<*, *>)?.entries?.associate { it.key as String to it.value }
        ?: error("$key must be an object")

private fun Map<String, Any?>.nonBlankString(key: String, where: String): String =
    (this[key] as? String)?.takeIf { it.isNotBlank() } ?: error("$where: $key must be a non-blank string")

private fun Map<String, Any?>.nullableString(key: String, where: String): String? {
    val value = this[key] ?: return null
    return value as? String ?: error("$where: $key must be a string or null")
}

private fun Map<String, Any?>.boolean(key: String, where: String): Boolean =
    this[key] as? Boolean ?: error("$where: $key must be a boolean")

private fun Map<String, Any?>.nonBlankStringList(key: String, where: String): List<String> {
    val value = this[key] as? List<*> ?: error("$where: $key must be a string array")
    return value.map { it as? String ?: error("$where: $key entries must be strings") }
}

private fun Map<String, Any?>.nullableStringList(key: String, where: String): List<String>? =
    if (this[key] == null) null else nonBlankStringList(key, where)
