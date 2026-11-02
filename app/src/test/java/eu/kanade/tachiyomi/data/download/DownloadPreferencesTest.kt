package eu.kanade.tachiyomi.data.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences

/**
 * Pins the orphaned-download switch: on by default, because the cleanup is the point of the feature.
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
}
