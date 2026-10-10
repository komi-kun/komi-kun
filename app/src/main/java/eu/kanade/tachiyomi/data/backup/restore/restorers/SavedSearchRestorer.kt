package eu.kanade.tachiyomi.data.backup.restore.restorers

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import exh.EXHMigrations
import exh.util.nullIfBlank
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Inject
class SavedSearchRestorer(
    private val database: Database,
) {
    suspend fun restoreSavedSearches(backupSavedSearches: List<BackupSavedSearch>) {
        if (backupSavedSearches.isEmpty()) return

        val currentSavedSearches = database.saved_searchQueries
            // KMK -->
            // saved_searchQueries.selectNamesAndSources()
            .selectAll()
            // KMK <--
            .awaitAsList()

        database.transaction {
            backupSavedSearches.map {
                // KMK -->
                EXHMigrations.migrateBackupSavedSearch(it)
                // KMK <--
            }.filter { backupSavedSearch ->
                currentSavedSearches.none { currentSavedSearch ->
                    currentSavedSearch.source == backupSavedSearch.source &&
                        currentSavedSearch.name == backupSavedSearch.name &&
                        // KMK -->
                        currentSavedSearch.query.orEmpty() == backupSavedSearch.query &&
                        (currentSavedSearch.filters_json ?: "[]") == backupSavedSearch.filterList
                    // KMK <--
                }
            }.forEach { backupSavedSearch ->
                database.saved_searchQueries.insert(
                    source = backupSavedSearch.source,
                    name = backupSavedSearch.name,
                    query = backupSavedSearch.query.nullIfBlank(),
                    filtersJson = backupSavedSearch.filterList.nullIfBlank()
                        ?.takeUnless { it == "[]" },
                ).awaitAsOneOrNull()
            }
        }
    }
}
