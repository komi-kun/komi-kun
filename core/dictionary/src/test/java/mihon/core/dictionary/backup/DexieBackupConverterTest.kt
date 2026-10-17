// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.backup

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * Fast, deterministic tests for [DexieBackupConverter] over a small synthetic Dexie fixture that
 * covers every hazard: both row encodings, an interleaved `terms` (and `media`) sequence, a base64
 * media block, structured-content glossary, a legacy term row missing `sequence`/`termTags`, an
 * unknown store/key, and a malformed row.
 */
class DexieBackupConverterTest {

    @TempDir
    lateinit var temp: File

    private val fixtureJson: String
        get() = requireNotNull(javaClass.getResource("/backup/dexie-small.json")).readText()

    private fun converter(bankSize: Int = DexieBackupConverter.DEFAULT_BANK_SIZE) =
        DexieBackupConverter(bankSize) { input ->
            TreeJsonSource(Json.parseToJsonElement(input.readBytes().decodeToString()))
        }

    private fun input() = ByteArrayInputStream(fixtureJson.toByteArray())

    private fun staging() = File(temp, "stage")

    private fun convert(
        bankSize: Int = DexieBackupConverter.DEFAULT_BANK_SIZE,
        shouldInclude: (String) -> Boolean = { true },
    ): BackupConversionResult = runBlocking {
        converter(bankSize).convert(
            input = input(),
            stagingDir = staging(),
            shouldInclude = shouldInclude,
        )
    }

    private fun entryText(zip: File, name: String): String = ZipFile(zip).use { zf ->
        val entry = requireNotNull(zf.getEntry(name)) { "missing entry $name in ${zip.name}" }
        zf.getInputStream(entry).readBytes().decodeToString()
    }

    private fun entryBytes(zip: File, name: String): ByteArray = ZipFile(zip).use { zf ->
        val entry = requireNotNull(zf.getEntry(name)) { "missing entry $name in ${zip.name}" }
        zf.getInputStream(entry).readBytes()
    }

    private fun entryNames(zip: File): List<String> = ZipFile(zip).use { zf ->
        zf.entries().asSequence().map { it.name }.toList()
    }

    private fun bank(zip: File, name: String): List<JsonArray> =
        Json.parseToJsonElement(entryText(zip, name)).jsonArray.map { it.jsonArray }

    private fun zipFor(result: BackupConversionResult, title: String): File =
        result.staged.first { it.title == title }.zip

    @Test
    fun `streams interleaved dictionaries into separate V3 banks`() {
        val result = convert()

        result.staged.map { it.title } shouldContainExactly listOf("Synth A", "Synth B", "Synth Kanji")
        result.skippedTitles shouldBe emptyList()
        result.totalRows shouldBe 20L
        result.rowsRead shouldBe 21L
        result.malformedRows shouldBe 1L

        // Interleaved `terms` routed by the `dictionary` field, in encounter order, not by table order.
        val aTerms = bank(zipFor(result, "Synth A"), "term_bank_1.json")
        aTerms.map { it[0].jsonPrimitive.content } shouldContainExactly listOf("水", "亜", "山")
        val bTerms = bank(zipFor(result, "Synth B"), "term_bank_1.json")
        bTerms.map { it[0].jsonPrimitive.content } shouldContainExactly listOf("川", "人")

        // Legacy V1 row (亜) is padded to the fixed 8-element V3 shape.
        val aze = aTerms.first { it[0].jsonPrimitive.content == "亜" }
        aze.size shouldBe 8
        aze[6].jsonPrimitive.content shouldBe "0" // sequence
        aze[7].jsonPrimitive.content shouldBe "" // termTags

        // Structured-content glossary passes through untouched.
        aze[5].jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content shouldBe "structured-content"
    }

