package eu.kanade.tachiyomi.ui.history

import androidx.lifecycle.ViewModel
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.history.service.HistoryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class HistorySettingsViewModel(
    val historyPreferences: HistoryPreferences = Injekt.get(),
) : ViewModel() {

    fun toggleFilter(preference: (HistoryPreferences) -> Preference<TriState>) {
        preference(historyPreferences).getAndSet {
            it.next()
        }
    }

    fun toggleSwitch(preference: (HistoryPreferences) -> Preference<Boolean>) {
        preference(historyPreferences).getAndSet { !it }
    }
}
