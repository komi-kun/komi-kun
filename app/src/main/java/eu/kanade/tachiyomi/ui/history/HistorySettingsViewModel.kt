package eu.kanade.tachiyomi.ui.history

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.history.service.HistoryPreferences

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class HistorySettingsViewModel(
    val historyPreferences: HistoryPreferences,
    // KUN -->
    val getCategories: GetCategories,
    // KUN <--
) : ViewModel() {

    // KUN -->
    val includedCategories = historyPreferences.filterIncludedCategories
    val excludedCategories = historyPreferences.filterExcludedCategories
    val inclusionWins = historyPreferences.filterCategoryInclusionWins

    fun cycleCategory(category: Category) {
        when (category.id) {
            in includedCategories.get() -> {
                includedCategories.getAndSet { it - category.id }
                excludedCategories.getAndSet { it + category.id }
            }

            in excludedCategories.get() -> excludedCategories.getAndSet { it - category.id }
            else -> includedCategories.getAndSet { it + category.id }
        }
    }

    fun setInclusionWins(value: Boolean) {
        inclusionWins.set(value)
    }

    /** Clears both category lists; the precedence choice is left as it is. */
    fun resetCategoryFilter() {
        // Explicit empty values, not `delete()`: with the filter synced, an absent key loses the
        // merge to the remote value and the reset would be undone on the next sync.
        includedCategories.set(emptyList())
        excludedCategories.set(emptyList())
    }
    // KUN <--

    fun toggleFilter(preference: (HistoryPreferences) -> Preference<TriState>) {
        preference(historyPreferences).getAndSet {
            it.next()
        }
    }

    fun toggleSwitch(preference: (HistoryPreferences) -> Preference<Boolean>) {
        preference(historyPreferences).getAndSet { !it }
    }
}
