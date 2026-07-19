package com.wzx.huitai.desktop.ui.shell

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.desktop.controller.BusinessProviderSettingsState
import com.wzx.huitai.desktop.state.BusinessDesktopState
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class BusinessDesktopShellTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `wide shell renders generic navigation form and persistent agent by default`() {
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = BusinessDesktopState(),
                    formState = DemoFormState(),
                    providerSettingsState = BusinessProviderSettingsState(),
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
    fun `wide navigation routes center content while keeping agent rail`() {
        val destination = mutableStateOf(BusinessDesktopDestination.DATA_ENTRY)
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = BusinessDesktopState(),
                    formState = DemoFormState(),
                    providerSettingsState = BusinessProviderSettingsState(),
                    selectedDestination = destination.value,
                    onDestinationSelected = { destination.value = it },
                    modifier = Modifier.widthForTest(1024.dp),
                )
            }
        }

        rule.onNodeWithTag("navigation-settings").performClick()
        assertEquals(BusinessDesktopDestination.SETTINGS, destination.value)
        rule.onNodeWithTag("provider-settings-panel").assertExists()
        rule.onNodeWithTag(BusinessUiTags.FORM_PANEL).assertDoesNotExist()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertExists()

        rule.onNodeWithTag("navigation-data_entry").performClick()
        rule.onNodeWithTag(BusinessUiTags.FORM_PANEL).assertExists()
        rule.onNodeWithTag("provider-settings-panel").assertDoesNotExist()

        rule.onNodeWithTag("navigation-workbench").performClick()
        rule.onNodeWithText("工作台功能将在后续阶段开放").assertExists()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertExists()

        rule.onNodeWithTag("navigation-run_history").performClick()
        rule.onNodeWithText("运行记录功能将在后续阶段开放").assertExists()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertExists()
    }

    @Test
    fun `compact shell exposes exactly one of data settings or agent`() {
        val destination = mutableStateOf(BusinessDesktopDestination.DATA_ENTRY)
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = BusinessDesktopState(),
                    formState = DemoFormState(),
                    providerSettingsState = BusinessProviderSettingsState(),
                    selectedDestination = destination.value,
                    onDestinationSelected = { destination.value = it },
                    modifier = Modifier.widthForTest(900.dp),
                )
            }
        }

        listOf("资料录入", "设置", "Agent").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithTag(BusinessUiTags.FORM_PANEL).assertExists()
        rule.onNodeWithTag("provider-settings-panel").assertDoesNotExist()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertDoesNotExist()

        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithTag(BusinessUiTags.FORM_PANEL).assertDoesNotExist()
        rule.onNodeWithTag("provider-settings-panel").assertExists()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertDoesNotExist()

        rule.onNodeWithText("Agent").performClick()
        rule.onNodeWithTag(BusinessUiTags.FORM_PANEL).assertDoesNotExist()
        rule.onNodeWithTag("provider-settings-panel").assertDoesNotExist()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertExists()
    }

    @Test
    fun `settings remains canonical when wide window becomes compact`() {
        val width = mutableStateOf(1280.dp)
        val destination = mutableStateOf(BusinessDesktopDestination.SETTINGS)
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = BusinessDesktopState(),
                    formState = DemoFormState(),
                    providerSettingsState = BusinessProviderSettingsState(),
                    selectedDestination = destination.value,
                    onDestinationSelected = { destination.value = it },
                    modifier = Modifier.widthForTest(width.value),
                )
            }
        }

        rule.onNodeWithTag("provider-settings-panel").assertExists()
        rule.runOnIdle { width.value = 900.dp }
        rule.onNodeWithTag("provider-settings-panel").assertExists()
        assertEquals(BusinessDesktopDestination.SETTINGS, destination.value)
    }

    @Test
    fun `compact agent uses data fallback on wide and returns to agent when compact again`() {
        val width = mutableStateOf(900.dp)
        val destination = mutableStateOf(BusinessDesktopDestination.AGENT)
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = BusinessDesktopState(),
                    formState = DemoFormState(),
                    providerSettingsState = BusinessProviderSettingsState(),
                    selectedDestination = destination.value,
                    onDestinationSelected = { destination.value = it },
                    modifier = Modifier.widthForTest(width.value),
                )
            }
        }

        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertExists()
        rule.onNodeWithTag(BusinessUiTags.FORM_PANEL).assertDoesNotExist()
        rule.runOnIdle { width.value = 1280.dp }
        rule.onNodeWithTag(BusinessUiTags.FORM_PANEL).assertExists()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertExists()
        assertEquals(BusinessDesktopDestination.AGENT, destination.value)
        rule.runOnIdle { width.value = 900.dp }
        rule.onNodeWithTag(BusinessUiTags.FORM_PANEL).assertDoesNotExist()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertExists()
        assertEquals(BusinessDesktopDestination.AGENT, destination.value)
    }

    @Test
    fun `workbench and run history use compact data fallback without changing canonical destination`() {
        val width = mutableStateOf(1280.dp)
        val destination = mutableStateOf(BusinessDesktopDestination.WORKBENCH)
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = BusinessDesktopState(),
                    formState = DemoFormState(),
                    providerSettingsState = BusinessProviderSettingsState(),
                    selectedDestination = destination.value,
                    onDestinationSelected = { destination.value = it },
                    modifier = Modifier.widthForTest(width.value),
                )
            }
        }

        rule.onNodeWithText("工作台功能将在后续阶段开放").assertExists()
        rule.runOnIdle { width.value = 900.dp }
        rule.onNodeWithTag(BusinessUiTags.FORM_PANEL).assertExists()
        assertEquals(BusinessDesktopDestination.WORKBENCH, destination.value)
        rule.runOnIdle {
            width.value = 1280.dp
            destination.value = BusinessDesktopDestination.RUN_HISTORY
        }
        rule.onNodeWithText("运行记录功能将在后续阶段开放").assertExists()
        rule.runOnIdle { width.value = 900.dp }
        rule.onNodeWithTag(BusinessUiTags.FORM_PANEL).assertExists()
        assertEquals(BusinessDesktopDestination.RUN_HISTORY, destination.value)
    }

    @Test
    fun `medium shell keeps 72 dp accessible navigation beside center and agent`() {
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = BusinessDesktopState(),
                    formState = DemoFormState(),
                    providerSettingsState = BusinessProviderSettingsState(),
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

    @Test
    fun `shell forwards form save and submit callbacks`() {
        var saved = false
        var submitted = false
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = BusinessDesktopState(),
                    formState = DemoFormState(),
                    providerSettingsState = BusinessProviderSettingsState(),
                    onSaveDraft = { saved = true },
                    onSubmit = { submitted = true },
                    modifier = Modifier.widthForTest(1280.dp),
                )
            }
        }

        rule.onNodeWithTag("save-draft-action").performScrollTo().performClick()
        rule.onNodeWithTag("submit-action").performScrollTo().performClick()

        org.junit.Assert.assertTrue(saved)
        org.junit.Assert.assertTrue(submitted)
    }
}

private fun Modifier.widthForTest(width: Dp): Modifier = then(Modifier.requiredWidth(width))
