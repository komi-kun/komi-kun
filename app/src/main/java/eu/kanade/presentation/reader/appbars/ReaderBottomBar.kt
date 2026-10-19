package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import mihon.icons.custommaterialsymbols.CustomMaterialSymbols
import mihon.icons.custommaterialsymbols.rounded.CropOff
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Crop
import mihon.icons.materialsymbols.rounded.EditNote
import mihon.icons.materialsymbols.rounded.Explore
import mihon.icons.materialsymbols.rounded.FormatListNumbered
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.Share
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderBottomBar(
    // SY -->
    enabledButtons: Set<String>,
    // SY <--
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    // SY -->
    currentReadingMode: ReadingMode,
    dualPageSplitEnabled: Boolean,
    doublePages: Boolean,
    onClickChapterList: () -> Unit,
    onClickWebView: (() -> Unit)?,
    onClickBrowser: (() -> Unit)?,
    onClickShare: (() -> Unit)?,
    onClickPageLayout: () -> Unit,
    onClickShiftPage: () -> Unit,
    // SY <--
    // KUN -->
    ocrEnabled: Boolean,
    onClickOcr: () -> Unit,
    onLongClickOcr: (() -> Unit)? = null,
    // KUN <--
    modifier: Modifier = Modifier,
) {
    // KMK -->
    val iconColor = MaterialTheme.colorScheme.primary
    // KMK <--
    Row(
        modifier = modifier
            .pointerInput(Unit) {},
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // SY -->
        if (ReaderBottomButton.ViewChapters.isIn(enabledButtons)) {
            IconButton(onClick = onClickChapterList) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.FormatListNumbered,
                    contentDescription = stringResource(MR.strings.chapters),
                    // KMK -->
                    tint = iconColor,
                    // KMK <--
                )
            }
        }

        if (ReaderBottomButton.WebView.isIn(enabledButtons) && onClickWebView != null) {
            IconButton(onClick = onClickWebView) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Public,
                    contentDescription = stringResource(MR.strings.action_open_in_web_view),
                    // KMK -->
                    tint = iconColor,
                    // KMK <--
                )
            }
        }

        if (ReaderBottomButton.Browser.isIn(enabledButtons) && onClickBrowser != null) {
            IconButton(onClick = onClickBrowser) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Explore,
                    contentDescription = stringResource(MR.strings.action_open_in_browser),
                    // KMK -->
                    tint = iconColor,
                    // KMK <--
                )
            }
        }

        if (ReaderBottomButton.Share.isIn(enabledButtons) && onClickShare != null) {
            IconButton(onClick = onClickShare) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Share,
                    contentDescription = stringResource(MR.strings.action_share),
                    // KMK -->
                    tint = iconColor,
                    // KMK <--
                )
            }
        }

        if (ReaderBottomButton.ReadingMode.isIn(enabledButtons)) {
            IconButton(onClick = onClickReadingMode) {
                Icon(
                    imageVector = readingMode.icon,
                    contentDescription = stringResource(MR.strings.viewer),
                    // KMK -->
                    tint = iconColor,
                    // KMK <--
                )
            }
        }

        if (ReaderBottomButton.Rotation.isIn(enabledButtons)) {
            IconButton(onClick = onClickOrientation) {
                Icon(
                    imageVector = orientation.icon,
                    contentDescription = stringResource(MR.strings.pref_rotation_type),
                    // KMK -->
                    tint = iconColor,
                    // KMK <--
                )
            }
        }

        val cropBorders = when (currentReadingMode) {
            ReadingMode.WEBTOON -> ReaderBottomButton.CropBordersWebtoon
            ReadingMode.CONTINUOUS_VERTICAL -> ReaderBottomButton.CropBordersContinuesVertical
            else -> ReaderBottomButton.CropBordersPager
        }
        if (cropBorders.isIn(enabledButtons)) {
            IconButton(onClick = onClickCropBorder) {
                Icon(
                    imageVector = if (cropEnabled) MaterialSymbols.Rounded.Crop else CustomMaterialSymbols.Rounded.CropOff,
                    contentDescription = stringResource(MR.strings.pref_crop_borders),
                    // KMK -->
                    tint = iconColor,
                    // KMK <--
                )
            }
        }

        // KUN -->
        if (ReaderBottomButton.Ocr.isIn(enabledButtons)) {
            val haptic = LocalHapticFeedback.current
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        interactionSource = null,
                        indication = ripple(bounded = false),
                        onClick = onClickOcr,
                        onLongClick = onLongClickOcr?.let { action ->
                            {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                action()
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.EditNote,
                    contentDescription = stringResource(KMR.strings.pref_category_ocr),
                    tint = if (ocrEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // KUN <--

        if (
            !dualPageSplitEnabled &&
            ReaderBottomButton.PageLayout.isIn(enabledButtons) &&
            ReadingMode.isPagerType(currentReadingMode.flagValue)
        ) {
            IconButton(onClick = onClickPageLayout) {
                Icon(
                    painter = painterResource(R.drawable.ic_book_open_variant_24dp),
                    contentDescription = stringResource(SYMR.strings.page_layout),
                    // KMK -->
                    tint = iconColor,
                    // KMK <--
                )
            }
        }

        if (doublePages) {
            IconButton(onClick = onClickShiftPage) {
                Icon(
                    painter = painterResource(R.drawable.ic_page_next_outline_24dp),
                    contentDescription = stringResource(SYMR.strings.shift_double_pages),
                    // KMK -->
                    tint = iconColor,
                    // KMK <--
                )
            }
        }

        IconButton(onClick = onClickSettings) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Settings,
                contentDescription = stringResource(MR.strings.action_settings),
                // KMK -->
                tint = iconColor,
                // KMK <--
            )
        }
        // SY <--
    }
}
