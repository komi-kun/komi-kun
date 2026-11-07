// KUN -->
package eu.kanade.tachiyomi.data.sync.service

import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.service.HistoryPreferences

/**
 * The app-preference merge keeps the remote value for a key both sides carry, with one exception: an
 * explicit local clear of a History category list survives, so "Reset filter" is not undone by the
 * next sync. That only applies while the filter is synced and only to the two list keys.
 */
class SyncPreferenceMergeTest {

    private val includedKey = HistoryPreferences.FILTER_INCLUDED_CATEGORIES_PREF_KEY
    private val excludedKey = HistoryPreferences.FILTER_EXCLUDED_CATEGORIES_PREF_KEY
    private val inclusionWinsKey = HistoryPreferences.FILTER_INCLUSION_WINS_PREF_KEY

    private fun string(key: String, value: String) = BackupPreference(key, StringPreferenceValue(value))

    private fun bool(key: String, value: Boolean) = BackupPreference(key, BooleanPreferenceValue(value))

    private fun merge(
        local: List<BackupPreference>,
        remote: List<BackupPreference>,
        syncHistoryCategoryFilter: Boolean = true,
    ): Map<String, BackupPreference> {
        return mergePreferencesLists(local, remote, syncHistoryCategoryFilter).associateBy { it.key }
    }

    private fun Map<String, BackupPreference>.stringValue(key: String): String {
        return (getValue(key).value as StringPreferenceValue).value
    }

    private fun Map<String, BackupPreference>.booleanValue(key: String): Boolean {
        return (getValue(key).value as BooleanPreferenceValue).value
    }

    @Test
    fun `an explicit local clear wins over a non-empty remote list when the filter is synced`() {
        val merged = merge(
            local = listOf(string(includedKey, ""), string(excludedKey, "")),
            remote = listOf(string(includedKey, "5,7"), string(excludedKey, "9")),
        )

        assertEquals("", merged.stringValue(includedKey))
        assertEquals("", merged.stringValue(excludedKey))
    }

    @Test
    fun `the same clear loses to the remote when the filter is not synced`() {
        val merged = merge(
            local = listOf(string(includedKey, "")),
            remote = listOf(string(includedKey, "5,7")),
            syncHistoryCategoryFilter = false,
        )

        assertEquals("5,7", merged.stringValue(includedKey))
    }

    @Test
    fun `a remote clear still replaces a non-empty local list`() {
        val merged = merge(
            local = listOf(string(includedKey, "5,7")),
            remote = listOf(string(includedKey, "")),
        )

        assertEquals("", merged.stringValue(includedKey))
    }

    @Test
    fun `the local clear does not override the precedence flag`() {
        val merged = merge(
            local = listOf(string(includedKey, ""), bool(inclusionWinsKey, false)),
            remote = listOf(string(includedKey, "5,7"), bool(inclusionWinsKey, true)),
        )

        assertEquals(true, merged.booleanValue(inclusionWinsKey))
    }

    @Test
    fun `the local clear does not override unrelated string preferences`() {
        val merged = merge(
            local = listOf(string(includedKey, ""), string("some_string_preference", "")),
            remote = listOf(string("some_string_preference", "remote")),
        )

        assertEquals("remote", merged.stringValue("some_string_preference"))
    }

    @Test
    fun `keys present on only one side are carried over`() {
        val merged = merge(
            local = listOf(string(excludedKey, "3")),
            remote = listOf(string(includedKey, "5")),
        )

        assertEquals("3", merged.stringValue(excludedKey))
        assertEquals("5", merged.stringValue(includedKey))
    }
}
// KUN <--
