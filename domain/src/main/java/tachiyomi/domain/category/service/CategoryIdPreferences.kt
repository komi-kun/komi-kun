package tachiyomi.domain.category.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.service.HistoryPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.upcoming.service.UpcomingPreferences
import tachiyomi.domain.updates.service.UpdatesPreferences

/**
 * The typed inventory of every preference that stores local category ids. It lives in one place
 * because two readers need the same list — `PruneCategoryReferences` (the steady-state prune after a
 * deletion) and `CategoryPreferenceRepairMigration` (the one-shot repair of existing drift) — and two
 * hand-maintained copies of the list had already started to drift.
 *
 * - [stringFilters] hold the ids as strings: the four library update/filter sets and the three
 *   download sets.
 * - [longFilters] hold them as `List<Long>` (order kept): the History, Updates and Upcoming
 *   include/exclude pair each.
 * - [defaultCategory] is the single "add to library" default; `-1` means unset.
 *
 * The app-level Discord RPC incognito set is *not* here: it is a `ConnectionsPreferences` field in
 * `app`, which `domain` cannot reach, so its own prune and repair paths handle it separately.
 */
@Inject
@SingleIn(AppScope::class)
class CategoryIdPreferences(
    libraryPreferences: LibraryPreferences,
    downloadPreferences: DownloadPreferences,
    historyPreferences: HistoryPreferences,
    updatesPreferences: UpdatesPreferences,
    upcomingPreferences: UpcomingPreferences,
) {

    val stringFilters: List<Preference<Set<String>>> = listOf(
        libraryPreferences.updateCategories,
        libraryPreferences.updateCategoriesExclude,
        libraryPreferences.filterCategoriesInclude,
        libraryPreferences.filterCategoriesExclude,
        downloadPreferences.removeExcludeCategories,
        downloadPreferences.downloadNewChapterCategories,
        downloadPreferences.downloadNewChapterCategoriesExclude,
    )

    val longFilters: List<Preference<List<Long>>> = listOf(
        historyPreferences.filterIncludedCategories,
        historyPreferences.filterExcludedCategories,
        updatesPreferences.filterIncludedCategories,
        updatesPreferences.filterExcludedCategories,
        upcomingPreferences.filterIncludedCategories,
        upcomingPreferences.filterExcludedCategories,
    )

    val defaultCategory: Preference<Int> = libraryPreferences.defaultCategory
}
