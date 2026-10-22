package net.newpipe.app.screen.about

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlin.test.Test
import kotlin.test.assertTrue
import net.newpipe.app.extensions.withKoin
import net.newpipe.app.screen.about.navigation.Page
import newpipe.shared.generated.resources.Res
import newpipe.shared.generated.resources.backtube_about_tab
import newpipe.shared.generated.resources.done
import newpipe.shared.generated.resources.read_full_license
import newpipe.shared.generated.resources.tab_licenses
import org.jetbrains.compose.resources.getString
import org.koin.dsl.module

@OptIn(ExperimentalTestApi::class)
class AboutScreenTest {
    @Test
    fun rendersAboutAndKeepsFullLicenseAccessibleThroughTheTabs() = runComposeUiTest {
        withKoin(
            modules = listOf(module { single<Settings> { MapSettings() } }),
            content = {
                MaterialTheme {
                    AboutScreenContent(
                        onPageContent = { page ->
                            when (page) {
                                Page.ABOUT -> AboutPageContent()
                                Page.LICENSE -> LicensePageContent()
                            }
                        }
                    )
                }
            },
            onContent = {
                onNodeWithText("backtube").assertIsDisplayed()
                val rendered = onRoot().captureToImage().toPixelMap()
                assertTrue(rendered.width > 0 && rendered.height > 0)
                assertTrue(rendered[rendered.width / 2, rendered.height / 2].alpha > 0f)

                onNodeWithText(getString(Res.string.tab_licenses)).performClick()
                onNodeWithText(getString(Res.string.read_full_license)).performClick()
                onNodeWithTag(TEST_TAG_LICENSE_TEXT).assertIsDisplayed()
                onNodeWithText("GNU GENERAL PUBLIC LICENSE", substring = true).assertIsDisplayed()
                onNodeWithText(getString(Res.string.done)).performClick()
                onNodeWithText(getString(Res.string.backtube_about_tab)).performClick()
                onNodeWithText("backtube").assertIsDisplayed()
            }
        )
    }
}
