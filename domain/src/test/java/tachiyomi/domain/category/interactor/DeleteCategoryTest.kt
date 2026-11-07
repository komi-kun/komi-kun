// KUN -->
package tachiyomi.domain.category.interactor

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
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
    fun `deleting a category that is not in the filter leaves it untouched`() = runTest {
        val store = InMemoryPreferenceStore()
        val preferences = historyPreferences(store)
        preferences.filterIncludedCategories.set(listOf(2L))
        preferences.filterExcludedCategories.set(listOf(3L))

        deleteCategory(preferences).await(1L)

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
// KUN <--