    @Test
    fun `writes index, empty styles and the meta and tag banks`() {
        val result = convert()

        val a = zipFor(result, "Synth A")
        entryNames(a).containsAll(
            listOf("index.json", "styles.css", "term_bank_1.json", "term_meta_bank_1.json", "tag_bank_1.json"),
        ) shouldBe true
        entryText(a, "styles.css") shouldBe ""

        val index = Json.parseToJsonElement(entryText(a, "index.json")).jsonObject
        index["title"]!!.jsonPrimitive.content shouldBe "Synth A"
        index["format"]!!.jsonPrimitive.content shouldBe "3"
        index["author"]!!.jsonPrimitive.content shouldBe "Tester"
        // Dropped keys from the DB row must not leak into index.json.
        index.containsKey("counts") shouldBe false
        index.containsKey("importDate") shouldBe false
        index.containsKey("someFutureKey") shouldBe false

        // term_meta: the malformed empty-expression row is skipped, the two valid ones remain.
        val termMeta = bank(a, "term_meta_bank_1.json")
        termMeta.map { it[0].jsonPrimitive.content } shouldContainExactly listOf("水", "山")
        termMeta[0][1].jsonPrimitive.content shouldBe "pitch"
        termMeta[0][2].jsonObject["pitches"]!!.jsonArray[0].jsonObject["position"]!!.jsonPrimitive.content shouldBe "1"

        bank(a, "tag_bank_1.json").map { it[0].jsonPrimitive.content } shouldContainExactly listOf("N5")

        // kanji store: `meanings` maps to the 5th bank element for the kanji-only dictionary.
        val c = zipFor(result, "Synth Kanji")
        val kanji = bank(c, "kanji_bank_1.json")
        kanji.map { it[0].jsonPrimitive.content } shouldContainExactly listOf("亜", "人")
        val aze = kanji.first { it[0].jsonPrimitive.content == "亜" }
        aze[3].jsonPrimitive.content shouldBe "jouyou"
        aze[4].jsonArray.map { it.jsonPrimitive.content } shouldContainExactly listOf("Asia", "rank next")
        aze[5].jsonObject["strokes"]!!.jsonPrimitive.content shouldBe "7"
        bank(c, "kanji_meta_bank_1.json").map { it[0].jsonPrimitive.content } shouldContainExactly listOf("人")
    }

    @Test
    fun `decodes base64 media to real files and blocks path traversal`() {
        val result = convert()
        val expected = java.util.Base64.getMimeDecoder().decode(PNG_1X1)

        val a = zipFor(result, "Synth A")
        entryBytes(a, "images/a.png").toList() shouldBe expected.toList()
        // "../escape.png" must be flattened to "escape.png", never written outside the dictionary.
        entryBytes(a, "escape.png").toList() shouldBe expected.toList()
        entryNames(a).any { it.contains("..") } shouldBe false
        entryBytes(zipFor(result, "Synth B"), "images/b.png").toList() shouldBe expected.toList()
    }

    @Test
    fun `rotates bank files at the configured size`() {
        val result = convert(bankSize = 2)
        val a = zipFor(result, "Synth A")
        entryNames(a).contains("term_bank_1.json") shouldBe true
        entryNames(a).contains("term_bank_2.json") shouldBe true
    }

    @Test
    fun `shouldInclude skips dictionaries without staging them`() {
        val result = convert(shouldInclude = { it != "Synth B" })
        result.staged.map { it.title } shouldContainExactly listOf("Synth A", "Synth Kanji")
        result.skippedTitles shouldContainExactly listOf("Synth B")
        result.staged.none { it.title == "Synth B" } shouldBe true
    }

    @Test
    fun `rejects a file that is not a dexie export`() {
        assertThrows<BackupFormatException> {
            runBlocking {
                converter().convert(
                    input = ByteArrayInputStream("""{"formatName":"not-dexie"}""".toByteArray()),
                    stagingDir = staging(),
                )
            }
        }
    }

    @Test
    fun `cancellation stops the finalize loop`() = runBlocking {
        val staged = mutableListOf<String>()
        val firstReady = CompletableDeferred<Unit>()
        val job: Job = launch(Dispatchers.Default) {
            converter().convert(
                input = input(),
                stagingDir = staging(),
                onDictionaryReady = { dictionary ->
                    staged += dictionary.title
                    firstReady.complete(Unit)
                    awaitCancellation()
                },
            )
        }
        firstReady.await()
        job.cancelAndJoin()
        staged shouldContainExactly listOf("Synth A")
    }

    private companion object {
        const val PNG_1X1 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    }
}
// KUN <--
