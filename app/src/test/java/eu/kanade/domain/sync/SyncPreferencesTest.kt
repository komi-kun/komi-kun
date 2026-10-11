package eu.kanade.domain.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference

/**
 * Ported from TachiyomiSY (jobobby04/TachiyomiSY#1658).
 */
class SyncPreferencesTest {

    private fun syncPreferences(syncService: Int) = SyncPreferences(
        InMemoryPreferenceStore(sequenceOf(InMemoryPreference("sync_service", syncService, 0))),
    )

    @Test
    fun `deleted chapters stay pending until a re-add forgets them`() {
        val prefs = syncPreferences(syncService = 1)

        prefs.rememberDeletedChapters(listOf("a", "b"))
        prefs.forgetDeletedChapters(listOf("a", "c"))

        assertEquals(setOf("b"), prefs.pendingDeletedChapterKeys.get())
    }

    @Test
    fun `deleted chapters are not tracked while sync is off`() {
        val prefs = syncPreferences(syncService = 0)

        prefs.rememberDeletedChapters(listOf("a"))

        assertEquals(emptySet<String>(), prefs.pendingDeletedChapterKeys.get())
    }
}
