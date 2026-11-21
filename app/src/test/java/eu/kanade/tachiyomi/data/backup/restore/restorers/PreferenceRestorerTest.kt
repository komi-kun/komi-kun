// KUN -->
package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import exh.source.EXH_OLD_ID
import exh.source.EXH_SOURCE_ID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.service.CategoryIdPreferences
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.service.HistoryPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.upcoming.service.UpcomingPreferences
import tachiyomi.domain.updates.service.UpdatesPreferences

class PreferenceRestorerTest {

    private val pinnedKey = SourcePreferences.PINNED_SOURCES_PREF_KEY

    /**
     * The real category-id inventory, built over a clean in-memory store of its own: the tests below
     * hand the restorer a specialised store that fails on any unexpected access, and the inventory
     * only contributes its keys.
     */
    private fun categoryIdPreferences(): CategoryIdPreferences {
        val store = InMemoryPreferenceStore()
        return CategoryIdPreferences(
            libraryPreferences = LibraryPreferences(store),
            downloadPreferences = DownloadPreferences(store),
            historyPreferences = HistoryPreferences(store),
            updatesPreferences = UpdatesPreferences(store),
            upcomingPreferences = UpcomingPreferences(store),
            connectionsPreferences = ConnectionsPreferences(store),
        )
    }

    private fun restorer(
        store: PreferenceStore,
        getCategories: GetCategories = mockk<GetCategories>(),
    ): PreferenceRestorer {
        return PreferenceRestorer(
            context = mockk<Context>(relaxed = true),
            getCategories = getCategories,
            categoryIdPreferences = categoryIdPreferences(),
            preferenceStore = store,
        )
    }

    private fun restorePins(
        store: StringSetPreferenceStore,
        incoming: Set<String>,
        options: RestoreOptions,
    ): Set<String> {
        restorer(store).restorePinnedSourcesPreference(pinnedKey, incoming, store, options)
        return store.getStringSet(pinnedKey).get()
    }

    @Test
    fun `default options union the synced pins with the local pins`() {
        val store = StringSetPreferenceStore(pinnedKey to setOf("local"))

        val result = restorePins(store, setOf("synced"), RestoreOptions())

        assertEquals(setOf("local", "synced"), result)
    }

    @Test
    fun `default options keep the local pins when the synced set is empty`() {
        val store = StringSetPreferenceStore(pinnedKey to setOf("local"))

        val result = restorePins(store, emptySet(), RestoreOptions())

        assertEquals(setOf("local"), result)
    }

    @Test
    fun `skip option leaves the local pins untouched`() {
        val store = StringSetPreferenceStore(pinnedKey to setOf("local"))

        val result = restorePins(store, setOf("synced"), RestoreOptions(skipPinnedSources = true))

        assertEquals(setOf("local"), result)
    }

    @Test
    fun `skip option ignores an empty synced set`() {
        val store = StringSetPreferenceStore(pinnedKey to setOf("local"))

        val result = restorePins(store, emptySet(), RestoreOptions(skipPinnedSources = true))

        assertEquals(setOf("local"), result)
    }

    @Test
    fun `replace option sets the synced pins exactly`() {
        val store = StringSetPreferenceStore(pinnedKey to setOf("local"))

        val result = restorePins(store, setOf("synced"), RestoreOptions(replacePinnedSources = true))

        assertEquals(setOf("synced"), result)
    }

