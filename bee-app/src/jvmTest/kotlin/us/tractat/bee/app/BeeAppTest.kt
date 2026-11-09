@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package us.tractat.bee.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BeeAppTest {
    @Test
    fun eachTitleIsOneLineOfPlainTextInTheOrderGiven() = runComposeUiTest {
        setContent { SurfaceTitles(listOf("Retro", "Plan")) }
        val titles = onAllNodesWithTag(SURFACE_TITLE_TAG)
        titles.assertCountEquals(2)
        titles[0].assertTextEquals("Retro")
        titles[1].assertTextEquals("Plan")
    }

    @Test
    fun anEmptyBoardShowsOnlyItsQuietHint() = runComposeUiTest {
        setContent { SurfaceTitles(emptyList()) }
        onAllNodesWithTag(SURFACE_TITLE_TAG).assertCountEquals(0)
        onNodeWithText("Nothing on the board yet.").assertExists()
    }

    @Test
    fun aLinkThatNamesNoHostIsRefusedWithItsReasonAndJoinsNothing() = runComposeUiTest {
        var joins = 0
        setContent { BeeApp(AppScreen.AskForBoard()) { joins++; error("a refused link never joins") } }
        val field = onNode(hasSetTextAction())
        field.performTextInput("board.example")
        field.performImeAction()
        onNodeWithText("isn't a bee host", substring = true).assertExists()
        assertEquals(0, joins)
    }
}
