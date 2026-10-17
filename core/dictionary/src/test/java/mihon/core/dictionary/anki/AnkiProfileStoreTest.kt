// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.anki

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * T1 profile-store gate (plan 05 §A3/B7): kotlinx-serialization round-trip, CRUD, the last-profile
 * guard and the legacy flat-key migration.
 */
class AnkiProfileStoreTest {

    private var profilesJson = "[]"
    private var activeId = ""

    private fun store() = AnkiProfileStore(
        readProfiles = { profilesJson },
        writeProfiles = { profilesJson = it },
        readActiveId = { activeId },
        writeActiveId = { activeId = it },
    )

    private fun profile(id: String, name: String) = AnkiProfile(id = id, name = name, ankiDeck = "Deck")

    @Test
    fun `toJson and fromJson round-trip every field`() {
        val original = AnkiProfile(
            id = "id-1",
            name = "Mining",
            ankiEnabled = true,
            ankiDeck = "Japanese",
            ankiModel = "Lapis",
            ankiFieldMap = """{"Expression":"{expression}"}""",
            ankiTags = "komikun manga",
            ankiDupCheck = false,
            ankiDupScope = "deck-root",
            ankiDupAction = "overwrite",
            ankiCropMode = "crop",
            ankiCropPreset = "square",
            ankiSyncOnCreate = true,
        )
        AnkiProfile.fromJson(original.toJson()) shouldBe original
        AnkiProfile.fromJsonString(original.toJsonString()) shouldBe original
    }

    @Test
    fun `addProfile persists and selects the new profile`() {
        val store = store()
        store.addProfile(profile("a", "A"))
        store.getProfiles().map { it.name } shouldBe listOf("A")
        store.getActiveProfile().id shouldBe "a"
    }

    @Test
    fun `getActiveProfile falls back to the first profile`() {
        val store = store()
        store.addProfile(profile("a", "A"))
        store.addProfile(profile("b", "B"))
        activeId = "missing"
        store.getActiveProfile().id shouldBe "a"
    }

    @Test
    fun `updateProfile replaces the matching id`() {
        val store = store()
        store.addProfile(profile("a", "A"))
        store.updateProfile(profile("a", "A2"))
        store.getProfiles().single().name shouldBe "A2"
    }

    @Test
    fun `deleteProfile refuses to remove the last profile`() {
        val store = store()
        store.addProfile(profile("a", "A"))
        store.deleteProfile("a") shouldBe false
        store.getProfiles().size shouldBe 1
    }

    @Test
    fun `deleteProfile reassigns the active id`() {
        val store = store()
        store.addProfile(profile("a", "A"))
        store.addProfile(profile("b", "B"))
        store.setActiveProfile("a")
        store.deleteProfile("a") shouldBe true
        store.getProfiles().map { it.id } shouldBe listOf("b")
        store.getActiveId() shouldBe "b"
    }

    @Test
    fun `migrateIfEmpty builds a default profile from legacy values only once`() {
        val store = store()
        store.migrateIfEmpty(
            defaultName = "Default",
            legacyValues = AnkiProfileStore.LegacyAnkiValues(
                deck = "LegacyDeck",
                model = "LegacyModel",
                tags = "old",
                dupAction = "new",
            ),
        )
        val migrated = store.getProfiles().single()
        migrated.ankiDeck shouldBe "LegacyDeck"
        migrated.ankiModel shouldBe "LegacyModel"
        migrated.ankiTags shouldBe "old"
        migrated.ankiDupAction shouldBe "new"
        store.getActiveId() shouldBe migrated.id

        // A second migration must not overwrite existing profiles.
        store.migrateIfEmpty("Default", AnkiProfileStore.LegacyAnkiValues(deck = "Other"))
        store.getProfiles().single().ankiDeck shouldBe "LegacyDeck"
    }

    @Test
    fun `empty and malformed profile JSON degrade gracefully`() {
        val store = store()
        store.getProfiles() shouldBe emptyList()
        profilesJson = "not json"
        store.getProfiles() shouldBe emptyList()
        store.getActiveProfile().name shouldNotBe ""
    }
}
// KUN <--
