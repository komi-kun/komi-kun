// KUN -->
package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
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
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.history.service.HistoryPreferences

class PreferenceRestorerTest {

    private val pinnedKey = SourcePreferences.PINNED_SOURCES_PREF_KEY

    private fun restorer(store: PreferenceStore): PreferenceRestorer {
        return PreferenceRestorer(
            context = mockk<Context>(relaxed = true),
            getCategories = mockk<GetCategories>(),
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

    private val historyIncludedKey = HistoryPreferences.FILTER_INCLUDED_CATEGORIES_PREF_KEY

    private fun category(id: Long, name: String) =
        Category(id = id, name = name, order = 0L, flags = 0L, hidden = false)

    private suspend fun restoreHistoryFilter(
        store: StringPreferenceStore,
        key: String,
        payload: String,
        localCategories: List<Category> = emptyList(),
        backupCategories: List<BackupCategory>? = null,
    ) {
        val getCategories = mockk<GetCategories>()
        coEvery { getCategories.await() } returns localCategories
        PreferenceRestorer(
            context = mockk<Context>(relaxed = true),
            getCategories = getCategories,
            preferenceStore = store,
        ).restorePreferences(
            listOf(BackupPreference(key, StringPreferenceValue(payload))),
            store,
            backupCategories = backupCategories,
            options = RestoreOptions(),
            isAppPreferences = true,
        )
    }

    @Test
    fun `history category filter ids are remapped by category name`() = runTest {
        val store = StringPreferenceStore()

        restoreHistoryFilter(
            store = store,
            key = historyIncludedKey,
            payload = "5,6",
            localCategories = listOf(category(7L, "Action"), category(9L, "Drama")),
            backupCategories = listOf(BackupCategory(name = "Action", id = 5L), BackupCategory(name = "Drama", id = 6L)),
        )

        assertEquals("7,9", store.getString(historyIncludedKey, "").get())
    }

    @Test
    fun `history category filter keeps the system category and drops unknown ids`() = runTest {
        val store = StringPreferenceStore()

        restoreHistoryFilter(
            store = store,
            key = historyIncludedKey,
            payload = "0,5,42",
            localCategories = listOf(category(7L, "Action")),
            backupCategories = listOf(BackupCategory(name = "Action", id = 5L)),
        )

        // 0 is the system sentinel (never in the backup category list); 42 maps to nothing.
        assertEquals("0,7", store.getString(historyIncludedKey, "").get())
    }

    @Test
    fun `history category filter is left alone when no id maps`() = runTest {
        val store = StringPreferenceStore(historyIncludedKey to "1,2")

        restoreHistoryFilter(
            store = store,
            key = historyIncludedKey,
            payload = "5,42",
            localCategories = listOf(category(7L, "Action")),
            backupCategories = listOf(BackupCategory(name = "Comedy", id = 5L)),
        )

        // Never collapses to "0" and never wipes the local filter.
        assertEquals("1,2", store.getString(historyIncludedKey, "").get())
    }

    @Test
    fun `history category filter is left alone when only the system id survives`() = runTest {
        val store = StringPreferenceStore(historyIncludedKey to "1,2")

        restoreHistoryFilter(
            store = store,
            key = historyIncludedKey,
            payload = "0,42",
            localCategories = listOf(category(7L, "Action")),
            backupCategories = listOf(BackupCategory(name = "Comedy", id = 5L)),
        )

        // The payload named a real category (42) that cannot be mapped, so writing "0" would lose it.
        assertEquals("1,2", store.getString(historyIncludedKey, "").get())
    }

    @Test
    fun `history category filter is left alone when the payload has no category section`() = runTest {
        val store = StringPreferenceStore(historyIncludedKey to "1,2")

        restoreHistoryFilter(
            store = store,
            key = historyIncludedKey,
            payload = "5,6",
            backupCategories = null,
        )

        assertEquals("1,2", store.getString(historyIncludedKey, "").get())
    }

    @Test
    fun `the history precedence flag is restored verbatim`() = runTest {
        val key = HistoryPreferences.FILTER_INCLUSION_WINS_PREF_KEY
        val store = mockk<PreferenceStore>(relaxed = true)
        val preference = mockk<Preference<Boolean>>()
        every { store.getAll() } returns mapOf(key to false)
        every { store.getBoolean(key, any()) } returns preference
        every { preference.set(any()) } returns Unit

        PreferenceRestorer(
            context = mockk<Context>(relaxed = true),
            getCategories = mockk<GetCategories>(),
            preferenceStore = store,
        ).restorePreferences(
            listOf(BackupPreference(key, BooleanPreferenceValue(true))),
            store,
            backupCategories = null,
            options = RestoreOptions(),
            isAppPreferences = true,
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