    @Test
    fun `replace option clears the local pins when the synced set is empty`() {
        val store = StringSetPreferenceStore(pinnedKey to setOf("local"))

        val result = restorePins(store, emptySet(), RestoreOptions(replacePinnedSources = true))

        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `sync restore without protocol v2 unions and preserves local pins`() {
        // Drive, WebDAV and SyncYomi v1 keep the remote preference when both sides carry it, so the
        // restore must not treat the synced set as authoritative.
        val store = StringSetPreferenceStore(pinnedKey to setOf("local"))

        val result = restorePins(
            store,
            setOf("synced"),
            RestoreOptions(skipPinnedSources = false, replacePinnedSources = false),
        )

        assertEquals(setOf("local", "synced"), result)
    }

    @Test
    fun `sync restore without protocol v2 keeps local pins when the synced set is empty`() {
        val store = StringSetPreferenceStore(pinnedKey to setOf("local"))

        val result = restorePins(
            store,
            emptySet(),
            RestoreOptions(skipPinnedSources = false, replacePinnedSources = false),
        )

        assertEquals(setOf("local"), result)
    }

    @Test
    fun `sync restore on protocol v2 applies the synced set exactly`() {
        val store = StringSetPreferenceStore(pinnedKey to setOf("local"))

        val result = restorePins(
            store,
            setOf("synced"),
            RestoreOptions(skipPinnedSources = false, replacePinnedSources = true),
        )

        assertEquals(setOf("synced"), result)
    }

    @Test
    fun `replace option still migrates legacy source ids`() {
        val store = StringSetPreferenceStore(pinnedKey to setOf("local"))

        val result = restorePins(
            store,
            setOf(EXH_OLD_ID.toString()),
            RestoreOptions(replacePinnedSources = true),
        )

        assertEquals(setOf(EXH_SOURCE_ID.toString()), result)
    }

    @Test
    fun `default options still migrate legacy source ids when unioning`() {
        val store = StringSetPreferenceStore(pinnedKey to setOf("local"))

        val result = restorePins(store, setOf(EXH_OLD_ID.toString()), RestoreOptions())

        assertEquals(setOf("local", EXH_SOURCE_ID.toString()), result)
    }

    @Test
    fun `other string set preferences are not affected by the replace option`() = runTest {
        suspend fun restore(replacePinnedSources: Boolean): Set<String> {
            val key = "another_string_set"
            val store = StringSetPreferenceStore(key to setOf("local"))
            restorer(store).restorePreferences(
                listOf(BackupPreference(key, StringSetPreferenceValue(setOf("synced")))),
                store,
                backupCategories = null,
                options = RestoreOptions(replacePinnedSources = replacePinnedSources),
            )
            return store.getStringSet(key).get()
        }

        assertEquals(setOf("synced"), restore(replacePinnedSources = false))
        assertEquals(setOf("synced"), restore(replacePinnedSources = true))
    }

    @Test
    fun `device-local app state is not restored from an app preference payload`() = runTest {
        val key = Preference.appStateKey("sync_pinned_sources")
        val store = mockk<PreferenceStore>(relaxed = true)
        every { store.getAll() } returns mapOf(key to true)

        restorer(store).restorePreferences(
            listOf(BackupPreference(key, BooleanPreferenceValue(true))),
            store,
            backupCategories = null,
            options = RestoreOptions(),
            isAppPreferences = true,
        )

        verify(exactly = 0) { store.getBoolean(key, any()) }
    }

    @Test
    fun `the history category filter is app state and is not restored`() = runTest {
        // The filter lives on one device only: a payload carrying it must not write it back. The key
        // comes from the real preference, so one that loses its app-state wrapper fails this test.
        val key = HistoryPreferences(InMemoryPreferenceStore()).filterIncludedCategories.key()
        val store = mockk<PreferenceStore>(relaxed = true)
        every { store.getAll() } returns mapOf(key to "1,2")

        restorer(store).restorePreferences(
            listOf(BackupPreference(key, StringPreferenceValue("5,6"))),
            store,
            backupCategories = null,
            options = RestoreOptions(),
            isAppPreferences = true,
        )

        verify(exactly = 0) { store.getString(key) }
    }

    @Test
    fun `app-state-like source preference keys are still restored`() = runTest {
        // Source preferences are namespaced separately; the app-state guard must not drop them.
        val key = Preference.appStateKey("source_option")
        val store = mockk<PreferenceStore>(relaxed = true)
        val preference = mockk<Preference<Boolean>>()
        every { store.getAll() } returns mapOf(key to true)
        every { store.getBoolean(key, any()) } returns preference
        every { preference.set(any()) } returns Unit

        restorer(store).restorePreferences(
            listOf(BackupPreference(key, BooleanPreferenceValue(true))),
            store,
            backupCategories = null,
            options = RestoreOptions(),
            isAppPreferences = false,
        )

        verify(exactly = 1) { preference.set(true) }
    }

    @Test
    fun `unrelated string preferences are still restored verbatim`() = runTest {
        val key = "some_string_preference"
        val store = StringPreferenceStore(key to "local")

        PreferenceRestorer(
            context = mockk<Context>(relaxed = true),
            getCategories = mockk<GetCategories>(),
            categoryIdPreferences = categoryIdPreferences(),
            preferenceStore = store,
        ).restorePreferences(
            listOf(BackupPreference(key, StringPreferenceValue("remote"))),
            store,
            backupCategories = null,
            options = RestoreOptions(),
            isAppPreferences = true,
        )

        assertEquals("remote", store.getString(key, "").get())
    }

    // region category ids

    /** The live categories of "this device": "Reading" is id 3 here and "Watching" id 4. */
    private fun liveCategories() = listOf(
        Category(id = 3L, name = "Reading", order = 0L, flags = 0L, hidden = false),
        Category(id = 4L, name = "Watching", order = 1L, flags = 0L, hidden = false),
    )

    /** The categories of the device the backup came from: ids 10, 11 and 12, one absent here. */
    private fun backedUpCategories() = listOf(
        BackupCategory(name = "Reading", order = 0, id = 10, uid = 1),
        BackupCategory(name = "Gone here", order = 1, id = 11, uid = 2),
        BackupCategory(name = "Watching", order = 2, id = 12, uid = 3),
    )

    private fun getCategories(categories: List<Category>): GetCategories {
        val getCategories = mockk<GetCategories>()
        coEvery { getCategories.await() } returns categories
        return getCategories
    }

    @Test
    fun `a restored Updates category filter is remapped by category name`() = runTest {
        // `getLongArray` stores the list as a comma-joined string of ids, so the payload carries
        // "10,11,12": 10 is "Reading" (id 3 here), 12 is "Watching" (id 4 here), 11 names a category
        // this device does not have.
        val key = UpdatesPreferences(InMemoryPreferenceStore()).filterIncludedCategories.key()
        val store = StringPreferenceStore(key to "5")

        restorer(store, getCategories(liveCategories())).restorePreferences(
            listOf(BackupPreference(key, StringPreferenceValue("10,11,12"))),
            store,
            backupCategories = backedUpCategories(),
            options = RestoreOptions(),
            isAppPreferences = true,
        )

        assertEquals("3,4", store.getString(key, "").get())
    }

    @Test
    fun `a restored Upcoming category filter keeps the order of the surviving ids`() = runTest {
        val key = UpcomingPreferences(InMemoryPreferenceStore()).filterExcludedCategories.key()
        val store = StringPreferenceStore(key to "5")

        restorer(store, getCategories(liveCategories())).restorePreferences(
            listOf(BackupPreference(key, StringPreferenceValue("12,11,10"))),
            store,
            backupCategories = backedUpCategories(),
            options = RestoreOptions(),
            isAppPreferences = true,
        )

        // The list is the user's order, not a set: "Watching" (4) stays ahead of "Reading" (3).
        assertEquals("4,3", store.getString(key, "").get())
    }

    @Test
    fun `the Discord incognito set is remapped by category name`() = runTest {
        val key = ConnectionsPreferences(InMemoryPreferenceStore()).discordRPCIncognitoCategories.key()
        val store = StringSetPreferenceStore(key to setOf("5"))

        restorer(store, getCategories(liveCategories())).restorePreferences(
            listOf(BackupPreference(key, StringSetPreferenceValue(setOf("10", "11", "12")))),
            store,
            backupCategories = backedUpCategories(),
            options = RestoreOptions(),
            isAppPreferences = true,
        )

        assertEquals(setOf("3", "4"), store.getStringSet(key).get())
    }

    @Test
    fun `a category filter naming no live category leaves the local filter alone`() = runTest {
        // Every backed-up id names a category this device does not have: writing an empty list would
        // silently clear a filter the backup never described.
        val key = UpdatesPreferences(InMemoryPreferenceStore()).filterExcludedCategories.key()
        val store = StringPreferenceStore(key to "5")

        restorer(store, getCategories(liveCategories())).restorePreferences(
            listOf(BackupPreference(key, StringPreferenceValue("11,99"))),
            store,
            backupCategories = backedUpCategories(),
            options = RestoreOptions(),
            isAppPreferences = true,
        )

        assertEquals("5", store.getString(key, "").get())
    }

    // endregion
}

/**
 * Minimal store backed by a mutable map. [tachiyomi.core.common.preference.InMemoryPreferenceStore]
 * hands out a fresh preference object per lookup, so writes made through the returned object are
 * not observable; these tests need to read back what the restorer wrote.
 */
private class StringSetPreferenceStore(
    vararg initial: Pair<String, Set<String>>,
) : PreferenceStore {

    private val values = initial.toMap().toMutableMap()

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> {
        return object : Preference<Set<String>> {
            override fun key() = key
            override fun get() = values[key] ?: defaultValue
            override fun set(value: Set<String>) {
                values[key] = value
            }

            override fun isSet() = key in values
            override fun delete() {
                values.remove(key)
            }

            override fun defaultValue() = defaultValue
            override fun changes(): Flow<Set<String>> = flowOf(get())
            override fun stateIn(scope: CoroutineScope): StateFlow<Set<String>> =
                MutableStateFlow(get())
        }
    }

    override fun getAll(): Map<String, *> = values.toMap()

    override fun getString(key: String, defaultValue: String): Preference<String> = unsupported(key)

    override fun getLong(key: String, defaultValue: Long): Preference<Long> = unsupported(key)

    override fun getInt(key: String, defaultValue: Int): Preference<Int> = unsupported(key)

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> = unsupported(key)

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = unsupported(key)

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = unsupported(key)

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = unsupported(key)

    override fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): Preference<Set<T>> = unsupported(key)

