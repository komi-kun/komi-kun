// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Pins the resume journal of the Yomitan-backup import. The manifest is keyed to the picked
// backup's name+size so a cancelled run skips the dictionaries that already landed; if a later
// `flush()` stops writing those identity keys, every re-open looks like a *different* backup,
// wipes the staging dir and re-imports everything from scratch.
package eu.kanade.tachiyomi.ui.dictionary

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * JVM-level test of [DictionaryBackupImporter.ResumeManifest] — plain `File` + `org.json`, no
 * Android framework involved. The full `import(uri, onProgress)` path is *not* covered here: it
 * needs `Context`/`ContentResolver` for the stream and `android.os.StatFs` in `inspect()`, neither
 * of which runs on the JVM.
 */
class DictionaryBackupImporterTest {

    @Test
    fun `a flush mid-run keeps the manifest's identity keys so the re-open resumes`() {
        val staging = stagingDir()
        val first = DictionaryBackupImporter.ResumeManifest.open(staging, BACKUP)

        first.record("JMdict")
        first.record("JMnedict")
        first.flush()

        // The next run opens the manifest again before touching the stream.
        val resumed = DictionaryBackupImporter.ResumeManifest.open(staging, BACKUP)

        resumed.completed shouldBe setOf("JMdict", "JMnedict")
    }

    @Test
    fun `a flush must not throw the staging tree away`() {
        val staging = stagingDir()
        val first = DictionaryBackupImporter.ResumeManifest.open(staging, BACKUP)
        // Work the converter already staged; a resume must find it, a wipe must not.
        val staged = File(staging, "terms_1.json").apply { writeText("[]") }

        first.record("JMdict")
        first.flush()

        DictionaryBackupImporter.ResumeManifest.open(staging, BACKUP)
        staged.readText() shouldBe "[]"
    }

    @Test
    fun `a different backup does not inherit the previous one's completed dictionaries`() {
        val staging = stagingDir()
        val first = DictionaryBackupImporter.ResumeManifest.open(staging, BACKUP)
        first.record("JMdict")
        first.flush()

        // Same file name, different size: a re-run over another backup must start clean.
        val other = DictionaryBackupImporter.ResumeManifest.open(
            staging,
            BACKUP.copy(sizeBytes = BACKUP.sizeBytes + 1),
        )

        other.completed shouldBe emptySet()
    }

    private fun stagingDir(): File =
        File(System.getProperty("java.io.tmpdir"), "dict-backup-manifest-${System.nanoTime()}")

    private companion object {
        val BACKUP = BackupInspection(
            displayName = "yomitan-backup-2024-06-20.json",
            sizeBytes = 12_345_678L,
            freeBytes = 4L * 1024 * 1024 * 1024,
            requiredBytes = 2L * 1024 * 1024 * 1024,
        )
    }
}
// KUN <--
