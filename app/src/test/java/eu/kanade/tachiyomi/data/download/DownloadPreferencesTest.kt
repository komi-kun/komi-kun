package eu.kanade.tachiyomi.data.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences

/**
 * Pins the download-cleanup switches: the orphaned-download one is on by default, because the
 * cleanup is the point of the feature; the filtered-download one is off, because it deletes files
 * the user still has listed until they change a filter.
 */
class DownloadPreferencesTest {

    @Test
    fun `orphaned download removal is on by default`() {
        DownloadPreferences(InMemoryPreferenceStore()).removeOrphanedDownloads.get() shouldBe true
    }

    @Test
    fun `the orphaned download switch can be turned off and read back`() {
        val preferences = DownloadPreferences(InMemoryPreferenceStore())

        preferences.removeOrphanedDownloads.set(false)

        preferences.removeOrphanedDownloads.get() shouldBe false
    }

    @Test
    fun `filtered download removal is off by default`() {
        DownloadPreferences(InMemoryPreferenceStore()).removeFilteredDownloads.get() shouldBe false
    }

    @Test
    fun `the filtered download switch can be turned on and read back`() {
        val preferences = DownloadPreferences(InMemoryPreferenceStore())

        preferences.removeFilteredDownloads.set(true)

        preferences.removeFilteredDownloads.get() shouldBe true
    }
}
