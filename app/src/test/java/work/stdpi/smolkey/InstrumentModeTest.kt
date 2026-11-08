package work.stdpi.smolkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class InstrumentModeTest {
    @Test fun autoUprightCancelsDisplayRotationAndTracksLockedDevice() {
        assertEquals(0, uprightGridTurns(0, 0))
        assertEquals(0, uprightGridTurns(1, 3))
        assertEquals(0, uprightGridTurns(2, 2))
        assertEquals(0, uprightGridTurns(3, 1))
        assertEquals(1, uprightGridTurns(1, 0))
        assertEquals(3, uprightGridTurns(0, 3))
    }

    @Test fun tiledCoordinatesAndOverridesStayInSharedGrid() {
        val layout = LaunchpadLayout(sharedColumns = 8, tileColumn = 4, overrides = mapOf(4 to 100))
        assertEquals(100, layout.noteAt(0, 0))
        assertEquals(41, layout.noteAt(0, 1))
        assertFalse(layout.copy(tileColumn = 5).isValid())
    }

    @Test fun uprightMappingRotatesSharedCoordinatesButKeepsPadLayoutFixed() {
        val layout = LaunchpadLayout(tileRows = 4, tileColumns = 2)

        val topLeft = mappedTileCoordinate(layout, 0, 0, 1)
        val bottomRight = mappedTileCoordinate(layout, 3, 1, 1)
        assertEquals(GridCoordinate(0, 3, 4, 4), topLeft)
        assertEquals(GridCoordinate(1, 0, 4, 4), bottomRight)
        assertEquals(39, layout.noteAt(topLeft))
        assertEquals(40, layout.noteAt(bottomRight))

        val flippedTop = mappedTileCoordinate(layout, 0, 0, 0, flipRows = true)
        val flippedBottom = mappedTileCoordinate(layout, 3, 0, 0, flipRows = true)
        assertEquals(GridCoordinate(3, 0, 4, 4), flippedTop)
        assertEquals(GridCoordinate(0, 0, 4, 4), flippedBottom)
    }

    @Test fun launchpadDrumPatternUsesGlobalGridPosition() {
        assertEquals(PercussionSound.KICK, launchpadDrumSound(GridCoordinate(0, 0, 4, 4)))
        assertEquals(PercussionSound.SNARE, launchpadDrumSound(GridCoordinate(0, 1, 4, 4)))
        assertEquals(PercussionSound.CLOSED_HI_HAT, launchpadDrumSound(GridCoordinate(1, 0, 4, 4)))
        assertEquals(PercussionSound.LOW_TOM, launchpadDrumSound(GridCoordinate(1, 1, 4, 4)))
    }

    @Test fun unsupportedClickPrimitiveRoutesToWorkingRumble() {
        assertEquals(HapticRoute.CLICK_PRIMITIVE, chooseHapticRoute(HapticStyle.CLICK, true, true, true))
        assertEquals(HapticRoute.PREDEFINED_CLICK, chooseHapticRoute(HapticStyle.CLICK, true, false, true))
        assertEquals(HapticRoute.RUMBLE, chooseHapticRoute(HapticStyle.CLICK, true, false, false))
        assertEquals(HapticRoute.RUMBLE, chooseHapticRoute(HapticStyle.RUMBLE, true, true, true))
        assertEquals(HapticRoute.VIEW, chooseHapticRoute(HapticStyle.CLICK, false, true, true))
    }

    @Test fun strokeDetectorRejectsQuietAndGatesRepeatedEvents() {
        val detector = TambourineStrokeDetector()
        assertNull(detector.sample(1_000_000_000, floatArrayOf(0f, 0f, 0f)))
        assertNotNull(detector.sample(1_020_000_000, floatArrayOf(5f, 0f, 0f)))
        assertNull(detector.sample(1_030_000_000, floatArrayOf(5f, 0f, 0f)))
        assertNull(detector.sample(1_050_000_000, floatArrayOf(0f, 0f, 0f)))
        assertNotNull(detector.sample(1_070_000_000, floatArrayOf(-5f, 0f, 0f)))
    }
}
