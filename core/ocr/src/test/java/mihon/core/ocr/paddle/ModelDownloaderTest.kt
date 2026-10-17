// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Unit tests for the ModelDownloader fixes:
//  (review iteration 1)
//  - extraction/verification share one directory (import path bug),
//  - integrity is checked against size + SHA-256, not mere existence,
//  - `<name>.part` scratch files are cleaned safely (owned names only).
//  (review iteration 2)
//  - the synchronous `isPaddleDownloaded` gate checks size only (cheap, main-thread safe),
//  - a user archive installs only the required model files (no planted `.so` / arbitrary paths).
//  (review iteration 3)
//  - concurrent triggers are coalesced by a single-flight gate,
//  - the cached-manifest fast path is refused for a manifest from another release,
//  - pending/re-fetch and cached-manifest decisions are pure + unit-tested,
//  - a staged import is verified (hash-before-copy) before it can touch the engine directory.
//  (review iteration 4)
//  - the gate is still released when a run fails, so a retry is possible (F7),
//  - two racing threads admit exactly one run (F7),
//  - a release mismatch is refused even when `baseUrl` matches (F4),
//  - a rejected staged archive leaves the existing install untouched (F7).
// KUN <--
package mihon.core.ocr.paddle

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
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
    fun `modelFileHasExpectedSize accepts a matching size and rejects a wrong size`(@TempDir dir: File) {
        val file = File(dir, detName).apply { writeBytes("model".toByteArray()) }

        modelFileHasExpectedSize(file, OcrModelFile(name = detName, size = file.length())) shouldBe true
        modelFileHasExpectedSize(file, OcrModelFile(name = detName, size = file.length() - 1)) shouldBe false
        modelFileHasExpectedSize(file, OcrModelFile(name = detName, size = 0)) shouldBe true
        modelFileHasExpectedSize(File(dir, "missing"), OcrModelFile(name = "missing", size = 0)) shouldBe false
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

    @Test
    fun `installRequiredModels copies only the required model files`(@TempDir dir: File) {
        val scratch = File(dir, "scratch")
        val modelsDir = File(dir, "paddle_ocr")
        val zip = zipOf(
            detName to "det",
            "models/$recName" to "rec",
            "lib/arm64-v8a/libevil.so" to "ELF",
            "README.txt" to "readme",
        )

        extractZipInto(scratch, ByteArrayInputStream(zip))
        val installed = installRequiredModels(scratch, modelsDir, listOf(detName, recName, dictName))

        installed shouldContainExactly listOf(detName, recName)
        File(modelsDir, detName).readText() shouldBe "det"
        File(modelsDir, recName).readText() shouldBe "rec"
        // The copy stages through `<name>.part` and renames, so no scratch file is left behind (F6).
        File(modelsDir, "$detName.part").exists() shouldBe false
        File(modelsDir, "$recName.part").exists() shouldBe false
        // A planted shared object (or any non-model path) is never installed where the engine
        // (`loadNativeLibraries`) could `System.load` it.
        File(modelsDir, "lib/arm64-v8a/libevil.so").exists() shouldBe false
        File(modelsDir, "lib").exists() shouldBe false
        File(modelsDir, "README.txt").exists() shouldBe false
    }

    @Test
    fun `SingleFlightGate coalesces concurrent triggers until released`() {
        val gate = SingleFlightGate()

        gate.tryAcquire() shouldBe true
        gate.tryAcquire() shouldBe false
        gate.tryAcquire() shouldBe false

        gate.release()
        gate.tryAcquire() shouldBe true
    }

    @Test
    fun `SingleFlightGate admits exactly one of two racing threads`() {
        val gate = SingleFlightGate()
        val start = CountDownLatch(1)
        val acquired = Collections.synchronizedList(mutableListOf<Boolean>())
        val threads = (1..2).map {
            Thread {
                start.await()
                acquired += gate.tryAcquire()
            }
        }

        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }

        // Exactly one thread wins; the other is coalesced (real 2-thread race, not sequential).
        acquired.count { it } shouldBe 1
    }

    @Test
    fun `SingleFlightGate is released when a run fails so a retry can proceed`() {
        runBlocking {
            val gate = SingleFlightGate()

            // A run that throws must still release the gate (the `finally`), not wedge it forever.
            runCatching { gate.runExclusive<Unit> { error("download failed") } }.isFailure shouldBe true
            // The retry is not coalesced away.
            gate.runExclusive { true } shouldBe true
        }
    }

    @Test
    fun `cachedManifestIsCurrent rejects a manifest from another release`() {
        val current = OcrModelManifest(release = "ocr-models-v3", baseUrl = "https://x/ocr-models-v3")
        val old = OcrModelManifest(release = "ocr-models-v2", baseUrl = "https://x/ocr-models-v2")

        cachedManifestIsCurrent(current, "ocr-models-v3", "https://x/ocr-models-v3") shouldBe true
        cachedManifestIsCurrent(old, "ocr-models-v3", "https://x/ocr-models-v3") shouldBe false
        // `baseUrl` is the fallback identifier when the manifest omits `release`.
        cachedManifestIsCurrent(
            OcrModelManifest(baseUrl = "https://x/ocr-models-v3"),
            "ocr-models-v3",
            "https://x/ocr-models-v3",
        ) shouldBe true
    }

    @Test
    fun `cachedManifestIsCurrent refuses a release mismatch even when baseUrl matches`() {
        // The exact pinning case F4 guards: baseUrl matches the pinned release, but the manifest was
        // fetched from a different release, so it is stale.
        cachedManifestIsCurrent(
            OcrModelManifest(release = "ocr-models-v2", baseUrl = "https://x/ocr-models-v3"),
            "ocr-models-v3",
            "https://x/ocr-models-v3",
        ) shouldBe false
        // A manifest that omits `baseUrl` is no longer trivially "current" via the field default.
        cachedManifestIsCurrent(
            OcrModelManifest(release = "ocr-models-v2"),
            "ocr-models-v3",
            "https://x/ocr-models-v3",
        ) shouldBe false
        // A manifest with the pinned release but no baseUrl is still current (release is primary).
        cachedManifestIsCurrent(
            OcrModelManifest(release = "ocr-models-v3"),
            "ocr-models-v3",
            "https://x/ocr-models-v3",
        ) shouldBe true
    }

    @Test
    fun `cachedManifestIsUsable requires the pinned release and verified files`(@TempDir dir: File) {
        val det = File(dir, detName).apply { writeBytes("det".toByteArray()) }
        val rec = File(dir, recName).apply { writeBytes("rec".toByteArray()) }
        val dict = File(dir, dictName).apply { writeBytes("dict".toByteArray()) }
        val names = listOf(detName, recName, dictName)
        val manifest = OcrModelManifest(
            release = "ocr-models-v3",
            baseUrl = "https://x/ocr-models-v3",
            files = listOf(
                OcrModelFile(name = detName, sha256 = sha256Hex(det), size = det.length()),
                OcrModelFile(name = recName, sha256 = sha256Hex(rec), size = rec.length()),
                OcrModelFile(name = dictName, sha256 = sha256Hex(dict), size = dict.length()),
            ),
        )

        cachedManifestIsUsable(manifest, "ocr-models-v3", "https://x/ocr-models-v3", dir, names) shouldBe true
        // A cached manifest from a newer release must not be trusted even when the files verify.
        cachedManifestIsUsable(manifest, "ocr-models-v4", "https://x/ocr-models-v4", dir, names) shouldBe false
        // A same-size but corrupt file fails verification (the fast path is refused, so it re-fetches).
        File(dir, dictName).writeBytes("junk".toByteArray())
        cachedManifestIsUsable(manifest, "ocr-models-v3", "https://x/ocr-models-v3", dir, names) shouldBe false
    }

    @Test
    fun `requiredModelsVerified is false when a model is missing or corrupt`(@TempDir dir: File) {
        val det = File(dir, detName).apply { writeBytes("det".toByteArray()) }
        val manifest = OcrModelManifest(
            files = listOf(OcrModelFile(name = detName, sha256 = sha256Hex(det), size = det.length())),
        )

        requiredModelsVerified(manifest, dir, listOf(detName)) shouldBe true
        requiredModelsVerified(manifest, dir, listOf(detName, recName)) shouldBe false

        File(dir, detName).writeBytes("d".toByteArray())
        requiredModelsVerified(manifest, dir, listOf(detName)) shouldBe false
    }

    @Test
    fun `pendingModelEntries returns only the missing or mismatched files`(@TempDir dir: File) {
        val good = File(dir, detName).apply { writeBytes("good".toByteArray()) }
        val entries = listOf(
            OcrModelFile(name = detName, sha256 = sha256Hex(good), size = good.length()) to "u1",
            OcrModelFile(name = recName, sha256 = "0".repeat(64), size = 0) to "u2",
            OcrModelFile(name = dictName, sha256 = "0".repeat(64), size = 0) to "u3",
        )

        val pending = pendingModelEntries(entries, dir)

        // detName verifies and is kept; the two absent files are pending.
        pending.map { it.first.name } shouldContainExactly listOf(recName, dictName)
    }

    @Test
    fun `verifyStagedModels rejects a staged archive before it is installed`(@TempDir dir: File) {
        val scratch = File(dir, "scratch").apply { mkdirs() }
        val staged = File(scratch, detName).apply { writeBytes("det".toByteArray()) }
        val modelsDir = File(dir, "paddle_ocr").apply { mkdirs() }
        val existing = File(modelsDir, detName).apply { writeBytes("good".toByteArray()) }
        val manifest = OcrModelManifest(
            files = listOf(OcrModelFile(name = detName, sha256 = "0".repeat(64), size = staged.length())),
        )

        // Mirror `importFromUri`'s verify-then-install ordering: it installs only when
        // `verifyStagedModels` returns. The real entry point needs a `Context`/`Uri`, so it is not
        // exercised here (coverage limit); this mirrors its two calls instead. The staged bytes
        // (`det`) differ from what is installed (`good`), so a rejected archive that reached
        // `installRequiredModels` would clobber the file and fail the assertion below — it is not
        // vacuous.
        val checksumRejected = runCatching {
            verifyStagedModels(scratch, manifest, listOf(detName))
            installRequiredModels(scratch, modelsDir, listOf(detName))
        }
        checksumRejected.isFailure shouldBe true
        // A rejected import must leave the existing install byte-for-byte untouched (F7).
        existing.readText() shouldBe "good"

        val manifestRejected = runCatching {
            verifyStagedModels(scratch, OcrModelManifest(), listOf(detName))
            installRequiredModels(scratch, modelsDir, listOf(detName))
        }
        manifestRejected.isFailure shouldBe true
        existing.readText() shouldBe "good"
    }

    @Test
    fun `verifyStagedModels accepts a staged archive that matches the manifest`(@TempDir dir: File) {
        val scratch = File(dir, "scratch").apply { mkdirs() }
        val staged = File(scratch, detName).apply { writeBytes("det".toByteArray()) }
        val manifest = OcrModelManifest(
            files = listOf(OcrModelFile(name = detName, sha256 = sha256Hex(staged), size = staged.length())),
        )

        runCatching { verifyStagedModels(scratch, manifest, listOf(detName)) }.isSuccess shouldBe true
    }

    @Test
    fun `findModelFile matches a nested model by name`(@TempDir dir: File) {
        val nested = File(dir, "models").apply { mkdirs() }
        val model = File(nested, detName).apply { writeText("det") }

        findModelFile(dir, detName) shouldBe model
        findModelFile(dir, "nope.onnx") shouldBe null
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
