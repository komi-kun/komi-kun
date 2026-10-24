// SPDX-License-Identifier: Apache-2.0
// KUN -->
// Contract tests for the dictionary/OCR opt-in preference. One key is both the master state and the
// explicit-choice flag: a fresh install is off *and* unasked; an explicit accept or decline is
// stored (so the app never nags again) and is never rewritten by a later launch.
// KUN <--
package tachiyomi.core.common.preference

import android.content.SharedPreferences
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.jupiter.api.Test

class DictionaryOcrPreferencesTest {

    @Test
    fun `a fresh install is off and has not chosen yet`() {
        val preferences = DictionaryOcrPreferences(AndroidPreferenceStore(FakeSharedPreferences().prefs))

        preferences.isEnabled() shouldBe false
        preferences.hasChosen() shouldBe false
        // The key is the shared one the app and the GPL services read.
        preferences.enabled.key() shouldBe "pref_dictionary_ocr_enabled"
        preferences.enabled.defaultValue() shouldBe false
    }

    @Test
    fun `accepting stores an explicit on that a later launch sees`() {
        val store = FakeSharedPreferences()
        DictionaryOcrPreferences(AndroidPreferenceStore(store.prefs)).choose(true)

        val relaunched = DictionaryOcrPreferences(AndroidPreferenceStore(store.prefs))

        relaunched.isEnabled() shouldBe true
        relaunched.hasChosen() shouldBe true
    }

    @Test
    fun `declining is a stored choice, not the unasked default`() {
        val store = FakeSharedPreferences()
        DictionaryOcrPreferences(AndroidPreferenceStore(store.prefs)).choose(false)

        val relaunched = DictionaryOcrPreferences(AndroidPreferenceStore(store.prefs))

        relaunched.isEnabled() shouldBe false
        // Declined (not "never asked"): the app must not prompt again.
        relaunched.hasChosen() shouldBe true
    }

    @Test
    fun `recording the current choice turns an unasked install into an explicit decline`() {
        val store = FakeSharedPreferences()
        DictionaryOcrPreferences(AndroidPreferenceStore(store.prefs)).recordCurrentChoice()

        val relaunched = DictionaryOcrPreferences(AndroidPreferenceStore(store.prefs))

        relaunched.isEnabled() shouldBe false
        relaunched.hasChosen() shouldBe true
    }

    @Test
    fun `recording the current choice never resets an accepted choice`() {
        val store = FakeSharedPreferences()
        DictionaryOcrPreferences(AndroidPreferenceStore(store.prefs)).choose(true)

        DictionaryOcrPreferences(AndroidPreferenceStore(store.prefs)).recordCurrentChoice()

        val relaunched = DictionaryOcrPreferences(AndroidPreferenceStore(store.prefs))
        relaunched.isEnabled() shouldBe true
        relaunched.hasChosen() shouldBe true
    }

    @Test
    fun `recording the current choice never turns a decline back into unasked`() {
        val store = FakeSharedPreferences()
        DictionaryOcrPreferences(AndroidPreferenceStore(store.prefs)).choose(false)

        DictionaryOcrPreferences(AndroidPreferenceStore(store.prefs)).recordCurrentChoice()

        DictionaryOcrPreferences(AndroidPreferenceStore(store.prefs)).hasChosen() shouldBe true
    }

    /**
     * An in-memory [SharedPreferences] so the real [AndroidPreferenceStore] (and therefore the real
     * `contains()`-based `isSet()`) is exercised, including across two `DictionaryOcrPreferences`
     * instances — i.e. the "relaunch" case. No Android runtime is involved: [SharedPreferences] is a
     * plain interface.
     */
    private class FakeSharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        private val editor: SharedPreferences.Editor = mockk()

        val prefs: SharedPreferences = mockk()

        init {
            every { editor.putBoolean(any(), any()) } answers {
                values[firstArg<String>()] = secondArg<Boolean>()
                editor
            }
            every { editor.remove(any()) } answers {
                values.remove(firstArg<String>())
                editor
            }
            every { editor.clear() } answers {
                values.clear()
                editor
            }
            every { editor.commit() } returns true
            every { editor.apply() } just Runs

            every { prefs.contains(any()) } answers { values.containsKey(firstArg<String>()) }
            every { prefs.getBoolean(any(), any()) } answers {
                values[firstArg<String>()] as? Boolean ?: secondArg()
            }
            every { prefs.edit() } returns editor
        }
    }
}
