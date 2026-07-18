package com.wzx.huitai.desktop.ui.shell

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.desktop.state.BusinessDesktopState
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import com.wzx.huitai.desktop.ui.layout.CompactContentTab
import org.junit.Rule
import org.junit.Test

class BusinessDesktopShellTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `wide shell renders only generic navigation and separate form and agent columns`() {
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = BusinessDesktopState(),
                    formState = DemoFormState(),
                    modifier = Modifier.widthForTest(1280.dp),
                )
            }
        }

        listOf("工作台", "资料录入", "运行记录", "设置").forEach {
            rule.onNodeWithText(it).assertExists()
        }
        listOf("客户", "案件", "文书", "审批").forEach {
            rule.onAllNodesWithText(it).assertCountEquals(0)
        }
        rule.onNodeWithTag(BusinessUiTags.SIDEBAR).assertExists()
        rule.onNodeWithTag(BusinessUiTags.FORM_PANEL).assertExists()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertExists()
    }

    @Test
    fun `compact shell switches between exactly one form or agent content tab`() {
        rule.setContent {
            var tab by remember { mutableStateOf(CompactContentTab.FORM) }
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = BusinessDesktopState(),
                    formState = DemoFormState(),
                    compactContentTab = tab,
                    onCompactContentTabSelected = { tab = it },
                    modifier = Modifier.widthForTest(900.dp),
                )
            }
        }

        rule.onNodeWithTag(BusinessUiTags.FORM_PANEL).assertExists()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertDoesNotExist()
        rule.onNodeWithText("Agent").performClick()
        rule.onNodeWithTag(BusinessUiTags.FORM_PANEL).assertDoesNotExist()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertExists()
    }

    @Test
    fun `medium shell keeps 72 dp accessible navigation beside form and agent`() {
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = BusinessDesktopState(),
                    formState = DemoFormState(),
                    modifier = Modifier.widthForTest(1024.dp),
                )
            }
        }

        rule.onNodeWithTag(BusinessUiTags.SIDEBAR).assertWidthIsEqualTo(72.dp)
        mapOf(
            "workbench" to "工作台",
            "data_entry" to "资料录入",
            "run_history" to "运行记录",
            "settings" to "设置",
        ).forEach { (tagSuffix, label) ->
            rule.onNodeWithTag("navigation-$tagSuffix")
                .assertExists()
                .assertContentDescriptionEquals(label)
        }
        rule.onNodeWithTag(BusinessUiTags.FORM_PANEL).assertExists()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertExists()
    }
}

private fun Modifier.widthForTest(width: androidx.compose.ui.unit.Dp): Modifier =
    then(Modifier.requiredWidth(width))
