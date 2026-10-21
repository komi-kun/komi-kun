// KUN -->
package eu.kanade.tachiyomi.data.backup.restore

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RestoreOptionsTest {

    @Test
    fun `pinned source replacement is off by default`() {
        assertFalse(RestoreOptions().replacePinnedSources)
    }

    @Test
    fun `pinned source replacement survives the worker boolean array`() {
        val options = RestoreOptions(replacePinnedSources = true)

        val restored = RestoreOptions.fromBooleanArray(options.asBooleanArray())

        assertTrue(restored.replacePinnedSources)
        assertArrayEquals(options.asBooleanArray(), restored.asBooleanArray())
    }

    @Test
    fun `older worker arrays default pinned source replacement to off`() {
        val legacyArray = booleanArrayOf(true, true, true, true, true, true, true, true, true)

        val restored = RestoreOptions.fromBooleanArray(legacyArray)

        assertFalse(restored.replacePinnedSources)
        assertEquals(RestoreOptions(), restored)
    }
}
// KUN <--
