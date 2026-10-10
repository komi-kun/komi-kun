package tachiyomi.data.category

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository

class CategoryRepositoryImpl(
    private val database: Database,
) : CategoryRepository {

    override suspend fun get(id: Long): Category? {
        return database.categoriesQueries
            .getCategory(id, CategoryMapper::mapCategory)
            .awaitAsOneOrNull()
    }

    override suspend fun getAll(): List<Category> {
        return database.categoriesQueries
            .getCategories(CategoryMapper::mapCategory)
            .awaitAsList()
    }

    override fun getAllAsFlow(): Flow<List<Category>> {
        return database.categoriesQueries
            .getCategories(CategoryMapper::mapCategory)
            .subscribeToList()
    }

    override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> {
        return database.categoriesQueries
            .getCategoriesByMangaId(mangaId, CategoryMapper::mapCategory)
            .awaitAsList()
    }

    override fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>> {
        return database.categoriesQueries
            .getCategoriesByMangaId(mangaId, CategoryMapper::mapCategory)
            .subscribeToList()
    }

    // SY -->
    override suspend fun insert(category: Category): Long? {
        return database.categoriesQueries.insertWithNextOrder(
            name = category.name,
            flags = category.flags,
            // KMK -->
            hidden = if (category.hidden) 1L else 0L,
            // KMK <--
            // SY -->
            version = category.version,
            uid = category.uid,
            lastModifiedAt = category.lastModifiedAt,
            // SY <--
        ).awaitAsOneOrNull()
    }
    // SY <--

    override suspend fun updateName(categoryId: Long, name: String) {
        database.categoriesQueries.updateName(name = name, categoryId = categoryId)
    }

    override suspend fun updateFlags(categoryId: Long, flags: Long) {
        database.categoriesQueries.updateFlags(flags = flags, categoryId = categoryId)
    }

    override suspend fun updateAllFlags(flags: Long?) {
        database.categoriesQueries.updateAllFlags(flags)
    }

    override suspend fun updateAllOrders(orderedIds: List<Long>) {
        database.transaction {
            val current = database.categoriesQueries.getUserCategoryIds().awaitAsList()
            val ids = orderedIds.filter { it in current } + current.filterNot { it in orderedIds }
            ids.forEachIndexed { index, categoryId ->
                database.categoriesQueries.updateOrder(order = -index - 2L, categoryId = categoryId)
            }
            ids.forEachIndexed { index, categoryId ->
                database.categoriesQueries.updateOrder(order = index.toLong(), categoryId = categoryId)
            }
        }
    }

    // KMK -->
    override suspend fun updatePartial(update: CategoryUpdate) {
        database.updatePartialBlocking(update)
    }

    override suspend fun updatePartial(updates: List<CategoryUpdate>) {
        database.transaction {
            updates.forEach { database.updatePartialBlocking(it) }
        }
    }

    private suspend fun Database.updatePartialBlocking(update: CategoryUpdate) {
        categoriesQueries.update(
            name = update.name,
            order = update.order,
            flags = update.flags,
            // KMK -->
            hidden = update.hidden?.let { if (it) 1L else 0L },
            // KMK <--
            // SY -->
            version = update.version,
            uid = update.uid,
            lastModifiedAt = update.lastModifiedAt,
            isSyncing = null,
            // SY <--
            categoryId = update.id,
        )
    }
    // KMK <--

    override suspend fun delete(categoryId: Long) {
        database.categoriesQueries.delete(categoryId = categoryId)
    }
}
