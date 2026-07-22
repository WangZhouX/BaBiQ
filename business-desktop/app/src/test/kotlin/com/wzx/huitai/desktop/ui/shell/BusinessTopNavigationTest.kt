package com.wzx.huitai.desktop.ui.shell

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class BusinessTopNavigationTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `top toolbar reports its real committed composition`() {
        var composed = false
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessTopNavigation(
                    selectedDestination = BusinessDesktopDestination.DATA_ENTRY,
                    onDestinationSelected = {},
                    onComposed = { composed = true },
                )
            }
        }

        rule.runOnIdle { assertTrue(composed) }
    }

    @Test
    fun `top toolbar is 52dp and only exposes settings at the right edge`() {
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessTopNavigation(
                    selectedDestination = BusinessDesktopDestination.DATA_ENTRY,
                    onDestinationSelected = {},
                    modifier = Modifier.requiredWidth(900.dp),
                )
            }
        }

        rule.onNodeWithTag(BusinessTopNavigationTags.ROOT).assertHeightIsEqualTo(52.dp)
        rule.onNodeWithTag(BusinessTopNavigationTags.SETTINGS).assertExists()
        rule.onNodeWithTag(BusinessTopNavigationTags.BRAND).assertDoesNotExist()
        rule.onNodeWithTag(BusinessTopNavigationTags.LOGO).assertDoesNotExist()
        rule.onNodeWithTag(BusinessTopNavigationTags.WORKBENCH).assertDoesNotExist()
        rule.onNodeWithTag(BusinessTopNavigationTags.DATA_ENTRY).assertDoesNotExist()
        rule.onNodeWithTag(BusinessTopNavigationTags.RUN_HISTORY).assertDoesNotExist()

        val root = bounds(BusinessTopNavigationTags.ROOT)
        val settings = bounds(BusinessTopNavigationTags.SETTINGS)
        assertTrue(settings.center.x > root.center.x, "settings must be in the right half: $settings in $root")
        assertTrue(settings.right <= root.right, "settings must stay inside the toolbar: $settings in $root")
        assertTrue(root.right - settings.right <= 24f, "settings must stay near the right edge: $settings in $root")
    }

    @Test
    fun `settings keeps tab semantics and routes through the existing callback`() {
        val selected = mutableStateOf(BusinessDesktopDestination.DATA_ENTRY)
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessTopNavigation(
                    selectedDestination = selected.value,
                    onDestinationSelected = { selected.value = it },
                    modifier = Modifier.requiredWidth(900.dp),
                )
            }
        }

        assertTabSemantics(BusinessTopNavigationTags.SETTINGS, expectedSelected = false)
        rule.onNodeWithTag(BusinessTopNavigationTags.SETTINGS).performClick()
        rule.runOnIdle { assertEquals(BusinessDesktopDestination.SETTINGS, selected.value) }
        assertTabSemantics(BusinessTopNavigationTags.SETTINGS, expectedSelected = true)
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
