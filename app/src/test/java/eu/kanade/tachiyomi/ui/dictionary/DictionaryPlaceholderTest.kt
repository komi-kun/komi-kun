// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Pins the shared empty-surface decision: a first-run dictionary tab (no dictionary installed) and
// a lookup with no hits must say so through the renderer's `placeholder`. The renderer prints the
// string verbatim (`renderer.js`: `empty.textContent = payload.placeholder`), so a blank decision
// here is a blank page there — that is the defect these tests would fail on.
package eu.kanade.tachiyomi.ui.dictionary

import io.kotest.matchers.shouldBe
import mihon.core.dictionary.model.DictionaryPaths
import org.junit.jupiter.api.Test
import tachiyomi.i18n.kun.KUNMR

class DictionaryPlaceholderTest {

    @Test
    fun `no installed dictionary says so instead of leaving the surface blank`() {
        val state = dictionaryPlaceholder(
            hasDictionaries = false,
            loading = false,
            query = "猫",
            hasContent = false,
        )

        state shouldBe DictionaryPlaceholder.NoDictionaries
        state.resource shouldBe KUNMR.strings.dictionary_no_dictionaries
        state.args shouldBe emptyList<Any>()
    }

    @Test
    fun `a finished lookup with no hits reports the query it ran`() {
        val state = dictionaryPlaceholder(
            hasDictionaries = true,
            loading = false,
            query = "猫",
            hasContent = false,
        )

        (state as DictionaryPlaceholder.NoResults).query shouldBe "猫"
        state.resource shouldBe KUNMR.strings.dictionary_no_results
        state.args shouldBe listOf<Any>("猫")
    }

    @Test
    fun `an in-flight lookup keeps the surface blank for the progress indicator`() {
        val state = dictionaryPlaceholder(
            hasDictionaries = true,
            loading = true,
            query = "猫",
            hasContent = false,
        )

        state shouldBe DictionaryPlaceholder.None
        state.resource shouldBe null
    }

    @Test
    fun `no lookup yet is blank rather than a stale no-results message`() {
        val state = dictionaryPlaceholder(
            hasDictionaries = true,
            loading = false,
            query = null,
            hasContent = false,
        )

        state shouldBe DictionaryPlaceholder.None
    }

    @Test
    fun `a frame that draws entries is never reported as empty`() {
        val state = dictionaryPlaceholder(
            hasDictionaries = true,
            loading = false,
            query = "猫",
            hasContent = true,
        )

        state shouldBe DictionaryPlaceholder.None
    }

    @Test
    fun `a dictionary of any single type counts as installed`() {
        DictionaryPaths().hasAnyDictionaries() shouldBe false
        DictionaryPaths(termPaths = listOf("/dictionaries/term/jmdict")).hasAnyDictionaries() shouldBe true
        DictionaryPaths(pitchPaths = listOf("/dictionaries/pitch/accents")).hasAnyDictionaries() shouldBe true
        DictionaryPaths(kanjiPaths = listOf("/dictionaries/kanji/kanjidic")).hasAnyDictionaries() shouldBe true
    }
}
// KUN <--
