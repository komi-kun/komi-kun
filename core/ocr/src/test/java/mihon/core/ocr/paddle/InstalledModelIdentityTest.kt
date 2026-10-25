// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// `PaddleOcrEngine` cannot be unit-tested (it needs an Android `Context` and libpaddle_ocr.so), so the
// part that decides *which weights are loaded and when to reload* lives in `installedModelIdentity`
// and is pinned here.
//
// The defect: the engine recorded `preferences.modelKey()` (the **selection**) as the key of the
// weights it had loaded. Every release publishes identically named files — `ppocrv6_small_det.onnx`
// is the dialogue detector in `ocr-manga-v4` and the SFX detector in `ocr-manga-v3` — so an
// upgrading install loaded the SFX weights still on disk and reported the dialogue release, and after
// a later download replaced the file the "key unchanged" early return kept the old detector in native
// memory until process death. Keying on the installed marker **plus the files' own fingerprint** is
// what makes both cases visible.
// KUN <--
package mihon.core.ocr.paddle

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class InstalledModelIdentityTest {

    private val detName = "ppocrv6_small_det.onnx"
    private val recName = "ppocrv6_small_rec_manga_real.onnx"
    private val names = listOf(detName, recName)

    /** `ocr-manga-v4` (dialogue) weights as the downloader would leave them. */
    private fun seedInstall(dir: File): File {
        dir.mkdirs()
        File(dir, detName).writeBytes("dialogue-detector-weights".toByteArray())
        File(dir, recName).writeBytes("fine-tuned-recogniser-weights".toByteArray())
        writeInstalledReleaseMarker(dir, "ocr-manga-v4")
        return dir
    }

    /**
     * The blocker: a file replaced under the same name (exactly what a variant download does — rename
     * a freshly written `.part` over it) must change the identity, so `ensureModelVariant` reloads
     * instead of early-returning. A selection-derived key was identical before and after.
     */
    @Test
    fun `a replaced detector is not reported as the release the marker still names`(@TempDir dir: File) {
        val modelsDir = seedInstall(dir)
        val beforeReplacement = installedModelIdentity(modelsDir, names)
        val beforeFingerprint = modelFileFingerprint(modelsDir, names)
        beforeReplacement shouldBe "ocr-manga-v4|$beforeFingerprint"

        // The user switched detector and the download installed the SFX bytes over the dialogue ones.
        val detector = File(modelsDir, detName)
        detector.writeBytes("sfx-detector-weights".toByteArray())
        // Explicit timestamp: the fingerprint is length + mtime (cheap `stat`), and a same-length
        // replacement in the same millisecond would otherwise be invisible on a coarse filesystem.
        detector.setLastModified(detector.lastModified() + 5_000L)

        val afterReplacement = installedModelIdentity(modelsDir, names)
        // The marker was not rewritten yet (or still names v4): the file identity alone must differ.
        installedReleaseMarker(modelsDir) shouldBe "ocr-manga-v4"
        afterReplacement shouldNotBe beforeReplacement
        // Precisely: the fingerprint part changed, so the engine's `loadedModelKey == installed`
        // test fails and it reloads.
        modelFileFingerprint(modelsDir, names) shouldNotBe beforeFingerprint
    }

    /**
     * A pre-marker install (every upgrade from a build that predates `installed-release.txt`) cannot
     * prove which release its bytes are. It must never be reported as the selected release.
     */
    @Test
    fun `an unmarked cache never claims the selected release`(@TempDir dir: File) {
        dir.mkdirs()
        File(dir, detName).writeBytes("sfx-detector-weights".toByteArray())
        File(dir, recName).writeBytes("fine-tuned-recogniser-weights".toByteArray())

        val identity = installedModelIdentity(dir, names)

        identity.startsWith("$UNKNOWN_INSTALL|") shouldBe true
        // Not the dialogue release the selection resolves to, and not any other release tag either.
        identity shouldNotBe "ocr-manga-v4|${modelFileFingerprint(dir, names)}"
        identity.contains("ocr-manga-v4") shouldBe false
        identity.contains("ocr-manga-v3") shouldBe false
    }

    /**
     * The hand-staged / just-downloaded case: the marker appearing after the engine already loaded
     * unmarked files is exactly one reload, and the identity is stable afterwards (so the next page
     * does not reload again).
     */
    @Test
    fun `recording the release changes the identity once and then stays stable`(@TempDir dir: File) {
        dir.mkdirs()
        File(dir, detName).writeBytes("detector-weights".toByteArray())
        File(dir, recName).writeBytes("recogniser-weights".toByteArray())

        val unmarked = installedModelIdentity(dir, names)

        writeInstalledReleaseMarker(dir, "ocr-manga-v4")
        val marked = installedModelIdentity(dir, names)
        val markedAgain = installedModelIdentity(dir, names)

        marked shouldNotBe unmarked
        marked shouldBe "ocr-manga-v4|${modelFileFingerprint(dir, names)}"
        markedAgain shouldBe marked
    }

    /** A missing file is part of the identity: installing it must force a reload, not a silent skip. */
    @Test
    fun `a removed model file changes the identity`(@TempDir dir: File) {
        val modelsDir = seedInstall(dir)
        val complete = installedModelIdentity(modelsDir, names)

        File(modelsDir, recName).delete()

        installedModelIdentity(modelsDir, names) shouldNotBe complete
        modelFileFingerprint(modelsDir, names).contains("$recName:absent") shouldBe true
    }
}
