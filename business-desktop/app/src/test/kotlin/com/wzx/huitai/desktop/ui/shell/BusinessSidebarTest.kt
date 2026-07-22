package com.wzx.huitai.desktop.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
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
    fun `sidebar fills the parent 210dp slot and only exposes business destinations`() {
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
        rule.onNodeWithText("设置").assertDoesNotExist()
        rule.onNodeWithTag("navigation-settings").assertDoesNotExist()
        rule.onNodeWithTag("navigation-agent").assertDoesNotExist()
        rule.onAllNodes(hasClickAction()).assertCountEquals(3)
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
    fun `sidebar exposes tab selection only for its selected business destination`() {
        val selected = mutableStateOf(BusinessDesktopDestination.SETTINGS)
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessSidebar(selected = selected.value)
            }
        }

        assertTabSemantics(BusinessSidebarTags.WORKBENCH, expectedSelected = false)
        assertTabSemantics(BusinessSidebarTags.DATA_ENTRY, expectedSelected = false)
        assertTabSemantics(BusinessSidebarTags.RUN_HISTORY, expectedSelected = false)

        rule.runOnIdle { selected.value = BusinessDesktopDestination.RUN_HISTORY }
        assertTabSemantics(BusinessSidebarTags.WORKBENCH, expectedSelected = false)
        assertTabSemantics(BusinessSidebarTags.DATA_ENTRY, expectedSelected = false)
        assertTabSemantics(BusinessSidebarTags.RUN_HISTORY, expectedSelected = true)
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

        rule.runOnIdle {
            assertEquals(
                listOf(
                    BusinessDesktopDestination.WORKBENCH,
                    BusinessDesktopDestination.DATA_ENTRY,
                    BusinessDesktopDestination.RUN_HISTORY,
                ),
                selectedDestinations,
            )
        }
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
}
