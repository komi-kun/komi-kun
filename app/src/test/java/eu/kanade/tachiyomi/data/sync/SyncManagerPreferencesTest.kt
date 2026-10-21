// KUN -->
package eu.kanade.tachiyomi.data.sync

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncManagerPreferencesTest {

    private val pinnedKey = SourcePreferences.PINNED_SOURCES_PREF_KEY

    private fun preferences(vararg keys: String): List<BackupPreference> {
        return keys.map { BackupPreference(it, BooleanPreferenceValue(true)) }
    }

    @Test
    fun `pinned sources are not uploaded when the setting is off`() {
        val filtered = SyncManager.filterSyncableAppPreferences(
            preferences(pinnedKey, "sync_library_entries"),
            syncPinnedSources = false,
        )

        assertEquals(listOf("sync_library_entries"), filtered.map { it.key })
    }

    @Test
    fun `pinned sources are uploaded when the setting is on`() {
        val filtered = SyncManager.filterSyncableAppPreferences(
            preferences(pinnedKey, "sync_library_entries"),
            syncPinnedSources = true,
        )

        assertTrue(pinnedKey in filtered.map { it.key })
        assertEquals(2, filtered.size)
    }

    @Test
    fun `other device-local preferences stay excluded from the sync upload`() {
        val filtered = SyncManager.filterSyncableAppPreferences(
            preferences("sync_service", "tablet_ui_mode", "connection_webdav_url", pinnedKey),
            syncPinnedSources = true,
        )

        // Only the (enabled) pinned-source key survives the device-local exclusions.
        assertEquals(listOf(pinnedKey), filtered.map { it.key })
    }
}
// KUN <--
