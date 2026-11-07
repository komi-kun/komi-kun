package eu.kanade.presentation.category

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Tri-state category list shared by the History and Updates filter dialogs.
 *
 * A category is [TriState.ENABLED_IS] while it is in [includedCategories], [TriState.ENABLED_NOT]
 * while it is in [excludedCategories], and [TriState.DISABLED] otherwise. Tapping a row advances it
 * through that cycle; the persisted state is owned by [onCycleCategory].
 */
@Composable
fun ColumnScope.CategoryFilterSheetContent(
    categories: List<Category>,
    includedCategories: List<Long>,
    excludedCategories: List<Long>,
    onCycleCategory: (Category) -> Unit,
    description: String,
) {
    Text(
        description,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
    )

    HorizontalDivider(modifier = Modifier.padding(MaterialTheme.padding.extraSmall))

    if (categories.isEmpty()) {
        // since it includes the system category, this should only happen when loading is required
        LoadingScreen(modifier = Modifier.padding(16.dp))
        return
    }

    // Key the remembered tri-state list on the live category list so it is rebuilt (and stays the same
    // size/order) when categories change while the dialog is open. Upstream origin: mihon 1d8a2b05dd
    // ("Add category filters for Updates tab", #3589) used a keyless remember, which could throw
    // IndexOutOfBoundsException / misalign the tri-state when allCategories changed.
    val selected = remember(categories) {
        categories.map { category ->
            when (category.id) {
                in includedCategories -> TriState.ENABLED_IS
                in excludedCategories -> TriState.ENABLED_NOT
                else -> TriState.DISABLED
            }
        }.toMutableStateList()
    }

    Column {
        categories.fastForEachIndexed { idx, category ->
            val state = selected[idx]
            TriStateItem(
                label = category.visualName,
                state = state,
                onClick = {
                    selected[idx] = state.next()
                    onCycleCategory(category)
                },
            )
        }
    }
}
