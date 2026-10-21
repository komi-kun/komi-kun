// KUN -->
package eu.kanade.tachiyomi.data.backup.create.creators

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.source.service.SourceManager

class PreferenceBackupCreatorTest {

    @Test
    fun `device-local sync settings are not uploaded with app preferences`() {
        val deviceLocalKey = Preference.appStateKey("sync_replace_pinned_sources")
        val store = mockk<PreferenceStore>()
        every { store.getAll() } returns mapOf(
            deviceLocalKey to true,
            "sync_library_entries" to true,
        )
        val creator = PreferenceBackupCreator(mockk<SourceManager>(relaxed = true), store)

        val keys = creator.createApp(includePrivatePreferences = true).map { it.key }

        assertEquals(listOf("sync_library_entries"), keys)
        assertFalse(deviceLocalKey in keys)
    }
}
// KUN <--
