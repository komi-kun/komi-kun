// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import mihon.core.dictionary.model.DictionaryProfile
import mihon.core.dictionary.repository.DictionaryRepository
import mihon.core.dictionary.ui.LookupPath
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DictionaryLookupStateTest {

    private val context = mockk<Context>(relaxed = true)
    private val repository = mockk<DictionaryRepository>(relaxed = true)
    private val prefs = mockk<DictionaryPreferences>(relaxed = true)
    private val testScope = TestScope()
    private val profile = DictionaryProfile(id = "default", name = "Default", languageCode = "ja")

    private fun createState(): DictionaryLookupState = DictionaryLookupState(
        context = context,
        repository = repository,
        prefs = prefs,
        coroutineScope = testScope,
        profileProvider = { profile },
        logTag = "test",
    )

    private fun frameOf(query: String) = LookupFrame(
        query = query,
        results = emptyList(),
        styles = emptyList(),
        mediaDataUris = emptyMap(),
    )

    @Test
    fun `initial state is empty and cannot go back`() {
        val state = createState()
        state.path shouldBe LookupPath()
        state.current shouldBe null
        state.canGoBack shouldBe false
        state.tabs shouldBe emptyList()
        state.loading shouldBe false
    }

    @Test
    fun `tabs reflects path entries with active index marked`() {
        val state = createState()
        // Simulate path with 2 entries
        val frame1 = frameOf("日本語")
        val frame2 = frameOf("言語")

        state.selectTab(0) // no-op on empty
        state.canGoBack shouldBe false

        // Start path with frame1 and push frame2
        val p = LookupPath<LookupFrame>().start(frame1).push(frame2)
        state.path = p

        state.current?.query shouldBe "言語"
        state.canGoBack shouldBe true
        state.tabs.size shouldBe 2
        state.tabs[0].label shouldBe "日本語"
        state.tabs[0].active shouldBe false
        state.tabs[1].label shouldBe "言語"
        state.tabs[1].active shouldBe true

        // Move to tab 0
        state.selectTab(0)
        state.current?.query shouldBe "日本語"
        state.canGoBack shouldBe false
        state.tabs[0].active shouldBe true
        state.tabs[1].active shouldBe false

        // Move back to tab 1
        state.selectTab(1)
        state.current?.query shouldBe "言語"
        state.canGoBack shouldBe true

        // back() goes to tab 0
        state.back() shouldBe true
        state.current?.query shouldBe "日本語"
        state.canGoBack shouldBe false

        // back() at root returns false
        state.back() shouldBe false
        state.current?.query shouldBe "日本語"

        // clear() resets everything
        state.clear()
        state.path shouldBe LookupPath()
        state.current shouldBe null
        state.canGoBack shouldBe false
        state.tabs shouldBe emptyList()
    }

    @Test
    fun `isKanji correctly classifies CJK ideographs`() {
        isKanji('日') shouldBe true
        isKanji('本') shouldBe true
        isKanji('語') shouldBe true
        isKanji('猫') shouldBe true
        isKanji('犬') shouldBe true

        isKanji('a') shouldBe false
        isKanji('Z') shouldBe false
        isKanji('1') shouldBe false
        isKanji('あ') shouldBe false
        isKanji('ア') shouldBe false
        isKanji('。') shouldBe false
    }
}
// KUN <--
