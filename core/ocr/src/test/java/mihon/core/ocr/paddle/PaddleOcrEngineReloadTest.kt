// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// `PaddleOcrEngine.ensureModelVariant` decides whether to swap the weights by comparing the identity
// the last `init` recorded with the identity of the files on disk. The engine itself cannot be unit
// tested (Android `Context` + `libpaddle_ocr.so`), so the comparison is a pure function
// (`shouldReloadModels`) and is pinned here — including the direction that made the bug invisible:
// a mutation writing `loadedModelKey = preferences.resolveModelRelease().release` (the selection)
// satisfies "the key is a String" and used to pass every test in this module.
// KUN <--
package mihon.core.ocr.paddle

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PaddleOcrEngineReloadTest {

    private val detName = "ppocrv6_small_det.onnx"
    private val recName = "ppocrv6_small_rec_manga_real.onnx"
    private val names = listOf(detName, recName)

    /**
     * The truth table. The `null` row is the `destroy()` / "never initialised" state: the engine has
     * disclaimed its weights, so the next page must initialise (and not early-return into an engine
     * that holds no weights).
     */
    @Test
    fun `a reload is decided by equality of the two identities`() {
        shouldReloadModels(null, "ocr-manga-v4|det:1:2") shouldBe true
        shouldReloadModels("ocr-manga-v4|det:1:2", "ocr-manga-v4|det:1:2") shouldBe false
        shouldReloadModels("ocr-manga-v4|det:1:2", "ocr-manga-v4|det:1:3") shouldBe true
        // A different release is a different identity too (the easy direction, kept for completeness).
        shouldReloadModels("ocr-manga-v3|det:1:2", "ocr-manga-v4|det:1:2") shouldBe true
    }

    /**
     * The blocker, at the level of the real identity strings: a detector replaced under its own name
     * (what a variant download does) leaves the installed-release marker **unchanged**, so the release
     * tag is identical before and after and only the file fingerprint differs. The comparison must
     * still force a reload — an implementation that compared `substringBefore('|')` (or the selection)
     * returns `false` here and the old detector survives in native memory.
     */
    @Test
    fun `a replaced detector forces a reload even though the release tag is unchanged`(@TempDir dir: File) {
        dir.mkdirs()
        val detector = File(dir, detName).apply { writeBytes("dialogue-detector-weights".toByteArray()) }
        File(dir, recName).writeBytes("fine-tuned-recogniser-weights".toByteArray())
        writeInstalledReleaseMarker(dir, "ocr-manga-v4")

        val loaded = installedModelIdentity(dir, names)

        detector.writeBytes("sfx-detector-weights".toByteArray())
        detector.setLastModified(detector.lastModified() + 5_000L)
        val installed = installedModelIdentity(dir, names)

        // The premise of the test: same release tag on both sides, so a tag-only comparison matches.
        loaded.substringBefore('|') shouldBe "ocr-manga-v4"
        installed.substringBefore('|') shouldBe "ocr-manga-v4"
        shouldReloadModels(loaded, installed) shouldBe true
        // ...and the identity is stable, so the page after the reload does not reload again.
        shouldReloadModels(installed, installed) shouldBe false
    }

    /**
     * A bare release tag is exactly what the selection resolves to, and it is never a well-formed
     * identity (those always carry a `|` and a file fingerprint). So a mutation recording the
     * selection instead of the installed identity makes *every* comparison unequal: the engine would
     * re-`destroy()` and re-`init()` on every page. Pinned as a property of the identity space so the
     * mutation cannot become plausible again.
     */
    @Test
    fun `a release tag is not an identity`(@TempDir dir: File) {
        dir.mkdirs()
        File(dir, detName).writeBytes("detector-weights".toByteArray())
        File(dir, recName).writeBytes("recogniser-weights".toByteArray())
        writeInstalledReleaseMarker(dir, "ocr-manga-v4")

        val installed = installedModelIdentity(dir, names)

        shouldReloadModels("ocr-manga-v4", installed) shouldBe true
        installed.contains('|') shouldBe true
    }

    /**
     * **Source-level guard for the engine's *use* of the identity.** The truth table above pins the
     * comparison; it cannot see which value the engine stores or compares, and reverting
     * `loadedModelKey = installed` to the original bug (`= preferences.resolveModelRelease().release`)
     * compiles and passes every other test in this module. `PaddleOcrEngine` needs a `Context` and
     * `libpaddle_ocr.so` to instantiate, so the call site is read instead.
     */
    @Test
    fun `the engine records and compares the installed identity, never the selection`() {
        val engine = File("src/main/java/mihon/core/ocr/paddle/PaddleOcrEngine.kt").readText()

        // `init` records the identity of the files it just loaded...
        engine shouldContainSource """loadedModelKey\s*=\s*installed\b"""
        // ...never the selection, and `destroy` forgets it so the next page initialises.
        engine shouldNotContainSource """loadedModelKey\s*=\s*preferences"""
        engine shouldContainSource """loadedModelKey\s*=\s*null"""
        // The reload decision compares that recorded identity with the *installed* one, and the
        // installed one is what `installedModelIdentity` reports for the directory.
        val guard = functionBody(engine, "private fun ensureModelVariant()")
        guard shouldContainSource """shouldReloadModels\s*\(\s*loadedModelKey\s*,\s*installed\s*\)"""
        guard shouldNotContainSource """resolveModelRelease"""
        guard shouldContainSource """installedModelIdentity\("""

        // Not asserted: that `installedModelIdentity` is called with the directory the engine loads
        // from (`File(appContext.filesDir, MODEL_DIR)`), nor that the init really happens on device.
    }
}

/** [pattern] must occur somewhere in this text (whitespace-tolerant regex). */
private infix fun String.shouldContainSource(pattern: String) {
    if (!Regex(pattern).containsMatchIn(this)) {
        throw AssertionError("expected the source to contain /$pattern/")
    }
}

/** The mirror: [pattern] must not occur in this text. */
private infix fun String.shouldNotContainSource(pattern: String) {
    if (Regex(pattern).containsMatchIn(this)) {
        throw AssertionError("expected the source not to contain /$pattern/")
    }
}

/** The body of the function starting at [signature], by counting braces from its opening brace. */
private fun functionBody(source: String, signature: String): String {
    val start = source.indexOf(signature)
    check(start >= 0) { "missing call site: $signature" }
    val open = source.indexOf('{', start)
    check(open > start) { "no block for: $signature" }

    var depth = 0
    for (i in open until source.length) {
        when (source[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return source.substring(open, i + 1)
            }
        }
    }
    error("unterminated body for: $signature")
}
