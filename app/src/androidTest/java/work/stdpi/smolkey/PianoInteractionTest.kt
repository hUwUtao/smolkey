package work.stdpi.smolkey

import android.content.pm.ActivityInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertCountEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PianoInteractionTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun fullLaunchpadRestoresAllPadsAfterUsingAHalf() {
        compose.onNodeWithContentDescription("Instrument mode:", substring = true).performClick()
        compose.onNodeWithText("Launchpad").performClick()
        try {
            compose.onNodeWithContentDescription("Launchpad placement").performClick()
            compose.onNodeWithText("Use full grid on this phone").performClick()
            val leading = if (compose.activity.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            ) "Top 2×4" else "Left 4×2"
            compose.onNodeWithText(leading).performClick()
            compose.onNodeWithText("Use full grid on this phone").performClick()
            compose.onNodeWithText("Done").performScrollTo().performClick()
            compose.onAllNodesWithContentDescription("Pad row", substring = true).assertCountEquals(16)
        } finally {
            compose.onNodeWithContentDescription("Instrument mode:", substring = true).performClick()
            compose.onNodeWithText("Piano").performClick()
        }
    }

    @Test
    fun pianoTracksKeyPositionsThroughRotationAndKeepsFourFingersHeld() {
        val keyboard = compose.onNodeWithContentDescription("Piano keyboard from", substring = true)
        val initialOrientation = compose.activity.requestedOrientation
        try {
            for (orientation in listOf(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            )) {
                compose.runOnUiThread { compose.activity.requestedOrientation = orientation }
                val portrait = orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
                    orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                compose.waitUntil(5000) {
                    val size = keyboard.fetchSemanticsNode().size
                    (size.height > size.width) == portrait
                }
                val key = compose.onAllNodesWithContentDescription(" key", substring = true)
                    .fetchSemanticsNodes().first()
                val note = key.config[SemanticsProperties.ContentDescription].first().removeSuffix(" key")
                val position = key.boundsInRoot.center - keyboard.fetchSemanticsNode().positionInRoot
                keyboard.performTouchInput { down(position) }
                keyboard.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, note))
                keyboard.performTouchInput { up() }
            }
            keyboard.performTouchInput {
                listOf(.15f, .35f, .55f, .75f).forEachIndexed { index, x ->
                    down(index, Offset(width * x, height * .8f))
                }
            }
            Thread.sleep(900)
            val held = keyboard.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            org.junit.Assert.assertEquals(4, held.split(", ").size)
            keyboard.performTouchInput { repeat(4) { up(it) } }
            keyboard.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "No keys pressed"))
        } finally {
            compose.runOnUiThread { compose.activity.requestedOrientation = initialOrientation }
        }
    }

    @Test
    fun pinchChangesVisibleKeyboardRange() {
        val keyboard = compose.onNodeWithContentDescription("Piano keyboard from", substring = true)
        val before = keyboard.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()
        compose.onNodeWithContentDescription("Keyboard resize gesture area").performTouchInput {
            down(0, percentOffset(0.35f, 0.5f))
            down(1, percentOffset(0.65f, 0.5f))
            moveTo(0, percentOffset(0.25f, 0.5f), 40)
            moveTo(1, percentOffset(0.75f, 0.5f), 40)
            up(0)
            up(1)
        }
        compose.waitForIdle()
        val after = keyboard.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()
        org.junit.Assert.assertNotEquals("Pinch should change the visible note range", before, after)
    }

    @Test
    fun repeatedChordsAndGlissandiReleaseEveryKeyAndKeepControlsResponsive() {
        val node = compose.onNodeWithContentDescription("Piano keyboard from", substring = true)

        repeat(40) {
            node.performTouchInput {
                val start = percentOffset(0.2f, 0.8f)
                val next = percentOffset(0.38f, 0.8f)
                down(0, start)
                down(1, next)
                moveTo(0, percentOffset(0.31f, 0.8f), 12)
                moveTo(1, percentOffset(0.49f, 0.8f), 12)
                up(0)
                up(1)
            }
        }

        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "No keys pressed"))
        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("All notes off").performClick()

    }
}
