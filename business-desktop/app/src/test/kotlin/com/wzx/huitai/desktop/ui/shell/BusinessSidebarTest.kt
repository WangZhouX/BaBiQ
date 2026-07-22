package com.wzx.huitai.desktop.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class BusinessSidebarTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `sidebar fills the parent 210dp slot and exposes four destinations`() {
        rule.setContent {
            HuitaiBusinessTheme {
                Box(Modifier.requiredSize(width = 210.dp, height = 600.dp)) {
                    BusinessSidebar()
                }
            }
        }

        rule.onNodeWithTag(BusinessSidebarTags.ROOT).assertWidthIsEqualTo(210.dp)
        rule.onNodeWithText("业务导航").assertExists()
        rule.onNodeWithTag(BusinessSidebarTags.WORKBENCH).assertExists()
        rule.onNodeWithTag(BusinessSidebarTags.DATA_ENTRY).assertExists()
        rule.onNodeWithTag(BusinessSidebarTags.RUN_HISTORY).assertExists()
        rule.onNodeWithTag(BusinessSidebarTags.SETTINGS).assertExists()
        rule.onNodeWithTag("navigation-agent").assertDoesNotExist()
        rule.onAllNodes(hasClickAction()).assertCountEquals(4)
        assertEquals(
            listOf(
                BusinessDesktopDestination.WORKBENCH,
                BusinessDesktopDestination.DATA_ENTRY,
                BusinessDesktopDestination.RUN_HISTORY,
            ),
            businessSidebarDestinations,
        )
    }

    @Test
    fun `settings stays at the bottom while business destinations stay at the top`() {
        val parentHeight = mutableStateOf(600.dp)
        val density = Density(0.5f)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                HuitaiBusinessTheme {
                    Box(Modifier.requiredSize(width = 210.dp, height = parentHeight.value)) {
                        BusinessSidebar()
                    }
                }
            }
        }

        val rootAt600 = bounds(BusinessSidebarTags.ROOT)
        val settingsAt600 = bounds(BusinessSidebarTags.SETTINGS)
        val workbenchAt600 = bounds(BusinessSidebarTags.WORKBENCH)
        val dataEntryAt600 = bounds(BusinessSidebarTags.DATA_ENTRY)
        val runHistoryAt600 = bounds(BusinessSidebarTags.RUN_HISTORY)
        assertTrue(settingsAt600.top > runHistoryAt600.bottom, "settings must be below run history")
        assertEquals(
            with(density) { 18.dp.toPx() },
            rootAt600.bottom - settingsAt600.bottom,
            absoluteTolerance = with(density) { 0.5.dp.toPx() },
            message = "settings must keep the sidebar's 18dp bottom padding",
        )

        rule.runOnIdle { parentHeight.value = 800.dp }

        val rootAt800 = bounds(BusinessSidebarTags.ROOT)
        val settingsAt800 = bounds(BusinessSidebarTags.SETTINGS)
        assertEquals(
            with(density) { 200.dp.toPx() },
            rootAt800.bottom - rootAt600.bottom,
            absoluteTolerance = with(density) { 0.5.dp.toPx() },
        )
        assertEquals(
            with(density) { 200.dp.toPx() },
            settingsAt800.bottom - settingsAt600.bottom,
            absoluteTolerance = with(density) { 0.5.dp.toPx() },
            message = "settings must follow the parent bottom via a flexible spacer",
        )
        assertEquals(workbenchAt600, bounds(BusinessSidebarTags.WORKBENCH))
        assertEquals(dataEntryAt600, bounds(BusinessSidebarTags.DATA_ENTRY))
        assertEquals(runHistoryAt600, bounds(BusinessSidebarTags.RUN_HISTORY))
    }

    @Test
    fun `settings is the only selected tab when it is selected`() {
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessSidebar(selected = BusinessDesktopDestination.SETTINGS)
            }
        }

        assertTabSemantics(BusinessSidebarTags.WORKBENCH, expectedSelected = false)
        assertTabSemantics(BusinessSidebarTags.DATA_ENTRY, expectedSelected = false)
        assertTabSemantics(BusinessSidebarTags.RUN_HISTORY, expectedSelected = false)
        assertTabSemantics(BusinessSidebarTags.SETTINGS, expectedSelected = true)
    }

    @Test
    fun `each sidebar entry returns its canonical destination`() {
        val selectedDestinations = mutableListOf<BusinessDesktopDestination>()
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessSidebar(onSelected = selectedDestinations::add)
            }
        }

        rule.onNodeWithTag(BusinessSidebarTags.WORKBENCH).performClick()
        rule.onNodeWithTag(BusinessSidebarTags.DATA_ENTRY).performClick()
        rule.onNodeWithTag(BusinessSidebarTags.RUN_HISTORY).performClick()
        rule.onNodeWithTag(BusinessSidebarTags.SETTINGS).performClick()

        rule.runOnIdle {
            assertEquals(
                listOf(
                    BusinessDesktopDestination.WORKBENCH,
                    BusinessDesktopDestination.DATA_ENTRY,
                    BusinessDesktopDestination.RUN_HISTORY,
                    BusinessDesktopDestination.SETTINGS,
                ),
                selectedDestinations,
            )
        }
    }

    @Test
    fun `sidebar reports its real committed composition`() {
        var composed = false
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessSidebar(onComposed = { composed = true })
            }
        }

        rule.runOnIdle { assertTrue(composed) }
    }

    private fun assertTabSemantics(tag: String, expectedSelected: Boolean) {
        val config = rule.onNodeWithTag(tag).fetchSemanticsNode().config
        assertEquals(Role.Tab, config[SemanticsProperties.Role], "$tag must expose tab role")
        assertTrue(config.contains(SemanticsActions.OnClick), "$tag must expose click action")
        val actualSelected = config[SemanticsProperties.Selected]
        if (expectedSelected) {
            assertTrue(actualSelected, "$tag should be selected")
        } else {
            assertFalse(actualSelected, "$tag should not be selected")
        }
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
}
