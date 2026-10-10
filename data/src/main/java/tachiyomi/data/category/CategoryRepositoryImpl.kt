package tachiyomi.data.category

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CategoryRepositoryImpl(
    private val database: Database,
) : CategoryRepository {

    override suspend fun get(id: Long): Category? {
        return database.categoryQueries
            .getCategory(id, CategoryMapper::mapCategory)
            .awaitAsOneOrNull()
    }

    override suspend fun getAll(): List<Category> {
        return database.categoryQueries
            .getCategories(CategoryMapper::mapCategory)
            .awaitAsList()
    }

    override fun getAllAsFlow(): Flow<List<Category>> {
        return database.categoryQueries
            .getCategories(CategoryMapper::mapCategory)
            .subscribeToList()
    }

    override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> {
        return database.categoryQueries
            .getCategoriesByMangaId(mangaId, CategoryMapper::mapCategory)
            .awaitAsList()
    }

    override fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>> {
        return database.categoryQueries
            .getCategoriesByMangaId(mangaId, CategoryMapper::mapCategory)
            .subscribeToList()
    }

    // SY -->
    override suspend fun insert(category: Category): Long? {
        return database.categoryQueries.insertWithNextOrder(
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

    override suspend fun restoreCategories(categories: List<Category>, isSync: Boolean): List<Category> {
        val dbCategories = database.categoryQueries
            .getCategories(CategoryMapper::mapCategory)
            .awaitAsList()
        val dbCategoriesByName = dbCategories.associateBy { it.name }
        val dbCategoriesByUid = dbCategories.associateBy { it.uid } // Map by UID

        var nextOrder = dbCategories.maxOfOrNull { it.order }?.plus(1) ?: 0

        val restored = categories
            .sortedBy { it.order }
            .map { category ->
                var dbCategory = if (category.uid != 0L) {
                    dbCategoriesByUid[category.uid]
                } else {
                    null
                }

                if (dbCategory == null) {
                    dbCategory = dbCategoriesByName[category.name]
                }

                if (dbCategory != null) {
                    database.categoryQueries.update(
                        name = category.name,
                        order = category.order,
                        flags = category.flags,
                        // KMK -->
                        hidden = if (category.hidden) 1L else null,
                        // KMK <--
                        version = category.version,
                        uid = if (category.uid != 0L) category.uid else dbCategory.uid,
                        lastModifiedAt = category.lastModifiedAt,
                        isSyncing = 1,
                        categoryId = dbCategory.id,
                    )
                    return@map dbCategory
                }

                // A sync delta is a converging replica, not an import: new
                // categories must land at the position the server holds or
                // reorders never converge across devices.
                val order = if (isSync) category.order else nextOrder++
                database.categoryQueries.insert(
                    name = category.name,
                    order = order,
                    flags = category.flags,
                    // KMK -->
                    hidden = if (category.hidden) 1L else 0L,
                    // KMK <--
                    version = category.version,
                    uid = category.uid,
                    lastModifiedAt = category.lastModifiedAt,
                ).awaitAsOne()
                    .let { id -> category.copy(id = id, order = order) }
            }

        database.categoryQueries.resetIsSyncing()

        return restored
    }
    // SY <--

    override suspend fun updateName(categoryId: Long, name: String) {
        database.categoryQueries.updateName(name = name, categoryId = categoryId)
    }

    override suspend fun updateFlags(categoryId: Long, flags: Long) {
        database.categoryQueries.updateFlags(flags = flags, categoryId = categoryId)
    }

    override suspend fun updateAllFlags(flags: Long?) {
        database.categoryQueries.updateAllFlags(flags)
    }

    override suspend fun updateAllOrders(orderedIds: List<Long>) {
        database.transaction {
            val current = database.categoryQueries.getUserCategoryIds().awaitAsList()
            val ids = orderedIds.filter { it in current } + current.filterNot { it in orderedIds }
            ids.forEachIndexed { index, categoryId ->
                database.categoryQueries.updateOrder(order = -index - 2L, categoryId = categoryId)
            }
            ids.forEachIndexed { index, categoryId ->
                database.categoryQueries.updateOrder(order = index.toLong(), categoryId = categoryId)
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
        categoryQueries.update(
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
        database.categoryQueries.delete(categoryId = categoryId)
    }
}
