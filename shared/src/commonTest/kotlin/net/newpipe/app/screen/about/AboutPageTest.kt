package net.newpipe.app.screen.about

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AboutPageTest {
    @Test
    fun defaultAboutHasNoUpstreamActionDestinations() = runComposeUiTest {
        setContent { AboutPageContent() }
        onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun sharedHeaderUsesTheInstalledAppsBacktubeName() = runComposeUiTest {
        setContent { AboutPageContent() }
        onNodeWithText("backtube").assertIsDisplayed()
    }
}
