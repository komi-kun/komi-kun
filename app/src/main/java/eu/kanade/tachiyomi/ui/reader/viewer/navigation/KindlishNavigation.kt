package eu.kanade.tachiyomi.ui.reader.viewer.navigation

import androidx.compose.ui.geometry.Rect
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation

/**
 * Visualization of default state without any inversion
 * +---+---+---+
 * | M | M | M |   P: Previous
 * +---+---+---+
 * | P | N | N |   M: Menu
 * +---+---+---+
 * | P | N | N |   N: Next
 * +---+---+---+
*/
class KindlishNavigation(
    invertMode: TappingInvertMode,
    isReversed: Boolean,
    // KUN -->
    // KMK: the tap-zone size (R31) travels with the navigator.
    smallerTapZone: Boolean = false,
    // KUN <--
) : ViewerNavigation(invertMode, isReversed, smallerTapZone) {

    override val regionList: List<Region> = listOf(
        Region(
            rect = Rect(regionSize1, regionSize1, 1f, 1f),
            type = NavigationRegion.NEXT,
        ),
        Region(
            rect = Rect(0f, regionSize1, regionSize1, 1f),
            type = NavigationRegion.PREV,
        ),
    )
}
