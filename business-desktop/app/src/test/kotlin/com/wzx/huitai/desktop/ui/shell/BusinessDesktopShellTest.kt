package com.wzx.huitai.desktop.ui.shell

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import com.wzx.huitai.agent.conversation.BusinessThreadItem
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.desktop.controller.BusinessProviderSettingsState
import com.wzx.huitai.desktop.state.BusinessAuthenticationStatus
import com.wzx.huitai.desktop.state.BusinessConnectionStatus
import com.wzx.huitai.desktop.state.BusinessDesktopState
import com.wzx.huitai.desktop.state.BusinessIdentity
import com.wzx.huitai.desktop.ui.agent.BusinessAssistantChromeTags
import com.wzx.huitai.desktop.ui.layout.BusinessDesktopLayoutPolicy
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class BusinessDesktopShellTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `shell reports its own and top navigation committed composition`() {
        var shellComposed = false
        var topNavigationComposed = false
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessDesktopShell(
                    state = BusinessDesktopState(),
                    formState = DemoFormState(),
                    onShellComposed = { shellComposed = true },
                    onTopNavigationComposed = { topNavigationComposed = true },
                    modifier = Modifier.shellSize(1200.dp),
                )
            }
        }

        rule.runOnIdle {
            assertTrue(shellComposed)
            assertTrue(topNavigationComposed)
        }
    }

    @Test
    fun `collapsed shell uses top toolbar sidebar business region and assistant control without overlap`() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                HuitaiBusinessTheme {
                    BusinessDesktopShell(
                        state = BusinessDesktopState(),
                        formState = DemoFormState(),
                        providerSettingsState = BusinessProviderSettingsState(),
                        agentPanelExpanded = false,
                        modifier = Modifier.shellSize(1200.dp),
                    )
                }
            }
        }

        rule.onNodeWithTag(BusinessTopNavigationTags.ROOT).assertExists()
        assertEquals(
            "business-collapsed-assistant-control",
            BusinessUiTags.COLLAPSED_ASSISTANT_CONTROL,
        )
        rule.onNodeWithTag(BusinessSidebarTags.ROOT).assertWidthIsEqualTo(210.dp)
        rule.onNodeWithTag(BusinessSidebarTags.DATA_ENTRY).assertExists()
        rule.onNodeWithTag(BusinessUiTags.BUSINESS_REGION).assertWidthIsEqualTo(866.dp)
        rule.onNodeWithTag(BusinessUiTags.FORM_PANEL).assertWidthIsEqualTo(866.dp)
        rule.onNodeWithTag(BusinessUiTags.COLLAPSED_ASSISTANT_CONTROL).assertWidthIsEqualTo(124.dp)
        rule.onNodeWithTag(BusinessAssistantChromeTags.MASCOT).assertExists()
        rule.onNodeWithTag("business-mascot-safe-area").assertDoesNotExist()
        rule.onNodeWithTag("business-agent-collapsed-rail").assertDoesNotExist()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertDoesNotExist()

        val topToolbar = bounds(BusinessTopNavigationTags.ROOT)
        val sidebar = bounds(BusinessSidebarTags.ROOT)
        val dock = bounds(BusinessUiTags.CONTENT)
        val business = bounds(BusinessUiTags.BUSINESS_REGION)
        val control = bounds(BusinessUiTags.COLLAPSED_ASSISTANT_CONTROL)
        val mascot = bounds(BusinessAssistantChromeTags.MASCOT)
        assertApproximately(topToolbar.bottom, sidebar.top)
        assertApproximately(sidebar.right, dock.left)
        assertApproximately(dock.left, business.left)
        assertApproximately(business.right, control.left)
        assertApproximately(dock.right, control.right)
        assertApproximately(dock.top, business.top)
        assertApproximately(dock.bottom, business.bottom)
        assertApproximately(dock.top, control.top)
        assertApproximately(dock.bottom, control.bottom)
        assertApproximately(dock.width, business.width + control.width)
        assertTrue(mascot.left >= control.left && mascot.right <= control.right)
        assertApproximately(control.bottom, mascot.bottom)
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
        val topToolbar = bounds(BusinessTopNavigationTags.ROOT)
        assertApproximately(sidebar.right, content.left)
        assertApproximately(content.left, business.left)
        assertApproximately(business.right, divider.left)
        assertApproximately(divider.right, assistant.left)
        assertApproximately(content.right, assistant.right)
        assertApproximately(topToolbar.width, sidebar.width + content.width)
        assertApproximately(content.width, business.width + divider.width + assistant.width)
        assertApproximately(640f * 0.75f, business.width, minimum = true)
        assertApproximately(8f * 0.75f, divider.width)
        assertApproximately(460f * 0.75f, assistant.width)

        rule.onNodeWithContentDescription("收回小律智能助手").performClick()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertDoesNotExist()
        rule.onNodeWithTag(BusinessUiTags.DIVIDER_SLOT).assertDoesNotExist()
        rule.onNodeWithTag(BusinessUiTags.COLLAPSED_ASSISTANT_CONTROL).assertWidthIsEqualTo(124.dp)
        assertApproximately(
            bounds(BusinessUiTags.CONTENT).width,
            bounds(BusinessUiTags.BUSINESS_REGION).width + bounds(BusinessUiTags.COLLAPSED_ASSISTANT_CONTROL).width,
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

        val topToolbar = bounds(BusinessTopNavigationTags.ROOT)
        val business = bounds(BusinessUiTags.BUSINESS_REGION)
        val control = bounds(BusinessUiTags.COLLAPSED_ASSISTANT_CONTROL)
        val message = bounds(BusinessUiTags.EXPAND_WIDTH_MESSAGE)
        val mascot = bounds(BusinessAssistantChromeTags.MASCOT)
        assertTrue(message.left >= control.left && message.right <= control.right)
        assertTrue(message.top >= control.top && message.bottom <= control.bottom)
        assertTrue(message.bottom <= mascot.top + 0.5f)
        assertFalse(message.overlaps(mascot))
        assertFalse(message.overlaps(business))
        assertFalse(message.overlaps(topToolbar))

        rule.runOnIdle { width.value = 1218.dp }
        rule.onNodeWithContentDescription("打开小律智能助手").performClick()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertWidthIsEqualTo(360.dp)
        rule.onNodeWithTag(BusinessUiTags.COLLAPSED_ASSISTANT_CONTROL).assertDoesNotExist()
        rule.onNodeWithTag(BusinessUiTags.EXPAND_WIDTH_MESSAGE).assertDoesNotExist()
        rule.runOnIdle { assertTrue(expanded.value) }
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

        rule.onNodeWithTag(BusinessTopNavigationTags.SETTINGS).performClick()
        rule.onNodeWithTag("provider-settings-panel").assertExists()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertWidthIsEqualTo(516.dp)
        rule.onNodeWithTag(BusinessSidebarTags.DATA_ENTRY).performClick()
        rule.onNodeWithTag(BusinessUiTags.FORM_PANEL).assertExists()
        rule.onNodeWithTag(BusinessUiTags.AGENT_PANEL).assertWidthIsEqualTo(516.dp)
        rule.runOnIdle { assertEquals(516.dp, requestedWidth.value) }
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
    fun `collapsed mascot never intersects form save or submit actions`() {
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
        val control = bounds(BusinessUiTags.COLLAPSED_ASSISTANT_CONTROL)
        val mascot = bounds(BusinessAssistantChromeTags.MASCOT)
        assertApproximately(business.right, control.left)
        assertTrue(form.right <= business.right + 0.5f)
        assertTrue(mascot.left >= control.left && mascot.right <= control.right)
        assertTrue(mascot.top >= control.top && mascot.bottom <= control.bottom)
        assertApproximately(control.bottom, mascot.bottom)
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

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

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
