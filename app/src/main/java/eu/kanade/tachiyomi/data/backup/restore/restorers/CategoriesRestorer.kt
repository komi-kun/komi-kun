package eu.kanade.tachiyomi.data.backup.restore.restorers

import app.cash.sqldelight.async.coroutines.awaitAsOne
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import tachiyomi.data.Database
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.library.service.LibraryPreferences

@Inject
class CategoriesRestorer(
    private val database: Database,
    private val getCategories: GetCategories,
    private val libraryPreferences: LibraryPreferences,
) {
    // SY -->
    var isSync: Boolean = false
    // SY <--

    suspend operator fun invoke(backupCategories: List<BackupCategory>) {
        if (backupCategories.isNotEmpty()) {
            val dbCategories = getCategories.await()
            val dbCategoriesByName = dbCategories.associateBy { it.name }
            // SY -->
            val dbCategoriesByUid = dbCategories.associateBy { it.uid } // Map by UID
            // SY <--

            var nextOrder = dbCategories.maxOfOrNull { it.order }?.plus(1) ?: 0

            val categories = backupCategories
                .sortedBy { it.order }
                // SY -->
                .map { backupCategory ->
                    var dbCategory = if (backupCategory.uid != 0L) {
                        dbCategoriesByUid[backupCategory.uid]
                    } else {
                        null
                    }

                    if (dbCategory == null) {
                        dbCategory = dbCategoriesByName[backupCategory.name]
                    }

                    if (dbCategory != null) {
                        database.categoryQueries.update(
                            name = backupCategory.name,
                            order = backupCategory.order,
                            flags = backupCategory.flags,
                            // KMK -->
                            hidden = if (backupCategory.hidden) 1L else null,
                            // KMK <--
                            version = backupCategory.version,
                            uid = if (backupCategory.uid != 0L) backupCategory.uid else dbCategory.uid,
                            lastModifiedAt = backupCategory.lastModifiedAt,
                            isSyncing = 1,
                            categoryId = dbCategory.id,
                        )
                        return@map dbCategory
                    }

                    // SY -->
                    // A sync delta is a converging replica, not an import: new
                    // categories must land at the position the server holds or
                    // reorders never converge across devices.
                    val order = if (isSync) backupCategory.order else nextOrder++
                    // SY <--
                    database.categoryQueries.insert(
                        name = backupCategory.name,
                        order = order,
                        flags = backupCategory.flags,
                        // KMK -->
                        hidden = if (backupCategory.hidden) 1L else 0L,
                        // KMK <--
                        // SY -->
                        version = backupCategory.version,
                        uid = backupCategory.uid,
                        lastModifiedAt = backupCategory.lastModifiedAt,
                        // SY <--
                    ).awaitAsOne()
                        .let { id -> backupCategory.toCategory(id).copy(order = order) }
                }
            // SY <--

            // SY -->
            database.categoryQueries.resetIsSyncing()
            // SY <--

            libraryPreferences.categorizedDisplaySettings.set(
                (dbCategories + categories)
                    .distinctBy { it.flags }
                    .size > 1,
            )
        }
    }
}
