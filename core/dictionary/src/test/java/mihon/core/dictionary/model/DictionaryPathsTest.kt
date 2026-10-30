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

    @Test
    fun `normalizeDictionaryTitle strips date tags and lowercases`() {
        normalizeDictionaryTitle("Jitendex [2024-10-25]") shouldBe "jitendex"
        normalizeDictionaryTitle("Jitendex (2024-10-25)") shouldBe "jitendex"
        normalizeDictionaryTitle("JMdict (English)") shouldBe "jmdict (english)"
        normalizeDictionaryTitle("三省堂国語辞典 第七版") shouldBe "三省堂国語辞典 第七版"
    }

    @Test
    fun `resolveDictionaryOrder matches on-disk dictionaries by title or dirName`() {
        val context = contextWithDictionariesDir()
        val termDir = File(dictionariesDir(context), "term").apply { mkdirs() }
        // Create 3 dictionaries on disk:
        // 1. dirName "jmdict" with index.json title "JMdict (English)"
        // 2. dirName "jitendex_2024" with index.json title "Jitendex [2024-10-25]"
        // 3. dirName "kanjidic" with index.json title "KANJIDIC"
        File(termDir, "jmdict").mkdirs()
        File(termDir, "jmdict/index.json").writeText("""{"title":"JMdict (English)"}""")

        File(termDir, "jitendex_2024").mkdirs()
        File(termDir, "jitendex_2024/index.json").writeText("""{"title":"Jitendex [2024-10-25]"}""")

        File(termDir, "kanjidic").mkdirs()
        File(termDir, "kanjidic/index.json").writeText("""{"title":"KANJIDIC"}""")

        val allNames = allDictionaryNames(context)
        allNames shouldBe listOf("jitendex_2024", "jmdict", "kanjidic")

        // Yomitan settings had order: "KANJIDIC", "JMdict (English)", "Jitendex"
        val yomitanOrder = listOf("KANJIDIC", "JMdict (English)", "Jitendex")
        val resolved = resolveDictionaryOrder(allNames, yomitanOrder, context)

        resolved shouldBe listOf("kanjidic", "jmdict", "jitendex_2024")
    }

    private fun contextWithDictionariesDir(): Context {
        val externalRoot = File(System.getProperty("java.io.tmpdir"), "dict-paths-${System.nanoTime()}")
        return mockk<Context>(relaxed = true).also { context ->
            every { context.getExternalFilesDir(null) } returns externalRoot
        }
    }
}
// KUN <--
