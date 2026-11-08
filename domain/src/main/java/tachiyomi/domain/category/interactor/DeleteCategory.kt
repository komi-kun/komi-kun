package tachiyomi.domain.category.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.category.service.CategoryTombstones

class DeleteCategory(
    private val categoryRepository: CategoryRepository,
    // KUN -->
    // Upstream took `libraryPreferences` here to reset `defaultCategory`; that reset now lives in
    // [PruneCategoryReferences], so the sync deletion path behaves the same as this one.
    // KUN <--
    // SY -->
    private val categoryTombstones: CategoryTombstones,
    // SY <--
    // KUN -->
    private val pruneCategoryReferences: PruneCategoryReferences,
    // KUN <--
) {

    suspend fun await(categoryId: Long): Result {
        try {
            // SY -->
            val uid = categoryRepository.get(categoryId)?.uid
            // SY <--
            categoryRepository.delete(categoryId)
            // SY -->
            if (uid != null) categoryTombstones.remember(uid)
            // SY <--
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return Result.InternalError(e)
        }

        val orderedIds = categoryRepository.getAll()
            .filterNot(Category::isSystemCategory)
            .map { it.id }

        // KUN -->
        // The library, download and History/Updates/Upcoming filters that store category ids — and
        // `defaultCategory` with them — are reset in one place, because the non-v2 sync merge deletes
        // categories without going through this interactor.
        pruneCategoryReferences.prune(listOf(categoryId))
        // KUN <--

        return try {
            categoryRepository.updateAllOrders(orderedIds = orderedIds)
            Result.Success
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            Result.InternalError(e)
        }
    }

    sealed interface Result {
        data object Success : Result
        data class InternalError(val error: Throwable) : Result
    }
}
