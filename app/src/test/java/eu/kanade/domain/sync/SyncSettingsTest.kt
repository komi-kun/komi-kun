// KUN -->
package eu.kanade.domain.sync

import eu.kanade.domain.sync.models.SyncSettings
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncSettingsTest {

    @Test
    fun `force push requires every section stored in the manga payload`() {
        assertTrue(SyncSettings().supportsForcePush)
        assertFalse(SyncSettings(categories = false).supportsForcePush)
        assertFalse(SyncSettings(chapters = false).supportsForcePush)
        assertFalse(SyncSettings(tracking = false).supportsForcePush)
        assertFalse(SyncSettings(history = false).supportsForcePush)
        assertFalse(SyncSettings(customInfo = false).supportsForcePush)
        assertFalse(SyncSettings(libraryEntries = false).supportsForcePush)
    }

    @Test
    fun `sections outside the manga payload do not block force push`() {
        assertTrue(SyncSettings(appSettings = false, extensionStores = false, sourceSettings = false).supportsForcePush)
    }
}
// KUN <--
