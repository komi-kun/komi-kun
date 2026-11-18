// KUN -->
package eu.kanade.tachiyomi.ui.reader.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.Action
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.Region
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The tap-to-action mapping, which is the whole of what a tap zone does, and the rectangle inversion the zones
 * are drawn and hit-tested through.
 *
 * The mapping is direction-sensitive: a right-side tap goes forward in a left-to-right book and back in a
 * right-to-left one, and the left side mirrors it. Inverting the zones moves the rectangles without changing what
 * each zone means. Nothing here is Android: the navigators are values, and the tap zones are plain geometry.
 */
class ViewerNavigationTest {

    private fun navigator(
        isReversed: Boolean,
        invertMode: TappingInvertMode = TappingInvertMode.NONE,
    ) = RightAndLeftNavigation(invertMode, isReversed, smallerTapZone = false)

    @Test
    fun `a right-side tap is backward on a reversed navigator and forward otherwise`() {
        val rightSide = Offset(0.9f, 0.5f)

        assertEquals(Action.FORWARD, navigator(isReversed = false).getAction(rightSide))
        assertEquals(Action.BACKWARD, navigator(isReversed = true).getAction(rightSide))
    }

    @Test
    fun `a left-side tap is the mirror of the right side`() {
        val leftSide = Offset(0.1f, 0.5f)

        assertEquals(Action.BACKWARD, navigator(isReversed = false).getAction(leftSide))
        assertEquals(Action.FORWARD, navigator(isReversed = true).getAction(leftSide))
    }

    @Test
    fun `the middle of the screen is the menu whatever the direction`() {
        val middle = Offset(0.5f, 0.5f)

        assertEquals(Action.MENU, navigator(isReversed = false).getAction(middle))
        assertEquals(Action.MENU, navigator(isReversed = true).getAction(middle))
    }

    @Test
    fun `inverting the right and left zones moves them across the screen without changing what they do`() {
        val leftSide = Offset(0.1f, 0.5f)
        val rightSide = Offset(0.9f, 0.5f)

        // Horizontally inverted, the right-hand zone is drawn (and hit-tested) on the left half...
        assertEquals(Action.FORWARD, navigator(isReversed = false, TappingInvertMode.HORIZONTAL).getAction(leftSide))
        assertEquals(Action.BACKWARD, navigator(isReversed = false, TappingInvertMode.HORIZONTAL).getAction(rightSide))
        // ...with the direction still applied to the zone, not to the side: reversed, the same moved zone goes back.
        assertEquals(Action.BACKWARD, navigator(isReversed = true, TappingInvertMode.HORIZONTAL).getAction(leftSide))
        assertEquals(Action.FORWARD, navigator(isReversed = true, TappingInvertMode.HORIZONTAL).getAction(rightSide))

        // Inverting the other axis leaves these full-height zones where they are.
        assertEquals(Action.BACKWARD, navigator(isReversed = false, TappingInvertMode.VERTICAL).getAction(leftSide))
        assertEquals(Action.FORWARD, navigator(isReversed = false, TappingInvertMode.VERTICAL).getAction(rightSide))
    }

    /**
     * The rectangle halves are exact in binary, so the inversion is asserted by value rather than by a probe
     * point: this is the replacement for the `RectFExtensions.invert` the activity's native overlay used, and it
     * must mirror each axis by itself (`1 - right` .. `1 - left`), never swap the zone's edges in place.
     */
    @Test
    fun `a zone's rectangle is mirrored on the axes the inversion asks for`() {
        val region = Region(Rect(left = 0.125f, top = 0.5f, right = 0.375f, bottom = 0.75f), NavigationRegion.RIGHT)

        assertEquals(
            Rect(0.625f, 0.5f, 0.875f, 0.75f),
            region.invert(TappingInvertMode.HORIZONTAL).rect,
        )
        assertEquals(
            Rect(0.125f, 0.25f, 0.375f, 0.5f),
            region.invert(TappingInvertMode.VERTICAL).rect,
        )
        assertEquals(
            Rect(0.625f, 0.25f, 0.875f, 0.5f),
            region.invert(TappingInvertMode.BOTH).rect,
        )
        // Neither axis: the rectangle is left alone, and the zone keeps its type either way.
        assertEquals(region.rect, region.invert(TappingInvertMode.NONE).rect)
        assertEquals(NavigationRegion.RIGHT, region.invert(TappingInvertMode.BOTH).type)
    }
}
// KUN <--
