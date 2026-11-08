// KUN -->
package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.domain.source.service.SourcePreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.history.service.HistoryPreferences
import tachiyomi.domain.source.service.SourceManager

class PreferenceBackupCreatorTest {

    private val pinnedKey = SourcePreferences.PINNED_SOURCES_PREF_KEY

    private fun creatorWith(vararg preferences: Pair<String, Any>): PreferenceBackupCreator {
        val store = mockk<PreferenceStore>()
        every { store.getAll() } returns preferences.toMap()
        return PreferenceBackupCreator(mockk<SourceManager>(relaxed = true), store)
    }

    @Test
    fun `device-local sync settings are not uploaded with app preferences`() {
        val deviceLocalKey = Preference.appStateKey("sync_pinned_sources")
        val creator = creatorWith(deviceLocalKey to true, "sync_library_entries" to true)

        val keys = creator.createApp(includePrivatePreferences = true).map { it.key }

        assertEquals(listOf("sync_library_entries"), keys)
        assertFalse(deviceLocalKey in keys)
    }

    @Test
    fun `manual backups still include the pinned sources`() {
        // Manual/file backups go through createApp unchanged; only the sync upload filters the key.
        val creator = creatorWith(pinnedKey to setOf("1"), "sync_library_entries" to true)

        val keys = creator.createApp(includePrivatePreferences = true).map { it.key }

        assertTrue(pinnedKey in keys)
    }

    @Test
    fun `manual backups still include the history category filter`() {
        // The filter is in the sync denylist, but a manual/file backup must carry it so a restore
        // can remap the local category ids by name.
        val creator = creatorWith(
            HistoryPreferences.FILTER_INCLUDED_CATEGORIES_PREF_KEY to "1,2",
            HistoryPreferences.FILTER_EXCLUDED_CATEGORIES_PREF_KEY to "3",
            HistoryPreferences.FILTER_INCLUSION_WINS_PREF_KEY to true,
        )

        val keys = creator.createApp(includePrivatePreferences = true).map { it.key }

        assertTrue(HistoryPreferences.FILTER_INCLUDED_CATEGORIES_PREF_KEY in keys)
        assertTrue(HistoryPreferences.FILTER_EXCLUDED_CATEGORIES_PREF_KEY in keys)
        assertTrue(HistoryPreferences.FILTER_INCLUSION_WINS_PREF_KEY in keys)
    }
}
// KUN <--
