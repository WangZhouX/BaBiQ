package com.wzx.huitai.desktop.ui.shell

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
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
    fun `top navigation reports its real committed composition`() {
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
        val groupConfig = rule.onNodeWithTag(BusinessTopNavigationTags.GROUP)
            .fetchSemanticsNode()
            .config
        assertTrue(groupConfig.contains(SemanticsProperties.SelectableGroup))
        assertTabSemantics(BusinessTopNavigationTags.DATA_ENTRY, true)
        assertTabSemantics(BusinessTopNavigationTags.WORKBENCH, false)

        listOf(
            BusinessTopNavigationTags.WORKBENCH to BusinessDesktopDestination.WORKBENCH,
            BusinessTopNavigationTags.RUN_HISTORY to BusinessDesktopDestination.RUN_HISTORY,
            BusinessTopNavigationTags.SETTINGS to BusinessDesktopDestination.SETTINGS,
            BusinessTopNavigationTags.DATA_ENTRY to BusinessDesktopDestination.DATA_ENTRY,
        ).forEach { (tag, destination) ->
            rule.onNodeWithTag(tag).performClick()
            rule.runOnIdle { assertEquals(destination, selected.value) }
            assertTabSemantics(tag, true)
        }
    }

    @Test
    fun `compact width keeps every top destination inside the bar without overlap`() {
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessTopNavigation(
                    selectedDestination = BusinessDesktopDestination.SETTINGS,
                    onDestinationSelected = {},
                    modifier = Modifier.requiredWidth(900.dp),
                )
            }
        }

        listOf("工作台", "资料录入", "运行记录", "设置").forEach {
            rule.onNodeWithText(it).assertExists()
        }
        assertTabSemantics(BusinessTopNavigationTags.SETTINGS, true)

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
        val settingsRightInset = root.right - orderedBounds.last().right
        assertTrue(
            settingsRightInset in 0f..16f,
            "settings must remain fixed near the right edge: inset=$settingsRightInset root=$root",
        )

        rule.onNodeWithTag(BusinessTopNavigationTags.WORKBENCH).performClick()
        rule.onNodeWithTag(BusinessTopNavigationTags.DATA_ENTRY).performClick()
        rule.onNodeWithTag(BusinessTopNavigationTags.RUN_HISTORY).performClick()
        rule.onNodeWithTag(BusinessTopNavigationTags.SETTINGS).performClick()
        rule.onNodeWithTag("business-sidebar").assertDoesNotExist()
        rule.onNodeWithText("Agent").assertDoesNotExist()
        rule.onNodeWithText("助手").assertDoesNotExist()
    }

    @Test
    fun `two times font scale keeps brand and navigation labels fully laid out at compact width`() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                HuitaiBusinessTheme {
                    BusinessTopNavigation(
                        selectedDestination = BusinessDesktopDestination.DATA_ENTRY,
                        onDestinationSelected = {},
                        modifier = Modifier.requiredWidth(900.dp),
                    )
                }
            }
        }

        listOf("翔鸟律智桌面端", "工作台", "资料录入", "运行记录", "设置").forEach(::assertTextDoesNotOverflow)

        val root = bounds(BusinessTopNavigationTags.ROOT)
        val orderedBounds = listOf(
            BusinessTopNavigationTags.BRAND,
            BusinessTopNavigationTags.WORKBENCH,
            BusinessTopNavigationTags.DATA_ENTRY,
            BusinessTopNavigationTags.RUN_HISTORY,
            BusinessTopNavigationTags.SETTINGS,
        ).map(::bounds)
        orderedBounds.forEach { child ->
            assertTrue(child.left >= root.left && child.right <= root.right, "$child must remain inside $root")
        }
        orderedBounds.zipWithNext().forEach { (left, right) ->
            assertTrue(left.right <= right.left, "scaled top navigation items must not overlap: $left and $right")
        }
        assertTrue(root.right - orderedBounds.last().right in 0f..16f)
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

    private fun assertTextDoesNotOverflow(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        val action = rule.onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
        assertTrue(action?.invoke(layouts) == true, "$text must expose its text layout result")
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        assertFalse(
            layout.didOverflowWidth,
            "$text must not be clipped horizontally: size=${layout.size}, textWidth=${layout.multiParagraph.width}",
        )
    }
}
