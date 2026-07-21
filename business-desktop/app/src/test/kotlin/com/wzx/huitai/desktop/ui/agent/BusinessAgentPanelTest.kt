package com.wzx.huitai.desktop.ui.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import com.wzx.huitai.agent.conversation.BusinessMessageAttachment
import com.wzx.huitai.agent.conversation.BusinessPlanStep
import com.wzx.huitai.agent.conversation.BusinessProvider
import com.wzx.huitai.agent.conversation.BusinessProviderModel
import com.wzx.huitai.agent.conversation.BusinessThreadItem
import com.wzx.huitai.agent.conversation.BusinessThread
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.desktop.state.BusinessConnectionStatus
import com.wzx.huitai.desktop.state.BusinessAuthenticationStatus
import com.wzx.huitai.desktop.state.BusinessDesktopError
import com.wzx.huitai.desktop.state.BusinessDesktopState
import com.wzx.huitai.desktop.state.BusinessIdentity
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import com.wzx.huitai.presentation.form.FieldChange
import com.wzx.huitai.presentation.form.FormPatch
import com.wzx.huitai.presentation.form.SourceReference
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class BusinessAgentPanelTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `expanded panel exposes dedicated mascot slot without legacy collapse action`() {
        var toggleCount = 0
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessAgentPanel(
                    state = composerState(
                        BusinessAuthenticationStatus.AUTHENTICATED,
                        identity = identity(),
                    ),
                    mascot = {
                        BusinessAssistantMascotButton(
                            expanded = true,
                            onToggle = { toggleCount += 1 },
                        )
                    },
                    modifier = Modifier.requiredSize(460.dp, 900.dp),
                )
            }
        }

        rule.onNodeWithText("小律智能助手").assertExists()
        rule.onNodeWithContentDescription("收起业务 Agent").assertDoesNotExist()
        val slot = rule.onNodeWithTag(BusinessAssistantChromeTags.MASCOT_SLOT)
            .fetchSemanticsNode().boundsInRoot
        val mascot = rule.onNodeWithTag(BusinessAssistantChromeTags.MASCOT)
            .fetchSemanticsNode().boundsInRoot
        val messages = rule.onNodeWithTag("business-agent-messages").fetchSemanticsNode().boundsInRoot
        val composer = rule.onNodeWithTag("agent-composer-root").fetchSemanticsNode().boundsInRoot
        val input = rule.onNodeWithTag("agent-composer-input").fetchSemanticsNode().boundsInRoot
        val send = rule.onNodeWithTag("agent-composer-send").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(messages.bottom <= slot.top)
        org.junit.Assert.assertTrue(slot.bottom <= composer.top)
        org.junit.Assert.assertTrue(mascot.left >= slot.left && mascot.right <= slot.right)
        org.junit.Assert.assertTrue(mascot.top >= slot.top && mascot.bottom <= slot.bottom)
        org.junit.Assert.assertFalse(mascot.overlaps(input))
        org.junit.Assert.assertFalse(mascot.overlaps(send))

        rule.onNodeWithContentDescription("收回小律智能助手").performClick()
        assertEquals(1, toggleCount)
    }

    @Test
    fun `mascot slot stays disjoint from attachment error chip and multiline composer`() {
        val attachment = draftAttachment()
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessAgentPanel(
                    state = composerState(
                        BusinessAuthenticationStatus.AUTHENTICATED,
                        identity = identity(),
                    ),
                    composerText = "第一行\n第二行\n第三行\n第四行\n第五行",
                    composerAttachments = listOf(attachment),
                    attachmentError = "ATTACHMENT_TOTAL_TOO_LARGE: 附件总大小超过限制",
                    mascot = {
                        BusinessAssistantMascotButton(expanded = true, onToggle = {})
                    },
                    modifier = Modifier.requiredSize(460.dp, 1000.dp),
                )
            }
        }

        val mascot = rule.onNodeWithTag(BusinessAssistantChromeTags.MASCOT)
            .fetchSemanticsNode().boundsInRoot
        listOf(
            "agent-composer-input",
            "agent-composer-attach",
            "agent-composer-send",
            "agent-attachment-${attachment.displayId}",
            "agent-attachment-error",
        ).forEach { tag ->
            val target = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertFalse("mascot must not overlap $tag", mascot.overlaps(target))
        }
    }

    @Test
    fun `minimum height keeps many attachments error multiline input and actions inside panel`() {
        val displayIds = listOf(
            "A-BCDEFG",
            "A-BCDEFH",
            "A-BCDEFJ",
            "A-BCDEFK",
            "A-BCDEFM",
            "A-BCDEFN",
            "A-BCDEFP",
            "A-BCDEFQ",
        )
        val attachments = displayIds.mapIndexed { index, displayId ->
            draftAttachment().copy(
                id = "00000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}",
                displayId = displayId,
                name = "第${index + 1}份-${"很长的业务资料文件名".repeat(8)}.pdf",
            )
        }
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessAgentPanel(
                    state = composerState(
                        BusinessAuthenticationStatus.AUTHENTICATED,
                        identity = identity(),
                    ),
                    composerText = "第一行\n第二行\n第三行\n第四行",
                    composerAttachments = attachments,
                    attachmentError = "ATTACHMENT_TOTAL_TOO_LARGE: 附件总大小超过限制，请删除不需要的文件后重试",
                    mascot = {
                        BusinessAssistantMascotButton(expanded = true, onToggle = {})
                    },
                    modifier = Modifier.requiredSize(460.dp, 656.dp),
                )
            }
        }

        fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        fun assertInside(childTag: String, parentBounds: androidx.compose.ui.geometry.Rect) {
            val child = bounds(childTag)
            org.junit.Assert.assertTrue("$childTag left escaped", child.left >= parentBounds.left)
            org.junit.Assert.assertTrue("$childTag top escaped", child.top >= parentBounds.top)
            org.junit.Assert.assertTrue("$childTag right escaped", child.right <= parentBounds.right)
            org.junit.Assert.assertTrue("$childTag bottom escaped", child.bottom <= parentBounds.bottom)
        }

        val attachmentsTag = BusinessAssistantChromeTags.ATTACHMENTS_CONTAINER
        val panel = bounds(com.wzx.huitai.desktop.ui.shell.BusinessUiTags.AGENT_PANEL)
        val composer = bounds(BusinessAssistantChromeTags.COMPOSER)
        val mascotSlot = bounds(BusinessAssistantChromeTags.MASCOT_SLOT)
        val attachmentsContainer = bounds(attachmentsTag)
        rule.onNodeWithTag(attachmentsTag).assertHeightIsEqualTo(96.dp)
        listOf(
            BusinessAssistantChromeTags.COMPOSER,
            BusinessAssistantChromeTags.MASCOT_SLOT,
            BusinessAssistantChromeTags.MASCOT,
            attachmentsTag,
            "agent-attachment-error",
            "agent-composer-input",
            "agent-composer-attach",
            "agent-composer-send",
        ).forEach { assertInside(it, panel) }
        listOf(
            attachmentsTag,
            "agent-attachment-error",
            "agent-composer-input",
            "agent-composer-attach",
            "agent-composer-send",
        ).forEach { assertInside(it, composer) }
        org.junit.Assert.assertTrue(mascotSlot.bottom <= composer.top)
        org.junit.Assert.assertFalse(bounds("agent-composer-attach").overlaps(bounds("agent-composer-input")))
        org.junit.Assert.assertFalse(bounds("agent-composer-input").overlaps(bounds("agent-composer-send")))
        org.junit.Assert.assertTrue(attachmentsContainer.bottom <= bounds("agent-attachment-error").top)

        val lastAttachment = attachments.last()
        rule.onNodeWithTag("agent-attachment-${lastAttachment.displayId}").performScrollTo()
        assertInside("agent-attachment-${lastAttachment.displayId}", attachmentsContainer)
        assertInside("agent-composer-attach", panel)
        assertInside("agent-composer-send", panel)
    }

    @Test
    fun `renders conversation collapsed reasoning current plan actions summary and safe diagnostics`() {
        rule.setContent {
            BusinessAgentPanel(
                state = richState(),
                formPatch = patch(),
            )
        }

        rule.onNodeWithText("请整理这段资料").assertExists()
        rule.onNodeWithText("已生成通用字段建议").assertExists()
        rule.onNodeWithText("思考过程").assertExists()
        rule.onNodeWithText("内部分析内容").assertDoesNotExist()
        rule.onNodeWithTag("reasoning-reason-1").performClick()
        rule.onNodeWithText("内部分析内容").assertExists()

        rule.onNodeWithTag("agent-plan").performScrollTo()
        rule.onNodeWithText("核对资料", substring = true).assertExists()
        rule.onNodeWithText("等待确认").assertExists()
        listOf(
            "requested",
            "accepted",
            "previewed",
            "approval",
            "executing",
            "succeeded",
            "completed",
            "failed",
        ).forEach { executionId ->
            rule.onNodeWithTag("application-action-execution-$executionId").performScrollTo()
        }
        mapOf(
            "已接收" to 2,
            "预览中" to 1,
            "等待审批" to 1,
            "执行中" to 1,
            "已完成" to 2,
            "失败" to 1,
        ).forEach { (label, expectedCount) ->
            rule.onAllNodesWithText(label).assertCountEquals(expectedCount)
        }
        rule.onNodeWithTag("application-action-execution-unknown").performScrollTo()
        rule.onNodeWithText("结果未知，请先按执行编号对账，确认远端结果后再决定是否重试。", substring = true).assertExists()

        rule.onNodeWithTag("form-patch-card").performScrollTo()
        listOf("旧值：未命名资料", "新值：建议名称", "原因：从用户输入中识别", "置信度 92%", "来源：用户输入").forEach {
            rule.onNodeWithText(it).assertExists()
        }
        rule.onNodeWithTag("turn-summary").performScrollTo()
        rule.onNodeWithText("Tokens 30（输入 10 / 输出 20）").assertExists()
        rule.onNodeWithText("耗时 1.2 秒 · 工具 2 次").assertExists()
        rule.onNodeWithTag("business-error").performScrollTo()
        rule.onNodeWithText("普通错误说明").assertExists()
        rule.onNodeWithTag("unknown-event-diagnostic").performScrollTo()
        rule.onNodeWithText("未知类型事件 · 2 条（内容已安全忽略）").assertExists()

        listOf("cost", "price", "API key", "baseUrl", "secret").forEach {
            rule.onAllNodesWithText(it, substring = true, ignoreCase = true).assertCountEquals(0)
        }
    }

    @Test
    fun `disconnected panel disables composer reconnects and provider selection exposes ids only`() {
        var reconnectCount = 0
        val selections = mutableListOf<Pair<String, String>>()
        rule.setContent {
            var state by remember { mutableStateOf(richState()) }
            var selectedModel by remember { mutableStateOf("model-a") }
            BusinessAgentPanel(
                state = state,
                selectedModelId = selectedModel,
                onReconnect = { reconnectCount += 1 },
                onProviderSelected = { providerId, modelId ->
                    selections += providerId to modelId
                    state = state.copy(activeProviderId = providerId)
                    selectedModel = modelId
                },
            )
        }

        rule.onNodeWithText("连接已断开").assertExists()
        rule.onNodeWithTag("agent-composer-input").assertIsNotEnabled()
        rule.onNodeWithTag("reconnect-action").performClick()
        assertEquals(1, reconnectCount)

        rule.onNodeWithTag("provider-selector").performClick()
        rule.onNodeWithTag("provider-option-provider-b").performClick()
        assertEquals("provider-b" to "model-b-active", selections.last())
        rule.onNodeWithTag("model-selector").performClick()
        rule.onNodeWithTag("model-option-model-b-first").performClick()
        assertEquals("provider-b" to "model-b-first", selections.last())
        rule.onNodeWithTag("provider-selector").performClick()
        rule.onNodeWithTag("provider-option-provider-c").performClick()
        assertEquals("provider-c" to "model-c-first", selections.last())
        rule.onNodeWithText("下轮对话生效").assertExists()
        rule.onAllNodesWithText("super-secret-key", substring = true).assertCountEquals(0)
    }

    @Test
    fun `composer enables for connected authenticated identity before the first thread exists`() {
        val uiState = mutableStateOf(composerState(BusinessAuthenticationStatus.SIGNED_OUT))
        rule.setContent {
            BusinessAgentPanel(state = uiState.value)
        }

        rule.onNodeWithTag("agent-composer-input").assertIsNotEnabled()
        listOf(
            composerState(BusinessAuthenticationStatus.EXPIRED, identity(), thread()),
            composerState(BusinessAuthenticationStatus.MEMBERSHIP_EXPIRED, identity(), thread()),
            composerState(BusinessAuthenticationStatus.AUTHENTICATED, identity = null, thread = thread()),
        ).forEach { invalid ->
            rule.runOnIdle { uiState.value = invalid }
            rule.onNodeWithTag("agent-composer-input").assertIsNotEnabled()
        }

        rule.runOnIdle {
            uiState.value = composerState(
                BusinessAuthenticationStatus.AUTHENTICATED,
                identity = identity(),
                thread = null,
            )
        }
        rule.onNodeWithTag("agent-composer-input").assertIsEnabled()
    }

    @Test
    fun `composer shows path free removable attachment chips and enables attachment only send`() {
        val attachment = draftAttachment()
        var chooseCount = 0
        var removed: String? = null
        var sent = false
        rule.setContent {
            BusinessAgentPanel(
                state = composerState(
                    BusinessAuthenticationStatus.AUTHENTICATED,
                    identity = identity(),
                ),
                composerAttachments = listOf(attachment),
                attachmentError = "ATTACHMENT_TOTAL_TOO_LARGE: 附件总大小超过 50 MiB 限制",
                onChooseFiles = { chooseCount++ },
                onRemoveAttachment = { removed = it },
                onSend = { sent = true },
            )
        }

        rule.onNodeWithTag("agent-composer-attach").performClick()
        assertEquals(1, chooseCount)
        rule.onNodeWithTag("agent-attachment-${attachment.displayId}").assertExists()
        listOf(attachment.displayId, attachment.name, attachment.displayType, "2.0 KiB").forEach {
            rule.onNodeWithText(it, substring = true).assertExists()
        }
        rule.onAllNodesWithText(attachment.localPath, substring = true).assertCountEquals(0)
        rule.onNodeWithTag("agent-attachment-error").assertExists()
        rule.onNodeWithText("ATTACHMENT_TOTAL_TOO_LARGE", substring = true).assertExists()
        rule.onNodeWithTag("agent-composer-send").assertIsEnabled().performClick()
        org.junit.Assert.assertTrue(sent)
        rule.onNodeWithContentDescription("移除附件 ${attachment.displayId}").performClick()
        assertEquals(attachment.id, removed)
    }

    @Test
    fun `submitting disables send and long filenames keep a full accessible description`() {
        val longName = "a".repeat(251) + ".pdf"
        val attachment = draftAttachment().copy(name = longName)
        rule.setContent {
            BusinessAgentPanel(
                state = composerState(
                    BusinessAuthenticationStatus.AUTHENTICATED,
                    identity = identity(),
                ),
                composerText = "send once",
                composerAttachments = listOf(attachment),
                composerSubmitting = true,
            )
        }

        rule.onNodeWithTag("agent-composer-send").assertIsNotEnabled()
        rule.onNodeWithTag("agent-attachment-${attachment.displayId}")
            .assertContentDescriptionEquals(
                "附件 ${attachment.displayId}，$longName，${attachment.displayType}，2.0 KiB",
            )
    }

    @Test
    fun `long attachment filenames keep remove action measurable and clickable`() {
        val attachment = draftAttachment().copy(name = "a".repeat(251) + ".pdf")
        var removed: String? = null
        rule.setContent {
            BusinessAgentPanel(
                state = composerState(
                    BusinessAuthenticationStatus.AUTHENTICATED,
                    identity = identity(),
                ),
                composerAttachments = listOf(attachment),
                onRemoveAttachment = { removed = it },
            )
        }

        val removeAction =
            rule.onNodeWithContentDescription("移除附件 ${attachment.displayId}")
        val bounds = removeAction.fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("remove action must retain positive bounds", bounds.width > 0f && bounds.height > 0f)
        removeAction.performClick()
        assertEquals(attachment.id, removed)
    }

    @Test
    fun `composer handles one key down ctrl v and delegates ordinary paste when clipboard has no image`() {
        var calls = 0
        var captured = true
        rule.setContent {
            BusinessAgentPanel(
                state = composerState(
                    BusinessAuthenticationStatus.AUTHENTICATED,
                    identity = identity(),
                ),
                onPasteImage = {
                    calls++
                    captured
                },
            )
        }

        rule.onNodeWithTag("agent-composer-input").performClick().performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.V)
            keyUp(Key.CtrlLeft)
        }
        assertEquals(1, calls)

        captured = false
        assertFalse(
            handleComposerPasteKey(
                isKeyDown = true,
                isCtrlPressed = true,
                isV = true,
                onPasteImage = {
                    calls++
                    captured
                },
            ),
        )
        assertEquals(2, calls)
    }

    @Test
    fun `persisted user message renders stable path free attachment metadata`() {
        val attachment = messageAttachment()
        rule.setContent {
            BusinessAgentPanel(
                state = BusinessDesktopState(
                    messages = listOf(
                        BusinessThreadItem.UserMessage(
                            id = "user-with-file",
                            text = "",
                            attachments = listOf(attachment),
                        ),
                    ),
                ),
            )
        }

        rule.onNodeWithTag("agent-message-attachment-${attachment.displayId}").assertExists()
        listOf(attachment.displayId, attachment.name, attachment.mediaType, "2.0 KiB").forEach {
            rule.onNodeWithText(it, substring = true).assertExists()
        }
        rule.onAllNodesWithText(attachment.localPath, substring = true).assertCountEquals(0)
    }

    private fun richState(): BusinessDesktopState = BusinessDesktopState(
        connectionStatus = BusinessConnectionStatus.DISCONNECTED,
        messages = listOf(
            BusinessThreadItem.UserMessage("user-1", "请整理这段资料"),
            BusinessThreadItem.AgentMessage("agent-1", text = "已生成通用字段建议"),
            BusinessThreadItem.Reasoning("reason-1", "内部分析内容"),
        ),
        plan = BusinessThreadItem.Plan(
            id = "plan-current",
            goal = "整理资料",
            steps = listOf(BusinessPlanStep(1, "核对资料", "pending")),
        ),
        applicationActions = linkedMapOf(
            "requested" to action("requested", "requested"),
            "accepted" to action("accepted", "accepted"),
            "previewed" to action("previewed", "previewed"),
            "approval" to action("approval", "approval_required"),
            "executing" to action("executing", "executing"),
            "succeeded" to action("succeeded", "succeeded"),
            "completed" to action("completed", "completed"),
            "failed" to action("failed", "failed"),
            "unknown" to action("unknown", "OUTCOME_UNKNOWN"),
        ),
        turnSummary = BusinessThreadItem.TurnSummary(
            id = "summary-1",
            status = "completed",
            model = "model-a",
            promptTokens = 10,
            completionTokens = 20,
            totalTokens = 30,
            toolCalls = 2,
            durationMs = 1_200,
        ),
        providers = listOf(
            BusinessProvider(
                id = "provider-a",
                displayName = "通用模型 A",
                models = listOf(BusinessProviderModel("model-a", "模型 A", active = true)),
                authMode = "api_key",
                hasApiKey = true,
                active = true,
            ),
            BusinessProvider(
                id = "provider-b",
                displayName = "通用模型 B",
                models = listOf(
                    BusinessProviderModel("model-b-first", "模型 B1"),
                    BusinessProviderModel("model-b-active", "模型 B2", active = true),
                ),
                authMode = "api_key",
                hasApiKey = true,
                active = false,
            ),
            BusinessProvider(
                id = "provider-c",
                displayName = "通用模型 C",
                models = listOf(
                    BusinessProviderModel("model-c-first", "模型 C1"),
                    BusinessProviderModel("model-c-second", "模型 C2"),
                ),
                authMode = "api_key",
                hasApiKey = true,
                active = false,
            ),
        ),
        activeProviderId = "provider-a",
        error = BusinessDesktopError("GENERIC", "普通错误说明"),
        unknownEventCount = 2,
    )

    private fun action(id: String, status: String): BusinessThreadItem.ApplicationAction =
        BusinessThreadItem.ApplicationAction(
            id = "action-$id",
            executionId = "execution-$id",
            actionId = "form.apply_patch",
            title = "通用动作 $id",
            risk = "SAFE",
            status = status,
        )

    private fun composerState(
        authenticationStatus: BusinessAuthenticationStatus,
        identity: BusinessIdentity? = null,
        thread: BusinessThread? = null,
    ): BusinessDesktopState = BusinessDesktopState(
        connectionStatus = BusinessConnectionStatus.CONNECTED,
        authenticationStatus = authenticationStatus,
        identity = identity,
        currentThread = thread,
    )

    private fun identity(): BusinessIdentity = BusinessIdentity(
        desktopInstanceId = "desktop-instance",
        desktopSessionId = "desktop-session",
        authSessionId = "auth-session",
        identityEpoch = 1,
        userId = "user-1",
        tenantId = "tenant-1",
        platformId = "platform-1",
        roles = emptySet(),
        permissions = emptySet(),
    )

    private fun thread(): BusinessThread = BusinessThread("thread-1", "通用会话", "E:/workspace")

    private fun draftAttachment(): BusinessAttachmentDraft = BusinessAttachmentDraft(
        id = "00000000-0000-0000-0000-000000000301",
        displayId = "A-BCDEFG",
        name = "合同.pdf",
        localPath = "C:/private/customer/合同.pdf",
        sizeBytes = 2_048,
        displayType = "PDF",
    )

    private fun messageAttachment(): BusinessMessageAttachment = BusinessMessageAttachment(
        id = "00000000-0000-0000-0000-000000000302",
        displayId = "A-HJKLMN",
        name = "截图.png",
        mediaType = "image/png",
        sizeBytes = 2_048,
        sha256 = "a".repeat(64),
        source = "CLIPBOARD_IMAGE",
        localPath = "C:/private/customer/截图.png",
    )

    private fun patch(): FormPatch = FormPatch(
        pageId = DemoFormState.PAGE_ID,
        baseRevision = 7,
        changes = listOf(
            FieldChange(
                fieldId = DemoFormState.FIELD_NAME,
                previousValue = JsonPrimitive("未命名资料"),
                newValue = JsonPrimitive("建议名称"),
                reason = "从用户输入中识别",
                confidence = 0.92,
                sourceReferences = listOf(SourceReference("user-input", "message-1", "用户输入")),
            ),
        ),
    )
}
