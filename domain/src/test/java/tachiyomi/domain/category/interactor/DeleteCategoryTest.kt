// KUN -->
package tachiyomi.domain.category.interactor

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.category.service.CategoryTombstones
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.service.HistoryPreferences
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Deleting a category must not leave its id behind in the History category filter: the filter stores
 * plain ids, so a stale one would keep filtering on a category that no longer exists.
 */
class DeleteCategoryTest {

    private fun deleteCategory(
        historyPreferences: HistoryPreferences,
        categoryRepository: CategoryRepository = mockk(relaxed = true),
    ): DeleteCategory {
        val store = InMemoryPreferenceStore()
        return DeleteCategory(
            categoryRepository = categoryRepository,
            libraryPreferences = LibraryPreferences(store),
            downloadPreferences = DownloadPreferences(store),
            categoryTombstones = CategoryTombstones { },
            historyPreferences = historyPreferences,
        )
    }

    private fun historyPreferences(store: InMemoryPreferenceStore) = HistoryPreferences(store)

    @Test
    fun `deleted category ids are dropped from both history filter lists`() = runTest {
        val store = InMemoryPreferenceStore()
        val preferences = historyPreferences(store)
        preferences.filterIncludedCategories.set(listOf(1L, 2L))
        preferences.filterExcludedCategories.set(listOf(1L, 3L))

        val result = deleteCategory(preferences).await(1L)

        assertTrue(result is DeleteCategory.Result.Success)
        assertEquals(listOf(2L), preferences.filterIncludedCategories.get())
        assertEquals(listOf(3L), preferences.filterExcludedCategories.get())
    }

    @Test
    fun `deleting a category that is not in the filter does not write the filter`() = runTest {
        val store = TrackingPreferenceStore()
        val preferences = HistoryPreferences(store)
        preferences.filterIncludedCategories.set(listOf(2L))
        preferences.filterExcludedCategories.set(listOf(3L))
        store.writes.clear()

        // Positive control: the recorder sees writes made through these preferences, so the empty
        // assertion below cannot pass vacuously.
        preferences.filterIncludedCategories.set(listOf(4L))
        assertEquals(listOf(HistoryPreferences.FILTER_INCLUDED_CATEGORIES_PREF_KEY), store.writes)
        preferences.filterIncludedCategories.set(listOf(2L))
        store.writes.clear()

        val result = deleteCategory(preferences).await(1L)

        assertTrue(result is DeleteCategory.Result.Success)
        // No write at all, not merely "the same value written again": the guard skips the store.
        assertEquals(emptyList<String>(), store.writes)
        assertEquals(listOf(2L), preferences.filterIncludedCategories.get())
        assertEquals(listOf(3L), preferences.filterExcludedCategories.get())
    }

    @Test
    fun `an empty history filter stays empty`() = runTest {
        val store = InMemoryPreferenceStore()
        val preferences = historyPreferences(store)

        deleteCategory(preferences).await(1L)

        assertEquals(emptyList<Long>(), preferences.filterIncludedCategories.get())
        assertEquals(emptyList<Long>(), preferences.filterExcludedCategories.get())
    }

    @Test
    fun `a failing category deletion leaves the history filter alone`() = runTest {
        val store = InMemoryPreferenceStore()
        val preferences = historyPreferences(store)
        preferences.filterIncludedCategories.set(listOf(1L))

        val repository = mockk<CategoryRepository>(relaxed = true)
        coEvery { repository.get(1L) } throws IllegalStateException("boom")

        val result = deleteCategory(preferences, repository).await(1L)

        assertTrue(result is DeleteCategory.Result.InternalError)
        assertEquals(listOf(1L), preferences.filterIncludedCategories.get())
    }
}

/**
 * Delegating store that records every `set`/`delete` on an object preference, so a test can tell
 * "wrote the same value again" from "did not touch the store at all".
 */
private class TrackingPreferenceStore(
    private val delegate: PreferenceStore = InMemoryPreferenceStore(),
) : PreferenceStore by delegate {

    val writes = mutableListOf<String>()

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> {
        val preference = delegate.getObjectFromString(key, defaultValue, serializer, deserializer)
        return object : Preference<T> {
            override fun key() = preference.key()
            override fun get() = preference.get()

            override fun set(value: T) {
                writes += key
                preference.set(value)
            }

            override fun isSet() = preference.isSet()

            override fun delete() {
                writes += key
                preference.delete()
            }

            override fun defaultValue() = preference.defaultValue()
            override fun changes() = preference.changes()
            override fun stateIn(scope: CoroutineScope) = preference.stateIn(scope)
        }
    }
}
// KUN <--
