// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The one shared content for the combined dictionary/OCR opt-in, used by *both* surfaces:
// `DictionaryOcrStep` (inside the existing onboarding flow) and `DictionaryOcrSetupScreen` (the
// one-time screen shown on the first launch after a pre-feature upgrade). Same copy, same switch —
// the only difference is the chrome that surrounds it.
//
// Both call sites render this inside `InfoScreen`, whose content slot is *already* a
// `Column(Modifier.verticalScroll(...))`. Because that parent measures this content with an
// infinite maximum height, this body must never add a scroll container of its own: a nested
// `verticalScroll` throws `IllegalStateException: Vertically scrollable component was measured with
// an infinity maximum height constraints` at measure time, i.e. it crashes the app on the very
// launch the prompt is meant for. Scrolling is the parent's job — keep it there.
// KUN <--
package eu.kanade.presentation.more.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.kun.KUNMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Shared body of the dictionary/OCR opt-in. [enabled] is the switch state and [onEnabledChange] is
 * the only way to change it: the caller decides whether the switch *is* the answer (onboarding) or
 * whether an explicit button commits it (upgrade screen).
 */
@Composable
internal fun DictionaryOcrSetupContent(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        // No `verticalScroll` here on purpose: `InfoScreen` already scrolls its content slot, and a
        // nested scrollable is measured with an infinite maximum height and throws. See the file
        // header.
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        Text(stringResource(KUNMR.strings.dictionary_ocr_setup_description))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(KUNMR.strings.dictionary_ocr_setup_enable),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(KUNMR.strings.dictionary_ocr_setup_enable_summary),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = stringResource(KUNMR.strings.dictionary_ocr_setup_body),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = stringResource(KUNMR.strings.dictionary_ocr_setup_privacy),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
