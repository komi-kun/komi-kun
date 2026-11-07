package tachiyomi.domain.history.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getLongArray
import tachiyomi.domain.updates.service.USE_PANORAMA_COVER_PREF

@Inject
@SingleIn(AppScope::class)
class HistoryPreferences(
    private val preferenceStore: PreferenceStore,
) {

    val filterUnfinishedManga = preferenceStore.getEnum(
        "pref_filter_history_unfinished_manga",
        TriState.DISABLED,
    )

    val filterUnfinishedChapter = preferenceStore.getEnum(
        "pref_filter_history_unfinished_chapter",
        TriState.DISABLED,
    )

    val filterNonLibraryManga = preferenceStore.getEnum(
        "pref_filter_history_non_library_manga",
        TriState.DISABLED,
    )

    val usePanoramaCover = preferenceStore.getBoolean(
        USE_PANORAMA_COVER_PREF,
        false,
    )

    // KUN -->
    val filterIncludedCategories: Preference<List<Long>> = preferenceStore.getLongArray(
        FILTER_INCLUDED_CATEGORIES_PREF_KEY,
        emptyList(),
    )

    val filterExcludedCategories: Preference<List<Long>> = preferenceStore.getLongArray(
        FILTER_EXCLUDED_CATEGORIES_PREF_KEY,
        emptyList(),
    )

    /**
     * When true, a manga that explicitly matches an included category is shown even when it is also
     * in an excluded category. Off (the default) keeps the historical behaviour: exclusion wins.
     * An empty include list never rescues a manga, so exclusions still apply.
     */
    val filterCategoryInclusionWins = preferenceStore.getBoolean(
        "pref_filter_history_category_inclusion_wins",
        false,
    )
    // KUN <--

    // KUN -->
    companion object {
        const val FILTER_INCLUDED_CATEGORIES_PREF_KEY = "pref_filter_history_included_categories"
        const val FILTER_EXCLUDED_CATEGORIES_PREF_KEY = "pref_filter_history_excluded_categories"

        /** History filter preferences whose values are local category ids. */
        val categoryPreferenceKeys = setOf(
            FILTER_INCLUDED_CATEGORIES_PREF_KEY,
            FILTER_EXCLUDED_CATEGORIES_PREF_KEY,
        )
    }
    // KUN <--
}