    private fun unsupported(key: String): Nothing = error("Unexpected preference access: $key")
}

private class StringPreferenceStore(
    vararg initial: Pair<String, String>,
) : PreferenceStore {

    private val values = initial.toMap().toMutableMap()

    override fun getString(key: String, defaultValue: String): Preference<String> {
        return object : Preference<String> {
            override fun key() = key
            override fun get() = values[key] ?: defaultValue
            override fun set(value: String) {
                values[key] = value
            }

            override fun isSet() = key in values
            override fun delete() {
                values.remove(key)
            }

            override fun defaultValue() = defaultValue
            override fun changes(): Flow<String> = flowOf(get())
            override fun stateIn(scope: CoroutineScope): StateFlow<String> =
                MutableStateFlow(get())
        }
    }

    override fun getAll(): Map<String, *> = values.toMap()

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> = unsupported(key)

    override fun getLong(key: String, defaultValue: Long): Preference<Long> = unsupported(key)

    override fun getInt(key: String, defaultValue: Int): Preference<Int> = unsupported(key)

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> = unsupported(key)

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = unsupported(key)

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = unsupported(key)

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = unsupported(key)

    override fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): Preference<Set<T>> = unsupported(key)

    private fun unsupported(key: String): Nothing = error("Unexpected preference access: $key")
}
// KUN <--
