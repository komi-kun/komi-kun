package eu.kanade.tachiyomi.ui.reader.viewer.navigation

import androidx.compose.ui.geometry.Rect
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation

/**
 * Visualization of default state without any inversion
 * +---+---+---+
 * | P | P | P |   P: Previous
 * +---+---+---+
 * | P | M | N |   M: Menu
 * +---+---+---+
 * | N | N | N |   N: Next
 * +---+---+---+
 */
open class LNavigation(
    invertMode: TappingInvertMode,
    isReversed: Boolean,
    // KUN -->
    // KMK: the tap-zone size (R31) travels with the navigator.
    smallerTapZone: Boolean = false,
    // KUN <--
) : ViewerNavigation(invertMode, isReversed, smallerTapZone) {

    override val regionList: List<Region> = listOf(
        Region(
            rect = Rect(0f, regionSize1, regionSize1, regionSize2),
            type = NavigationRegion.PREV,
        ),
        Region(
            rect = Rect(0f, 0f, 1f, regionSize1),
            type = NavigationRegion.PREV,
        ),
        Region(
            rect = Rect(regionSize2, regionSize1, 1f, regionSize2),
            type = NavigationRegion.NEXT,
        ),
        Region(
            rect = Rect(0f, regionSize2, 1f, 1f),
            type = NavigationRegion.NEXT,
        ),
    )
}
