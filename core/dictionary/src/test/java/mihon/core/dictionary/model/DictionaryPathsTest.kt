// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The import writes `<name>.importing` and `<name>.replacing` next to every live type directory, and
// only cleans them up at the start of the *next* import. A process killed in between therefore leaves
// them on disk for the life of the install — so nothing that enumerates dictionaries may report them:
// a staging copy handed to the engine as a term/frequency path is a dictionary that is not one, and a
// backup is the previous revision of one whose swap may have completed.
package mihon.core.dictionary.model

import android.content.Context
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.io.File

class DictionaryPathsTest {

    /**
     * The defect this pins: `allDictionaryNames` listed **every** child of each type dir, so a
     * `.importing` staging copy left by a killed import was a dictionary as far as the app was
     * concerned — it appeared in the management list, in the profile order and, through
     * `getDictionaryPaths`, as a term/frequency path handed to the engine.
     */
    @Test
    fun `a leftover staging or backup directory is never reported as a dictionary`() {
        val context = contextWithDictionariesDir()
        val termDir = File(dictionariesDir(context), "term").apply { mkdirs() }
        val freqDir = File(dictionariesDir(context), "frequency").apply { mkdirs() }
        // The live dictionary, plus exactly what a killed swap leaves next to it.
        File(termDir, "Real").mkdirs()
        File(termDir, "Ghost$DICTIONARY_STAGING_SUFFIX").mkdirs()
        File(freqDir, "Ghost$DICTIONARY_BACKUP_SUFFIX").mkdirs()
        File(termDir, "Real$DICTIONARY_BACKUP_SUFFIX").mkdirs()

        val names = allDictionaryNames(context)

        names shouldBe listOf("Real")
        names shouldNotContain "Ghost$DICTIONARY_STAGING_SUFFIX"
        names shouldNotContain "Ghost$DICTIONARY_BACKUP_SUFFIX"
        names shouldNotContain "Real$DICTIONARY_BACKUP_SUFFIX"
    }

    private fun contextWithDictionariesDir(): Context {
        val externalRoot = File(System.getProperty("java.io.tmpdir"), "dict-paths-${System.nanoTime()}")
        return mockk<Context>(relaxed = true).also { context ->
            every { context.getExternalFilesDir(null) } returns externalRoot
        }
    }
}
// KUN <--
