package com.wzx.huitai.desktop.ui.shell

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import com.wzx.huitai.agent.conversation.BusinessProvider
import com.wzx.huitai.agent.conversation.BusinessProviderModel
import com.wzx.huitai.agent.conversation.BusinessThreadItem
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.desktop.controller.BusinessProviderSettingsState
import com.wzx.huitai.desktop.BusinessScheduleAttachmentSelection
import com.wzx.huitai.desktop.businessScheduleDraftChangeCallback
import com.wzx.huitai.desktop.businessWorkbenchBindingChangeCallback
import com.wzx.huitai.desktop.auth.BusinessAccessGateState
import com.wzx.huitai.desktop.state.BusinessAuthenticationStatus
import com.wzx.huitai.desktop.state.BusinessConnectionStatus
import com.wzx.huitai.desktop.state.BusinessDesktopState
import com.wzx.huitai.desktop.state.BusinessIdentity
import com.wzx.huitai.desktop.ui.agent.BusinessAssistantChromeTags
import com.wzx.huitai.desktop.ui.layout.BusinessDesktopLayoutPolicy
import com.wzx.huitai.desktop.ui.login.BusinessLoginGate
import com.wzx.huitai.desktop.ui.login.BusinessLoginGateTags
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import com.wzx.huitai.desktop.workbench.BusinessWorkbenchLoadState
import com.wzx.huitai.desktop.workbench.BusinessWorkbenchState
import com.wzx.huitai.desktop.workbench.BusinessScheduleFormState
import com.wzx.huitai.desktop.workbench.BusinessScheduleFormOption
import com.wzx.huitai.desktop.workbench.BusinessScheduleItem
import com.wzx.huitai.desktop.workbench.BusinessScheduleRelationType
import com.wzx.huitai.desktop.workbench.BusinessScheduleState
import com.wzx.huitai.desktop.workbench.BusinessScheduleViewMode
import com.wzx.huitai.desktop.workbench.BusinessAttachmentUploadState
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchScope
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSection
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSectionStatus
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSnapshot
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchTeamRole
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSortKind
import com.wzx.huitai.agent.business.auth.BusinessNavigationTarget
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class BusinessDesktopShellTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `workbench assistant shows provider settings selection when desktop projection is empty`() {
        val providers = listOf(
            BusinessProvider(
                id = "one-api",
                displayName = "我的中转（OneAPI）",
                models = listOf(BusinessProviderModel("gpt-4o", "gpt-4o", active = true)),
                authMode = "api_key",
                hasApiKey = true,
                active = true,
                type = "OPENAI_COMPATIBLE",
                baseUrl = "https://relay.example.com/v1",
                model = "gpt-4o",
            ),
        )
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = authenticatedShellState(),
                    formState = DemoFormState(),
                    providerSettingsState = BusinessProviderSettingsState(providers = providers),
                    selectedDestination = BusinessDesktopDestination.WORKBENCH,
                    agentPanelExpanded = true,
                    modifier = Modifier.shellSize(1400.dp),
                )
            }
        }

        rule.onNodeWithText("我的中转（OneAPI）").assertExists()
        rule.onNodeWithText("gpt-4o").assertExists()
    }

    @Test
    fun `workbench uses the shell sidebar as the only OA navigation`() {
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = BusinessDesktopState(),
                    formState = DemoFormState(),
                    selectedDestination = BusinessDesktopDestination.WORKBENCH,
                    workbenchState = BusinessWorkbenchState(
                        navigation = listOf(
                            BusinessNavigationTarget("WORKBENCH", "/", "工作台"),
                            BusinessNavigationTarget("CASE", "/case", "案件管理"),
                            BusinessNavigationTarget("CUSTOMER", "/customer", "客户管理"),
                        ),
                    ),
                    modifier = Modifier.requiredSize(1200.dp, 700.dp),
                )
            }
        }

        rule.onNodeWithText("资料录入").assertDoesNotExist()
        rule.onNodeWithText("运行记录").assertDoesNotExist()
        rule.onNodeWithText("案件管理").assertExists()
        rule.onNodeWithText("客户管理").assertExists()
        rule.onNodeWithTag(com.wzx.huitai.desktop.ui.workbench.WorkbenchTags.NAVIGATION).assertDoesNotExist()
    }

    @Test
    fun `login gate never composes business shell before ready and removes it on logout`() {
        val gate = mutableStateOf(BusinessAccessGateState.STARTING)
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessLoginGate(
                    gate = gate.value,
                    login = { Text("登录页", Modifier.testTag("login-content")) },
                    ready = { Text("业务桌面", Modifier.testTag("ready-content")) },
                )
            }
        }

        rule.onNodeWithTag(BusinessLoginGateTags.RECOVERY_PROGRESS).assertExists()
        rule.onNodeWithTag("login-content").assertDoesNotExist()
        rule.onNodeWithTag("ready-content").assertDoesNotExist()

        rule.runOnIdle { gate.value = BusinessAccessGateState.RESTORING }
        rule.onNodeWithTag(BusinessLoginGateTags.RECOVERY_PROGRESS).assertExists()
        rule.onNodeWithTag("ready-content").assertDoesNotExist()

        rule.runOnIdle { gate.value = BusinessAccessGateState.SIGNED_OUT }
        rule.onNodeWithTag("login-content").assertExists()
        rule.onNodeWithTag("ready-content").assertDoesNotExist()

        rule.runOnIdle { gate.value = BusinessAccessGateState.AUTHENTICATING }
        rule.onNodeWithTag("login-content").assertExists()
        rule.onNodeWithTag("ready-content").assertDoesNotExist()

        rule.runOnIdle { gate.value = BusinessAccessGateState.READY }
        rule.onNodeWithTag("login-content").assertDoesNotExist()
        rule.onNodeWithTag("ready-content").assertExists()

        rule.runOnIdle { gate.value = BusinessAccessGateState.SIGNING_OUT }
        rule.onNodeWithTag(BusinessLoginGateTags.SIGNING_OUT_PROGRESS).assertExists()
        rule.onNodeWithTag("login-content").assertDoesNotExist()
        rule.onNodeWithTag("ready-content").assertDoesNotExist()

        rule.runOnIdle { gate.value = BusinessAccessGateState.SIGNED_OUT }
        rule.onNodeWithTag("login-content").assertExists()
        rule.onNodeWithTag("ready-content").assertDoesNotExist()
    }

    @Test
    fun `settings logout immediately removes shell and clears sensitive login input`() {
        val gate = mutableStateOf(BusinessAccessGateState.READY)
        val password = mutableStateOf("golden-secret")
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessLoginGate(
                    gate = gate.value,
                    login = { Text("登录页", Modifier.testTag("login-content")) },
                    ready = {
                        BusinessDesktopShell(
                            state = authenticatedShellState(),
                            formState = DemoFormState(),
                            providerSettingsState = BusinessProviderSettingsState(operationsEnabled = true),
                            selectedDestination = BusinessDesktopDestination.SETTINGS,
                            onLogout = {
                                password.value = ""
                                gate.value = BusinessAccessGateState.SIGNING_OUT
                            },
                            modifier = Modifier.shellSize(1200.dp, 700.dp),
                        )
                    },
                )
            }
        }

        rule.onNodeWithTag(BusinessSidebarTags.ROOT).assertExists()
        rule.onNodeWithTag("business-logout-action").performClick()
        rule.onNodeWithTag(BusinessSidebarTags.ROOT).assertDoesNotExist()
        rule.onNodeWithTag(BusinessLoginGateTags.SIGNING_OUT_PROGRESS).assertExists()
        rule.runOnIdle { assertEquals("", password.value) }
    }

    @Test
    fun `shell reports its own and sidebar navigation committed composition`() {
        var shellComposed = false
        var sidebarNavigationComposed = false
        assertFalse(shellComposed)
        assertFalse(sidebarNavigationComposed)
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = BusinessDesktopState(),
                    formState = DemoFormState(),
                    onShellComposed = { shellComposed = true },
                    onSidebarNavigationComposed = { sidebarNavigationComposed = true },
                    modifier = Modifier.shellSize(1200.dp),
                )
            }
        }

        rule.runOnIdle {
            assertTrue(shellComposed)
            assertTrue(sidebarNavigationComposed)
        }
    }

    @Test
    fun `collapsed shell gives the full dock to business and floats only the mascot at bottom right`() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                HuitaiBusinessTheme {
                    BusinessDesktopShell(
                        state = BusinessDesktopState(),
                        formState = DemoFormState(),
                        providerSettingsState = BusinessProviderSettingsState(),
                        agentPanelExpanded = false,
                        modifier = Modifier.shellSize(1200.dp, 700.dp),
                    )
                }
            }
        }

        rule.onNodeWithTag("business-top-navigation").assertDoesNotExist()
        rule.onNodeWithTag(BusinessSidebarTags.ROOT).assertWidthIsEqualTo(210.dp)
        rule.onNodeWithTag(BusinessSidebarTags.DATA_ENTRY).assertExists()
        rule.onNodeWithTag(BusinessUiTags.BUSINESS_REGION).assertWidthIsEqualTo(990.dp)
        rule.onNodeWithTag(BusinessUiTags.FORM_PANEL).assertWidthIsEqualTo(990.dp)
        rule.onNodeWithTag(BusinessUiTags.COLLAPSED_ASSISTANT_CONTROL).assertDoesNotExist()
        rule.onNodeWithTag(BusinessAssistantChromeTags.MASCOT).assertExists()
        rule.onNodeWithTag("business-mascot-safe-area").assertDoesNotExist()
        rule.onNodeWithTag("business-agent-collapsed-rail").assertDoesNotExist()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertDoesNotExist()

        val sidebar = bounds(BusinessSidebarTags.ROOT)
        val dock = bounds(BusinessUiTags.CONTENT)
        val business = bounds(BusinessUiTags.BUSINESS_REGION)
        val mascot = bounds(BusinessAssistantChromeTags.MASCOT)
        assertApproximately(0f, sidebar.top)
        assertApproximately(0f, dock.top)
        assertApproximately(sidebar.right, dock.left)
        assertApproximately(dock.left, business.left)
        assertApproximately(dock.right, business.right)
        assertApproximately(dock.top, business.top)
        assertApproximately(dock.bottom, business.bottom)
        assertApproximately(dock.width, business.width)
        assertTrue(mascot.left >= business.left && mascot.right <= business.right)
        assertTrue(mascot.top >= business.top && mascot.bottom <= business.bottom)
        assertTrue(mascot.left > business.center.x)
        assertTrue(mascot.top > business.center.y)
        assertApproximately(business.right, mascot.right)
        assertApproximately(business.bottom, mascot.bottom)
        assertTrue(mascot.width < business.width)
        assertTrue(mascot.height < business.height)
    }

    @Test
    fun `expanded dock fills content exactly without overlap and mascot restores full business width`() {
        val expanded = mutableStateOf(false)
        val requestedWidth = mutableStateOf(460.dp)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.75f)) {
                HuitaiBusinessTheme {
                    BusinessDesktopShell(
                        state = authenticatedShellState().copy(
                            messages = listOf(BusinessThreadItem.AgentMessage("agent-1", text = "折叠前的回答")),
                        ),
                        formState = DemoFormState(),
                        composerText = "尚未发送的输入",
                        agentPanelExpanded = expanded.value,
                        requestedAssistantWidth = requestedWidth.value,
                        onAgentPanelExpandedChange = { expanded.value = it },
                        onRequestedAssistantWidthChange = { requestedWidth.value = it },
                        modifier = Modifier.shellSize(1400.dp),
                    )
                }
            }
        }

        rule.onNodeWithTag(BusinessAssistantChromeTags.MASCOT).performClick()

        val content = bounds(BusinessUiTags.CONTENT)
        val business = bounds(BusinessUiTags.BUSINESS_REGION)
        val divider = bounds(BusinessUiTags.DIVIDER_SLOT)
        val assistant = bounds(BusinessUiTags.AGENT_PANEL)
        val sidebar = bounds(BusinessSidebarTags.ROOT)
        assertApproximately(0f, sidebar.top)
        assertApproximately(0f, content.top)
        assertApproximately(sidebar.right, content.left)
        assertApproximately(content.left, business.left)
        assertApproximately(business.right, divider.left)
        assertApproximately(divider.right, assistant.left)
        assertApproximately(content.right, assistant.right)
        assertApproximately(content.width, business.width + divider.width + assistant.width)
        assertApproximately(640f * 0.75f, business.width, minimum = true)
        assertApproximately(8f * 0.75f, divider.width)
        assertApproximately(460f * 0.75f, assistant.width)

        rule.onNodeWithContentDescription("收回小律智能助手").performClick()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertDoesNotExist()
        rule.onNodeWithTag(BusinessUiTags.DIVIDER_SLOT).assertDoesNotExist()
        rule.onNodeWithTag(BusinessUiTags.COLLAPSED_ASSISTANT_CONTROL).assertDoesNotExist()
        assertApproximately(
            bounds(BusinessUiTags.CONTENT).width,
            bounds(BusinessUiTags.BUSINESS_REGION).width,
        )
        rule.onNodeWithContentDescription("打开小律智能助手").assertExists()

        rule.onNodeWithContentDescription("打开小律智能助手").performClick()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertWidthIsEqualTo(460.dp)
        rule.onNodeWithText("折叠前的回答").assertExists()
        rule.onNodeWithTag("agent-composer-input").assertTextContains("尚未发送的输入")
    }

    @Test
    fun `1217dp refuses expansion inside control while 1218dp allows a 360dp assistant`() {
        val width = mutableStateOf(1217.dp)
        val expanded = mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.75f)) {
                HuitaiBusinessTheme {
                    BusinessDesktopShell(
                        state = BusinessDesktopState(),
                        formState = DemoFormState(),
                        agentPanelExpanded = expanded.value,
                        onAgentPanelExpandedChange = { expanded.value = it },
                        modifier = Modifier.shellSize(width.value),
                    )
                }
            }
        }

        rule.onNodeWithContentDescription("打开小律智能助手").performClick()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertDoesNotExist()
        rule.onNodeWithTag(BusinessUiTags.EXPAND_WIDTH_MESSAGE)
            .assertTextContains("窗口宽度不足，请先最大化或放大窗口")
        rule.runOnIdle { assertFalse(expanded.value) }

        val dock = bounds(BusinessUiTags.CONTENT)
        val business = bounds(BusinessUiTags.BUSINESS_REGION)
        val message = bounds(BusinessUiTags.EXPAND_WIDTH_MESSAGE)
        val mascot = bounds(BusinessAssistantChromeTags.MASCOT)
        rule.onNodeWithTag(BusinessUiTags.COLLAPSED_ASSISTANT_CONTROL).assertDoesNotExist()
        assertApproximately(dock.width, business.width)
        assertTrue(message.left >= business.left && message.right <= business.right)
        assertTrue(message.top >= business.top && message.bottom <= business.bottom)
        assertTrue(message.bottom <= mascot.top + 0.5f)
        assertFalse(message.overlaps(mascot))
        assertTrue(mascot.left > business.center.x)
        assertTrue(mascot.top > business.center.y)

        rule.runOnIdle { width.value = 1218.dp }
        rule.onNodeWithContentDescription("打开小律智能助手").performClick()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertWidthIsEqualTo(360.dp)
        rule.onNodeWithTag(BusinessUiTags.COLLAPSED_ASSISTANT_CONTROL).assertDoesNotExist()
        rule.onNodeWithTag(BusinessUiTags.EXPAND_WIDTH_MESSAGE).assertDoesNotExist()
        rule.runOnIdle { assertTrue(expanded.value) }
    }

    @Test
    fun `expand width message clears when dock becomes wide enough without another click`() {
        val width = mutableStateOf(1217.dp)
        val expanded = mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.75f)) {
                HuitaiBusinessTheme {
                    BusinessDesktopShell(
                        state = BusinessDesktopState(),
                        formState = DemoFormState(),
                        agentPanelExpanded = expanded.value,
                        onAgentPanelExpandedChange = { expanded.value = it },
                        modifier = Modifier.shellSize(width.value),
                    )
                }
            }
        }

        rule.onNodeWithContentDescription("打开小律智能助手").performClick()
        rule.onNodeWithTag(BusinessUiTags.EXPAND_WIDTH_MESSAGE).assertExists()

        rule.runOnIdle { width.value = 1218.dp }
        rule.onNodeWithTag(BusinessUiTags.EXPAND_WIDTH_MESSAGE).assertDoesNotExist()
        rule.onNodeWithTag(BusinessUiTags.COLLAPSED_ASSISTANT_CONTROL).assertDoesNotExist()
        assertApproximately(
            bounds(BusinessUiTags.CONTENT).width,
            bounds(BusinessUiTags.BUSINESS_REGION).width,
        )
        rule.runOnIdle { assertFalse(expanded.value) }
    }

    @Test
    fun `real drag keyboard resize and navigation preserve requested assistant width`() {
        val destination = mutableStateOf(BusinessDesktopDestination.DATA_ENTRY)
        val requestedWidth = mutableStateOf(460.dp)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.75f)) {
                HuitaiBusinessTheme {
                    BusinessDesktopShell(
                        state = authenticatedShellState(),
                        formState = DemoFormState(),
                        selectedDestination = destination.value,
                        agentPanelExpanded = true,
                        requestedAssistantWidth = requestedWidth.value,
                        onDestinationSelected = { destination.value = it },
                        onRequestedAssistantWidthChange = { requestedWidth.value = it },
                        modifier = Modifier.shellSize(1400.dp),
                    )
                }
            }
        }

        rule.onNodeWithTag(BusinessAssistantChromeTags.RESIZE_HANDLE).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(-30f, 0f))
            release()
        }
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertWidthIsEqualTo(500.dp)

        rule.onNodeWithTag(BusinessAssistantChromeTags.RESIZE_HANDLE)
            .requestFocus()
            .performKeyInput { pressKey(Key.DirectionLeft) }
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertWidthIsEqualTo(516.dp)

        rule.onAllNodesWithText("设置").assertCountEquals(1)
        rule.onNodeWithTag(BusinessSidebarTags.SETTINGS).performClick()
        rule.onNodeWithTag("provider-settings-panel").assertExists()
        assertTrue(
            rule.onNodeWithTag(BusinessSidebarTags.SETTINGS)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Selected],
        )
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertWidthIsEqualTo(516.dp)
        rule.onNodeWithTag(BusinessSidebarTags.DATA_ENTRY).performClick()
        rule.onNodeWithTag(BusinessUiTags.FORM_PANEL).assertExists()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertWidthIsEqualTo(516.dp)
        rule.runOnIdle { assertEquals(516.dp, requestedWidth.value) }
    }

    @Test
    fun `provider editor keeps its unsaved draft when assistant expansion changes`() {
        val expanded = mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.75f)) {
                HuitaiBusinessTheme {
                    BusinessDesktopShell(
                        state = BusinessDesktopState(),
                        formState = DemoFormState(),
                        providerSettingsState = BusinessProviderSettingsState(
                            operationsEnabled = true,
                            connectionGeneration = 1,
                        ),
                        selectedDestination = BusinessDesktopDestination.SETTINGS,
                        agentPanelExpanded = expanded.value,
                        modifier = Modifier.shellSize(1400.dp),
                    )
                }
            }
        }

        rule.onNodeWithTag("provider-add-action").performClick()
        rule.onNodeWithTag("provider-display-name-input").performTextReplacement("未保存 Provider")
        rule.onNodeWithTag("provider-api-key-input").performTextReplacement("sk-unsaved-local")

        rule.runOnIdle { expanded.value = true }
        assertInputText("provider-display-name-input", "未保存 Provider")
        assertInputText("provider-api-key-input", "sk-unsaved-local")

        rule.runOnIdle { expanded.value = false }
        assertInputText("provider-display-name-input", "未保存 Provider")
        assertInputText("provider-api-key-input", "sk-unsaved-local")
    }

    @Test
    fun `one pointer gesture accumulates every move delta and then syncs an external width`() {
        val requestedWidth = mutableStateOf(460.dp)
        val emittedWidths = mutableListOf<Dp>()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.75f)) {
                HuitaiBusinessTheme {
                    BusinessDesktopShell(
                        state = authenticatedShellState(),
                        formState = DemoFormState(),
                        agentPanelExpanded = true,
                        requestedAssistantWidth = requestedWidth.value,
                        onRequestedAssistantWidthChange = { emittedWidths += it },
                        modifier = Modifier.shellSize(1400.dp),
                    )
                }
            }
        }

        rule.onNodeWithTag(BusinessAssistantChromeTags.RESIZE_HANDLE).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(-10f, 0f))
            moveBy(Offset(-10f, 0f))
            moveBy(Offset(-10f, 0f))
            release()
        }
        rule.runOnIdle {
            assertTrue(
                abs(emittedWidths.last().value - 500f) <= 0.1f,
                "expected one gesture to emit 500dp, actual ${emittedWidths.last()}",
            )
            emittedWidths.clear()
            requestedWidth.value = 520.dp
        }
        rule.waitForIdle()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertWidthIsEqualTo(520.dp)

        rule.onNodeWithTag(BusinessAssistantChromeTags.RESIZE_HANDLE).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(10f, 0f))
            moveBy(Offset(10f, 0f))
            moveBy(Offset(10f, 0f))
            release()
        }
        rule.runOnIdle {
            assertTrue(
                abs(emittedWidths.last().value - 480f) <= 0.1f,
                "expected external 520dp to sync before emitting 480dp, actual ${emittedWidths.last()}",
            )
        }
    }

    @Test
    fun `eight dp divider exposes an unclipped twelve dp handle whose two outer edges drag`() {
        val resizeEvents = mutableListOf<Dp>()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.75f)) {
                HuitaiBusinessTheme {
                    BusinessDesktopShell(
                        state = authenticatedShellState(),
                        formState = DemoFormState(),
                        agentPanelExpanded = true,
                        requestedAssistantWidth = 460.dp,
                        onRequestedAssistantWidthChange = { resizeEvents += it },
                        modifier = Modifier.shellSize(1400.dp),
                    )
                }
            }
        }

        rule.onNodeWithTag(BusinessUiTags.DIVIDER_SLOT).assertWidthIsEqualTo(8.dp)
        rule.onNodeWithTag(BusinessAssistantChromeTags.RESIZE_HANDLE).assertWidthIsEqualTo(12.dp)
        rule.onNodeWithTag(BusinessAssistantChromeTags.RESIZE_RAIL).assertWidthIsEqualTo(1.dp)
        val slot = bounds(BusinessUiTags.DIVIDER_SLOT)
        val handle = bounds(BusinessAssistantChromeTags.RESIZE_HANDLE)
        assertTrue(handle.left < slot.left)
        assertTrue(handle.right > slot.right)

        rule.onNodeWithTag(BusinessAssistantChromeTags.RESIZE_HANDLE).performMouseInput {
            moveTo(Offset(0.5f, center.y))
            press()
            moveBy(Offset(-3f, 0f))
            release()
        }
        rule.waitForIdle()
        rule.onNodeWithTag(BusinessAssistantChromeTags.RESIZE_HANDLE).performMouseInput {
            moveTo(Offset(handle.width - 0.5f, center.y))
            press()
            moveBy(Offset(3f, 0f))
            release()
        }
        rule.runOnIdle {
            assertTrue(resizeEvents.size >= 2, "左右透明边缘都必须命中真实拖拽手势")
        }
    }

    @Test
    fun `collapsed mascot is a local bottom-right overlay without a full-height control column`() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.75f)) {
                HuitaiBusinessTheme {
                    BusinessDesktopShell(
                        state = BusinessDesktopState(),
                        formState = DemoFormState(),
                        agentPanelExpanded = false,
                        modifier = Modifier.shellSize(1200.dp),
                    )
                }
            }
        }

        rule.onNodeWithTag("save-draft-action").performScrollTo()
        rule.onNodeWithTag("business-mascot-safe-area").assertDoesNotExist()
        val form = bounds(BusinessUiTags.FORM_PANEL)
        val business = bounds(BusinessUiTags.BUSINESS_REGION)
        val mascot = bounds(BusinessAssistantChromeTags.MASCOT)
        rule.onNodeWithTag(BusinessUiTags.COLLAPSED_ASSISTANT_CONTROL).assertDoesNotExist()
        assertApproximately(bounds(BusinessUiTags.CONTENT).width, business.width)
        assertTrue(form.right <= business.right + 0.5f)
        assertTrue(mascot.left >= business.left && mascot.right <= business.right)
        assertTrue(mascot.top >= business.top && mascot.bottom <= business.bottom)
        assertTrue(mascot.left > business.center.x)
        assertTrue(mascot.top > business.center.y)
        assertApproximately(business.right, mascot.right)
        assertApproximately(business.bottom, mascot.bottom)
        assertTrue(mascot.width < business.width)
        assertTrue(mascot.height < business.height)
        assertFalse(mascot.overlaps(bounds("save-draft-action")))
        assertFalse(mascot.overlaps(bounds("submit-action")))
    }

    @Test
    fun `shell forwards form actions and attachment actions in expanded assistant`() {
        val attachment = BusinessAttachmentDraft(
            id = "00000000-0000-0000-0000-000000000401",
            displayId = "A-BCDEFG",
            name = "evidence.pdf",
            localPath = "C:/private/evidence.pdf",
            sizeBytes = 1024,
            displayType = "PDF",
        )
        var saved = false
        var submitted = false
        var chooseCount = 0
        var removed: String? = null
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.75f)) {
                HuitaiBusinessTheme {
                    BusinessDesktopShell(
                        state = authenticatedShellState(),
                        formState = DemoFormState(),
                        composerAttachments = listOf(attachment),
                        agentPanelExpanded = true,
                        onSaveDraft = { saved = true },
                        onSubmit = { submitted = true },
                        onChooseFiles = { chooseCount++ },
                        onRemoveAttachment = { removed = it },
                        modifier = Modifier.shellSize(1400.dp),
                    )
                }
            }
        }

        rule.onNodeWithTag("save-draft-action").performScrollTo().performClick()
        rule.onNodeWithTag("submit-action").performScrollTo().performClick()
        rule.onNodeWithTag("agent-composer-attach").performClick()
        rule.onNodeWithContentDescription("移除附件 ${attachment.displayId}").performClick()

        rule.runOnIdle {
            assertTrue(saved)
            assertTrue(submitted)
            assertEquals(1, chooseCount)
            assertEquals(attachment.id, removed)
        }
    }

    @Test
    fun `workbench shell forwards team role and sort intents`() {
        var team: String? = null
        var role: String? = null
        var sort: Pair<BusinessWorkbenchSortKind, List<String>>? = null
        val workbench = BusinessWorkbenchState(
            loadState = BusinessWorkbenchLoadState.READY,
            identityEpoch = 1,
            generation = 2,
            scope = BusinessWorkbenchScope.TEAM,
            teamId = "team-1",
            roles = listOf(BusinessWorkbenchTeamRole("OWNER", "负责人")),
            shortcutOrder = listOf("one", "two"),
            snapshot = BusinessWorkbenchSnapshot(
                identityEpoch = 1,
                generation = 2,
                shortcuts = BusinessWorkbenchSection(
                    BusinessWorkbenchSectionStatus.OK,
                    Json.parseToJsonElement(
                        """[
                            {"id":"one","title":"案件","path":"/case"},
                            {"id":"two","title":"团队","path":"/team"}
                        ]""",
                    ),
                ),
                teams = BusinessWorkbenchSection(
                    BusinessWorkbenchSectionStatus.OK,
                    Json.parseToJsonElement(
                        """[{"id":"team-1","name":"一组"},{"id":"team-2","name":"二组"}]""",
                    ),
                ),
            ),
        )
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = authenticatedShellState(),
                    formState = DemoFormState(),
                    selectedDestination = BusinessDesktopDestination.WORKBENCH,
                    workbenchState = workbench,
                    onWorkbenchTeamSelected = { team = it },
                    onWorkbenchRoleSelected = { role = it },
                    onWorkbenchSortRequested = { kind, ids -> sort = kind to ids },
                    modifier = Modifier.shellSize(1200.dp, 900.dp),
                )
            }
        }

        rule.onNodeWithTag("business-workbench-team-team-2").performClick()
        rule.onNodeWithTag("business-workbench-role-OWNER").performClick()
        rule.onNodeWithTag("business-workbench-quick-sort-down-one").performClick()
        rule.runOnIdle {
            assertEquals("team-2", team)
            assertEquals("OWNER", role)
            assertEquals(BusinessWorkbenchSortKind.SHORTCUT to listOf("two", "one"), sort)
        }
    }

    @Test
    fun `shell scope team and schedule type callbacks cancel upload and clear local attachment paths`() {
        val selection = BusinessScheduleAttachmentSelection()
        val cancellations = mutableListOf<String>()
        val changes = mutableListOf<String>()
        val form = mutableStateOf(
            BusinessScheduleFormState(
                visible = false,
                draft = com.wzx.huitai.desktop.workbench.BusinessScheduleDraft.validForTest().copy(
                    scope = BusinessWorkbenchScope.TEAM,
                    teamId = "team-1",
                    typeId = "type-1",
                ),
                types = listOf(
                    BusinessScheduleFormOption("type-1", "会议"),
                    BusinessScheduleFormOption("type-2", "庭审"),
                ),
            ),
        )
        val oldFile = BusinessAttachmentDraft(
            "00000000-0000-0000-0000-000000000001",
            "A-BCDEFG",
            "old.pdf",
            "C:/old.pdf",
            1,
            "PDF",
        )
        fun activeOldPicker(): BusinessScheduleAttachmentSelection.Request {
            val initial = selection.tryBegin()!!
            assertTrue(selection.commit(initial, listOf(oldFile)))
            return selection.tryBegin()!!
        }
        fun assertCleared(oldPicker: BusinessScheduleAttachmentSelection.Request) {
            assertNull(selection.merge(oldPicker, emptyList()))
            val next = selection.tryBegin()!!
            assertTrue(next.currentDrafts.isEmpty())
            selection.finish(next)
        }
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f)) {
                HuitaiBusinessTheme {
                    BusinessDesktopShell(
                    state = authenticatedShellState(),
                    formState = DemoFormState(),
                    selectedDestination = BusinessDesktopDestination.WORKBENCH,
                    workbenchState = BusinessWorkbenchState(
                        loadState = BusinessWorkbenchLoadState.READY,
                        identityEpoch = 1,
                        generation = 2,
                        scope = BusinessWorkbenchScope.TEAM,
                        teamId = "team-1",
                        snapshot = BusinessWorkbenchSnapshot(
                            identityEpoch = 1,
                            generation = 2,
                            teams = BusinessWorkbenchSection(
                                BusinessWorkbenchSectionStatus.OK,
                                Json.parseToJsonElement(
                                    """[{"id":"team-1","name":"一组"},{"id":"team-2","name":"二组"}]""",
                                ),
                            ),
                        ),
                    ),
                    scheduleFormState = form.value,
                    onWorkbenchScopeSelected = businessWorkbenchBindingChangeCallback(
                        selection = selection,
                        cancelUpload = { cancellations += "scope" },
                        onBindingChanged = { changes += "scope:$it" },
                    ),
                    onWorkbenchTeamSelected = businessWorkbenchBindingChangeCallback(
                        selection = selection,
                        cancelUpload = { cancellations += "team" },
                        onBindingChanged = { changes += "team:$it" },
                    ),
                    onScheduleDraftChanged = businessScheduleDraftChangeCallback(
                        currentDraft = { form.value.draft },
                        selection = selection,
                        cancelUpload = { cancellations += "type" },
                        onDraftChanged = {
                            changes += "type:${it.typeId}"
                            form.value = form.value.copy(draft = it)
                        },
                    ),
                        modifier = Modifier.shellSize(1400.dp, 1200.dp),
                    )
                }
            }
        }

        val scopePicker = activeOldPicker()
        rule.onNodeWithTag(com.wzx.huitai.desktop.ui.workbench.WorkbenchTags.scopeItem("PERSONAL"))
            .performClick()
        rule.runOnIdle { assertCleared(scopePicker) }

        val teamPicker = activeOldPicker()
        rule.onNodeWithTag("business-workbench-team-team-2").performClick()
        rule.runOnIdle { assertCleared(teamPicker) }

        rule.runOnIdle { form.value = form.value.copy(visible = true) }
        val typePicker = activeOldPicker()
        rule.onNodeWithTag(com.wzx.huitai.desktop.ui.workbench.ScheduleCreateTags.type("type-2"))
            .performClick()
        rule.runOnIdle {
            assertCleared(typePicker)
            assertEquals(listOf("scope", "team", "type"), cancellations)
            assertEquals(
                listOf(
                    "scope:${BusinessWorkbenchScope.PERSONAL}",
                    "team:team-2",
                    "type:type-2",
                ),
                changes,
            )
        }
    }

    @Test
    fun `workbench shell exposes production schedule panel and dialog callbacks`() {
        val actions = mutableListOf<String>()
        val form = mutableStateOf(BusinessScheduleFormState())
        val upload = mutableStateOf(BusinessAttachmentUploadState())
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f)) {
                HuitaiBusinessTheme {
                    BusinessDesktopShell(
                    state = authenticatedShellState(),
                    formState = DemoFormState(),
                    selectedDestination = BusinessDesktopDestination.WORKBENCH,
                    workbenchState = BusinessWorkbenchState(
                        loadState = BusinessWorkbenchLoadState.READY,
                        identityEpoch = 1,
                        generation = 2,
                    ),
                    scheduleState = BusinessScheduleState(
                        identityEpoch = 1,
                        generation = 2,
                        visibleMonth = YearMonth.of(2026, 7),
                        selectedDate = LocalDate.of(2026, 7, 29),
                        scope = BusinessWorkbenchScope.TEAM,
                        teamId = "team-1",
                        onlyMine = true,
                        eventDates = setOf(LocalDate.of(2026, 7, 29)),
                        items = listOf(
                            BusinessScheduleItem(
                                id = "schedule-1",
                                title = "客户会议",
                                at = "2026-07-29 10:00:00",
                                completed = false,
                            ),
                        ),
                    ),
                    scheduleFormState = form.value,
                    scheduleUploadState = upload.value,
                    onScheduleViewModeChanged = { actions += "mode:$it" },
                    onScheduleOnlyMineChanged = { actions += "mine:$it" },
                    onScheduleCompletionChanged = { id, completed ->
                        actions += "complete:$id:$completed"
                    },
                    onScheduleCreate = {
                        actions += "create"
                        form.value = BusinessScheduleFormState(
                            visible = true,
                            draft = com.wzx.huitai.desktop.workbench.BusinessScheduleDraft.validForTest(),
                        )
                    },
                    onScheduleDraftChanged = { actions += "draft:${it.title}" },
                    onScheduleRelationTypeSelected = { actions += "relation:$it" },
                    onScheduleChooseAttachments = {
                        actions += "upload"
                        upload.value = BusinessAttachmentUploadState(uploading = true, progress = 0.5f)
                    },
                    onScheduleCancelUpload = {
                        actions += "cancel-upload"
                        upload.value = BusinessAttachmentUploadState()
                    },
                    onScheduleSubmit = { actions += "submit" },
                    onScheduleDismiss = { actions += "dismiss" },
                        modifier = Modifier.shellSize(1400.dp, 1200.dp),
                    )
                }
            }
        }

        rule.onNodeWithTag(com.wzx.huitai.desktop.ui.workbench.ScheduleTags.WEEK)
            .performScrollTo()
            .performClick()
        rule.onNodeWithTag(com.wzx.huitai.desktop.ui.workbench.ScheduleTags.ONLY_MINE)
            .performClick()
        rule.onNodeWithTag(com.wzx.huitai.desktop.ui.workbench.ScheduleTags.complete("schedule-1"))
            .performClick()
        rule.onNodeWithTag(com.wzx.huitai.desktop.ui.workbench.ScheduleTags.CREATE)
            .performClick()
        rule.onNodeWithTag(com.wzx.huitai.desktop.ui.workbench.ScheduleCreateTags.TITLE)
            .performTextReplacement("庭审准备")
        rule.onNodeWithTag(
            com.wzx.huitai.desktop.ui.workbench.ScheduleCreateTags.relation(
                BusinessScheduleRelationType.CASE,
            ),
        ).performClick()
        rule.onNodeWithTag(com.wzx.huitai.desktop.ui.workbench.ScheduleCreateTags.CHOOSE_ATTACHMENTS)
            .performClick()
        rule.onNodeWithTag(com.wzx.huitai.desktop.ui.workbench.ScheduleCreateTags.CANCEL_UPLOAD)
            .performClick()
        rule.onNodeWithTag(com.wzx.huitai.desktop.ui.workbench.ScheduleCreateTags.SUBMIT)
            .performClick()
        rule.onNodeWithTag(com.wzx.huitai.desktop.ui.workbench.ScheduleCreateTags.DISMISS)
            .performClick()

        rule.runOnIdle {
            assertTrue("mode:${BusinessScheduleViewMode.WEEK}" in actions)
            assertTrue("mine:false" in actions)
            assertTrue("complete:schedule-1:true" in actions)
            assertTrue("create" in actions)
            assertTrue("draft:庭审准备" in actions)
            assertTrue("relation:${BusinessScheduleRelationType.CASE}" in actions)
            assertTrue("upload" in actions)
            assertTrue("cancel-upload" in actions)
            assertTrue("submit" in actions)
            assertTrue("dismiss" in actions)
        }
    }

    @Test
    fun `non home workbench path renders a visible placeholder while home remains the real workbench`() {
        val path = mutableStateOf("/case")
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = authenticatedShellState(),
                    formState = DemoFormState(),
                    selectedDestination = BusinessDesktopDestination.WORKBENCH,
                    selectedWorkbenchPath = path.value,
                    workbenchState = BusinessWorkbenchState(
                        loadState = BusinessWorkbenchLoadState.READY,
                        identityEpoch = 1,
                        generation = 2,
                        navigation = listOf(
                            com.wzx.huitai.agent.business.auth.BusinessNavigationTarget("WORKBENCH", "/", "工作台"),
                            com.wzx.huitai.agent.business.auth.BusinessNavigationTarget("CASE", "/case", "案件管理"),
                        ),
                    ),
                    modifier = Modifier.shellSize(1200.dp, 800.dp),
                )
            }
        }

        rule.onNodeWithTag(BusinessUiTags.PLACEHOLDER_PANEL).assertExists()
        rule.onNodeWithText("功能占位：/case").assertExists()
        rule.onNodeWithTag(com.wzx.huitai.desktop.ui.workbench.WorkbenchTags.NAVIGATION)
            .assertWidthIsEqualTo(88.dp)
        rule.onNodeWithTag(com.wzx.huitai.desktop.ui.workbench.WorkbenchTags.navItem("/case"))
            .assertIsSelected()
        rule.runOnIdle { path.value = "/" }
        rule.onNodeWithTag(BusinessUiTags.PLACEHOLDER_PANEL).assertDoesNotExist()
        rule.onNodeWithTag(com.wzx.huitai.desktop.ui.workbench.WorkbenchTags.ROOT).assertExists()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertInputText(tag: String, expected: String) {
        val input = rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.InputText]
        assertEquals(expected, input.text)
    }

    private fun assertApproximately(expected: Float, actual: Float, minimum: Boolean = false) {
        if (minimum) {
            assertTrue(actual + 0.5f >= expected, "expected at least $expected, actual $actual")
        } else {
            assertTrue(abs(expected - actual) <= 0.5f, "expected $expected, actual $actual")
        }
    }
}

private fun Modifier.shellSize(width: Dp, height: Dp = 900.dp): Modifier =
    then(Modifier.requiredSize(width, height))

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
