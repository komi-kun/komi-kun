package eu.kanade.tachiyomi.ui.reader.viewer.navigation

import androidx.compose.ui.geometry.Rect
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation

/**
 * Visualization of default state without any inversion
 * +---+---+---+
 * | N | N | N |   P: Previous
 * +---+---+---+
 * | N | M | N |   M: Menu
 * +---+---+---+
 * | N | P | N |   N: Next
 * +---+---+---+
*/
class EdgeNavigation(
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
            type = NavigationRegion.NEXT,
        ),
        Region(
            rect = Rect(regionSize1, regionSize2, regionSize2, 1f),
            type = NavigationRegion.PREV,
        ),
        Region(
            rect = Rect(regionSize2, 0f, 1f, 1f),
            type = NavigationRegion.NEXT,
        ),
    )
}
