package com.wzx.huitai.desktop.ui.shell

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
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
    fun `wide top navigation shows brand routes every canonical destination and exposes selection`() {
        val selected = mutableStateOf(BusinessDesktopDestination.DATA_ENTRY)
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessTopNavigation(
                    selectedDestination = selected.value,
                    onDestinationSelected = { selected.value = it },
                    modifier = Modifier.requiredWidth(1100.dp),
                )
            }
        }

        rule.onNodeWithTag(BusinessTopNavigationTags.ROOT).assertExists()
        rule.onNodeWithTag(BusinessTopNavigationTags.LOGO)
            .assertContentDescriptionEquals("翔鸟律智 Logo")
        rule.onNodeWithText("翔鸟律智桌面端").assertExists()
        assertSelected(BusinessTopNavigationTags.DATA_ENTRY, true)
        assertSelected(BusinessTopNavigationTags.WORKBENCH, false)

        listOf(
            BusinessTopNavigationTags.WORKBENCH to BusinessDesktopDestination.WORKBENCH,
            BusinessTopNavigationTags.RUN_HISTORY to BusinessDesktopDestination.RUN_HISTORY,
            BusinessTopNavigationTags.SETTINGS to BusinessDesktopDestination.SETTINGS,
            BusinessTopNavigationTags.DATA_ENTRY to BusinessDesktopDestination.DATA_ENTRY,
        ).forEach { (tag, destination) ->
            rule.onNodeWithTag(tag).performClick()
            rule.runOnIdle { assertEquals(destination, selected.value) }
            assertSelected(tag, true)
        }
    }

    @Test
    fun `compact width keeps every top destination inside the bar without overlap`() {
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessTopNavigation(
                    selectedDestination = BusinessDesktopDestination.SETTINGS,
                    modifier = Modifier.requiredWidth(900.dp),
                )
            }
        }

        listOf("工作台", "资料录入", "运行记录", "设置").forEach {
            rule.onNodeWithText(it).assertExists()
        }
        assertSelected(BusinessTopNavigationTags.SETTINGS, true)

        val root = bounds(BusinessTopNavigationTags.ROOT)
        val orderedTags = listOf(
            BusinessTopNavigationTags.BRAND,
            BusinessTopNavigationTags.WORKBENCH,
            BusinessTopNavigationTags.DATA_ENTRY,
            BusinessTopNavigationTags.RUN_HISTORY,
            BusinessTopNavigationTags.SETTINGS,
        )
        val orderedBounds = orderedTags.map(::bounds)
        orderedBounds.forEach { child ->
            assertTrue(child.width > 0f && child.height > 0f, "navigation item must remain measurable")
            assertTrue(
                child.left >= root.left && child.right <= root.right &&
                    child.top >= root.top && child.bottom <= root.bottom,
                "navigation item $child must stay inside root $root",
            )
        }
        orderedBounds.zipWithNext().forEach { (left, right) ->
            assertTrue(left.right <= right.left, "top navigation items must not overlap: $left and $right")
        }

        rule.onNodeWithTag(BusinessTopNavigationTags.WORKBENCH).performClick()
        rule.onNodeWithTag(BusinessTopNavigationTags.DATA_ENTRY).performClick()
        rule.onNodeWithTag(BusinessTopNavigationTags.RUN_HISTORY).performClick()
        rule.onNodeWithTag(BusinessTopNavigationTags.SETTINGS).performClick()
        rule.onNodeWithTag("business-sidebar").assertDoesNotExist()
        rule.onNodeWithText("Agent").assertDoesNotExist()
        rule.onNodeWithText("助手").assertDoesNotExist()
    }

    private fun assertSelected(tag: String, expected: Boolean) {
        val actual = rule.onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Selected]
        if (expected) assertTrue(actual, "$tag should be selected") else assertFalse(actual, "$tag should not be selected")
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
}
