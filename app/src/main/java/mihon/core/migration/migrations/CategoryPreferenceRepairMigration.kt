// KUN -->
package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.service.HistoryPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.upcoming.service.UpcomingPreferences
import tachiyomi.domain.updates.service.UpdatesPreferences

/**
 * Removes the ids of categories that no longer exist from every preference that stores category ids,
 * once, on upgrade.
 *
 * [CategoryPreferencesCleanupMigration] (73f) only covered the library/download `Set<String>` filters
 * and `defaultCategory`; the History, Updates and Upcoming `List<Long>` filters it skipped can still
 * hold the id of a category deleted before this fork pruned them on deletion. A stale id there keeps
 * filtering on a category that is gone — with only stale include ids the tab comes up empty — and
 * because `category.id` is a rowid the same id can later belong to a different category.
 *
 * Every value is intersected with the live ids, and a preference is written only when the
 * intersection actually differs, so the migration emits nothing on a store that is already clean and
 * can safely run twice. A fresh install never reaches it: `Migrator` seeds `applied` from the stored
 * version code, and `0` selects the [mihon.core.migration.InitialMigrationStrategy], which runs only
 * the `isAlways` migrations.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class CategoryPreferenceRepairMigration(
    private val libraryPreferences: LibraryPreferences,
    private val downloadPreferences: DownloadPreferences,
    private val historyPreferences: HistoryPreferences,
    private val updatesPreferences: UpdatesPreferences,
    private val upcomingPreferences: UpcomingPreferences,
    private val getCategories: GetCategories,
) : Migration {
    override val version: Float = 85f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withContext(Dispatchers.IO) {
        // The category table seeds the system category (id 0), so it is live like any other row.
        val liveCategoryIds = getCategories.await().mapTo(mutableSetOf()) { it.id }
        val liveCategoryIdStrings = liveCategoryIds.mapTo(mutableSetOf()) { it.toString() }

        val stringPreferences = listOf(
            libraryPreferences.updateCategories,
            libraryPreferences.updateCategoriesExclude,
            libraryPreferences.filterCategoriesInclude,
            libraryPreferences.filterCategoriesExclude,
            downloadPreferences.removeExcludeCategories,
            downloadPreferences.downloadNewChapterCategories,
            downloadPreferences.downloadNewChapterCategoriesExclude,
        )
        stringPreferences.forEach { preference ->
            val ids = preference.get()
            val remaining = ids.intersect(liveCategoryIdStrings)
            if (remaining != ids) preference.set(remaining)
        }

        val longPreferences = listOf(
            historyPreferences.filterIncludedCategories,
            historyPreferences.filterExcludedCategories,
            updatesPreferences.filterIncludedCategories,
            updatesPreferences.filterExcludedCategories,
            upcomingPreferences.filterIncludedCategories,
            upcomingPreferences.filterExcludedCategories,
        )
        longPreferences.forEach { preference ->
            val ids = preference.get()
            // `filter` rather than `intersect`: the preference is a List and its order is kept.
            val remaining = ids.filter { it in liveCategoryIds }
            if (remaining != ids) preference.set(remaining)
        }

        // -1 is `defaultCategory`'s "no default category" sentinel, not an id.
        val defaultCategory = libraryPreferences.defaultCategory.get()
        if (defaultCategory >= 0 && defaultCategory.toLong() !in liveCategoryIds) {
            libraryPreferences.defaultCategory.delete()
        }

        return@withContext true
    }
}
// KUN <--
