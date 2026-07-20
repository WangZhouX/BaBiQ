package com.wzx.huitai.desktop.ui.shell

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.agent.conversation.BusinessProvider
import com.wzx.huitai.agent.conversation.BusinessProviderModel
import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import com.wzx.huitai.agent.conversation.BusinessThreadItem
import com.wzx.huitai.desktop.controller.BusinessProviderSettingsState
import com.wzx.huitai.desktop.state.BusinessAuthenticationStatus
import com.wzx.huitai.desktop.state.BusinessConnectionStatus
import com.wzx.huitai.desktop.state.BusinessDesktopState
import com.wzx.huitai.desktop.state.BusinessIdentity
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
    fun `fixed agent rail collapses to 52 dp and restores conversation state`() {
        val expanded = mutableStateOf(true)
        val state = BusinessDesktopState(
            messages = listOf(BusinessThreadItem.AgentMessage("agent-1", text = "折叠前的回答")),
            providers = listOf(
                BusinessProvider(
                    id = "relay",
                    displayName = "我的中转站",
                    models = listOf(BusinessProviderModel("kimi-k3", "kimi-k3", active = true)),
                    authMode = "api_key",
                    hasApiKey = true,
                    active = true,
                ),
            ),
            activeProviderId = "relay",
        )
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = state,
                    formState = DemoFormState(),
                    providerSettingsState = BusinessProviderSettingsState(),
                    composerText = "尚未发送的输入",
                    agentPanelExpanded = expanded.value,
                    onAgentPanelExpandedChange = { expanded.value = it },
                    modifier = Modifier.widthForTest(1024.dp),
                )
            }
        }

        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertWidthIsEqualTo(360.dp)
        rule.onNodeWithContentDescription("收起业务 Agent").performClick()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertDoesNotExist()
        rule.onNodeWithTag(BusinessUiTags.AGENT_COLLAPSED_RAIL).assertWidthIsEqualTo(52.dp)
        rule.onNodeWithTag(BusinessUiTags.FORM_PANEL).assertWidthIsEqualTo(900.dp)

        rule.onNodeWithContentDescription("展开业务 Agent").performClick()
        rule.onNodeWithTag(BusinessUiTags.AGENT_COLLAPSED_RAIL).assertDoesNotExist()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertWidthIsEqualTo(360.dp)
        rule.onNodeWithText("折叠前的回答").assertExists()
        rule.onNodeWithText("我的中转站").assertExists()
        rule.onNodeWithTag("agent-composer-input").assertTextContains("尚未发送的输入")
    }

    @Test
    fun `compact agent stays full page when wide rail preference is collapsed`() {
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = BusinessDesktopState(),
                    formState = DemoFormState(),
                    providerSettingsState = BusinessProviderSettingsState(),
                    selectedDestination = BusinessDesktopDestination.AGENT,
                    agentPanelExpanded = false,
                    modifier = Modifier.widthForTest(900.dp),
                )
            }
        }

        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertExists()
        rule.onNodeWithTag(BusinessUiTags.AGENT_COLLAPSED_RAIL).assertDoesNotExist()
        rule.onNodeWithContentDescription("收起业务 Agent").assertDoesNotExist()
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

    @Test
    fun `shell forwards attachment draft actions through the persistent agent rail`() {
        val attachment = BusinessAttachmentDraft(
            id = "00000000-0000-0000-0000-000000000401",
            displayId = "A-BCDEFG",
            name = "evidence.pdf",
            localPath = "C:/private/evidence.pdf",
            sizeBytes = 1024,
            displayType = "PDF",
        )
        var chooseCount = 0
        var removed: String? = null
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = authenticatedShellState(),
                    formState = DemoFormState(),
                    composerAttachments = listOf(attachment),
                    onChooseFiles = { chooseCount++ },
                    onRemoveAttachment = { removed = it },
                    modifier = Modifier.widthForTest(1280.dp),
                )
            }
        }

        rule.onNodeWithTag("agent-composer-attach").performClick()
        rule.onNodeWithContentDescription("移除附件 ${attachment.displayId}").performClick()

        assertEquals(1, chooseCount)
        assertEquals(attachment.id, removed)
    }
}

private fun Modifier.widthForTest(width: Dp): Modifier = then(Modifier.requiredWidth(width))

private fun authenticatedShellState(): BusinessDesktopState = BusinessDesktopState(
    connectionStatus = BusinessConnectionStatus.CONNECTED,
    authenticationStatus = BusinessAuthenticationStatus.AUTHENTICATED,
    identity = BusinessIdentity(
        desktopInstanceId = "desktop-1",
        desktopSessionId = "session-1",
        authSessionId = "auth-1",
        identityEpoch = 1,
        userId = "user-1",
        tenantId = "tenant-1",
        platformId = "platform-1",
        roles = emptySet(),
        permissions = emptySet(),
    ),
)
