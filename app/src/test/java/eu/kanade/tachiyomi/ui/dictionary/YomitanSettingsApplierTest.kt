// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Pins the in-place profile update a Yomitan settings re-import performs: profiles are matched by
// name and rebuilt from the backup, so any field the backup has no equivalent for must be carried
// over from the existing profile instead of being reset to its default.
package eu.kanade.tachiyomi.ui.dictionary

import io.kotest.matchers.shouldBe
import mihon.core.dictionary.anki.AnkiPreferences
import mihon.core.dictionary.model.DictionaryProfile
import mihon.core.dictionary.yomitan.YomitanImportAnki
import mihon.core.dictionary.yomitan.YomitanImportPopup
import mihon.core.dictionary.yomitan.YomitanImportProfile
import mihon.core.dictionary.yomitan.YomitanSettingsImport
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class YomitanSettingsApplierTest {

    /**
     * The defect this pins: [YomitanSettingsApplier] rebuilt the matched profile field-by-field
     * without `dictionaryCollapseMode`, so every settings import silently reset the user's display
     * mode to the default (`EXPAND_ALL`) while the kdoc promised an in-place update.
     */
    @Test
    fun `a re-import keeps the collapse mode of the matched profile`() {
        val prefs = DictionaryPreferences(InMemoryPreferenceStore())
        val existing = DictionaryProfile(
            id = "existing-id",
            name = "Default",
            dictionaryOrder = listOf("Jitendex"),
            dictionaryCollapseMode = DictionaryProfile.DICTIONARY_COLLAPSE_COLLAPSE_ALL,
        )
        prefs.profileStore.saveProfiles(listOf(existing))
        prefs.profileStore.setActiveProfile(existing.id)

        val settings = yomitanSettings(profileName = "Default")
        YomitanSettingsApplier.apply(settings, prefs, AnkiPreferences(InMemoryPreferenceStore()))
        // Applying the very same backup again is what the user does to re-sync: it must be a no-op
        // on this field, not a second reset.
        val applied = YomitanSettingsApplier.apply(settings, prefs, AnkiPreferences(InMemoryPreferenceStore()))

        val profile = prefs.profileStore.getProfiles().single()
        profile.id shouldBe "existing-id"
        profile.dictionaryCollapseMode shouldBe DictionaryProfile.DICTIONARY_COLLAPSE_COLLAPSE_ALL
        // The fields the backup does carry are still updated in place.
        profile.dictionaryOrder shouldBe listOf("Jitendex")
        applied.updatedProfiles shouldBe 1
        applied.createdProfiles shouldBe 0
    }

    /** The other half of the same rule: a profile that does not exist yet starts at the default. */
    @Test
    fun `a new profile from a settings import gets the default collapse mode`() {
        val prefs = DictionaryPreferences(InMemoryPreferenceStore())

        val applied = YomitanSettingsApplier.apply(
            yomitanSettings(profileName = "Imported"),
            prefs,
            AnkiPreferences(InMemoryPreferenceStore()),
        )

        val profile = prefs.profileStore.getProfiles().single()
        profile.name shouldBe "Imported"
        profile.dictionaryCollapseMode shouldBe DictionaryProfile.DICTIONARY_COLLAPSE_EXPAND_ALL
        applied.createdProfiles shouldBe 1
        applied.updatedProfiles shouldBe 0
    }

    private fun yomitanSettings(profileName: String): YomitanSettingsImport = YomitanSettingsImport(
        formatVersion = 0,
        optionsVersion = YomitanSettingsImport.KNOWN_OPTIONS_VERSION,
        activeProfileName = profileName,
        profiles = listOf(
            YomitanImportProfile(
                name = profileName,
                dictionaryOrder = listOf("Jitendex"),
                enabledDictionaries = emptySet(),
                dictionaryDisplayModes = emptyMap(),
                languageCode = "ja",
                searchResolution = "",
                scanResolution = "",
                isActive = true,
            ),
        ),
        popup = YomitanImportPopup(),
        anki = YomitanImportAnki(),
        aliases = emptyMap(),
        reports = emptyList(),
        warnings = emptyList(),
    )
}
// KUN <--
