// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Unit tests for the ModelDownloader fixes (review iteration 1):
//  - extraction/verification share one directory (import path bug),
//  - integrity is checked against size + SHA-256, not mere existence,
//  - `<name>.part` scratch files are cleaned safely (owned names only).
// KUN <--
package mihon.core.ocr.paddle

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelDownloaderTest {

    private val detName = "ppocrv6_small_det.onnx"
    private val recName = "ppocrv6_small_rec.onnx"
    private val dictName = "ppocrv6_small_dict.txt"

    @Test
    fun `sha256Hex matches a known vector`(@TempDir dir: File) {
        val file = File(dir, "abc.bin").apply { writeBytes("abc".toByteArray()) }
        sha256Hex(file) shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }

    @Test
    fun `modelFileMatches accepts a file that matches size and hash`(@TempDir dir: File) {
        val file = File(dir, detName).apply { writeBytes("model-bytes".toByteArray()) }
        val entry = OcrModelFile(name = detName, sha256 = sha256Hex(file), size = file.length())
        modelFileMatches(file, entry) shouldBe true
    }

    @Test
    fun `modelFileMatches rejects a truncated file (wrong size)`(@TempDir dir: File) {
        val file = File(dir, recName).apply { writeBytes("short".toByteArray()) }
        val entry = OcrModelFile(name = recName, sha256 = "", size = file.length() + 1)
        modelFileMatches(file, entry) shouldBe false
    }

    @Test
    fun `modelFileMatches rejects a corrupt file (same size, wrong hash)`(@TempDir dir: File) {
        val file = File(dir, dictName).apply { writeBytes("garbage".toByteArray()) }
        val entry = OcrModelFile(name = dictName, sha256 = "0".repeat(64), size = file.length())
        modelFileMatches(file, entry) shouldBe false
    }

    @Test
    fun `modelFileMatches rejects an empty file`(@TempDir dir: File) {
        val file = File(dir, detName).apply { createNewFile() }
        modelFileMatches(file, OcrModelFile(name = detName, sha256 = "", size = 0)) shouldBe false
    }

    @Test
    fun `removeOwnedPartFiles deletes only owned part files`(@TempDir dir: File) {
        File(dir, "$detName.part").writeText("partial")
        File(dir, "some_other.part").writeText("keep me")
        File(dir, detName).writeText("model")

        val removed = removeOwnedPartFiles(dir, listOf(detName, recName))

        removed shouldContainExactly listOf("$detName.part")
        File(dir, "$detName.part").exists() shouldBe false
        File(dir, "some_other.part").exists() shouldBe true
        File(dir, detName).exists() shouldBe true
    }

    @Test
    fun `extractZipInto extracts a flat archive into the target directory`(@TempDir dir: File) {
        val modelsDir = File(dir, "paddle_ocr")
        val zip = zipOf(
            detName to "det",
            recName to "rec",
            dictName to "dict",
        )

        extractZipInto(modelsDir, ByteArrayInputStream(zip))

        File(modelsDir, detName).readText() shouldBe "det"
        File(modelsDir, recName).readText() shouldBe "rec"
        File(modelsDir, dictName).readText() shouldBe "dict"
        // Nothing leaks into the parent (the old bug wrote to filesDir, not paddle_ocr).
        File(dir, detName).exists() shouldBe false
    }

    @Test
    fun `extractZipInto rejects entries that escape the target directory`(@TempDir dir: File) {
        val modelsDir = File(dir, "paddle_ocr")
        val zip = zipOf("../evil.txt" to "pwned")

        val result = runCatching { extractZipInto(modelsDir, ByteArrayInputStream(zip)) }

        result.isFailure shouldBe true
        File(dir, "evil.txt").exists() shouldBe false
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
