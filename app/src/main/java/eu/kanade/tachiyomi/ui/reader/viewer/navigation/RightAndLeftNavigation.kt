package eu.kanade.tachiyomi.ui.reader.viewer.navigation

import androidx.compose.ui.geometry.Rect
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation

/**
 * Visualization of default state without any inversion
 * +---+---+---+
 * | N | M | P |   P: Move Right
 * +---+---+---+
 * | N | M | P |   M: Menu
 * +---+---+---+
 * | N | M | P |   N: Move Left
 * +---+---+---+
 */
class RightAndLeftNavigation(
    invertMode: TappingInvertMode,
    isReversed: Boolean,
    // KUN -->
    // KMK: the tap-zone size (R31) travels with the navigator.
    smallerTapZone: Boolean = false,
    // KUN <--
) : ViewerNavigation(invertMode, isReversed, smallerTapZone) {

    override val regionList: List<Region> = listOf(
        Region(
            rect = Rect(0f, 0f, regionSize1, 1f),
            type = NavigationRegion.LEFT,
        ),
        Region(
            rect = Rect(regionSize2, 0f, 1f, 1f),
            type = NavigationRegion.RIGHT,
        ),
    )
}
