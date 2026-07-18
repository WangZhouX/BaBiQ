package com.wzx.huitai.desktop.ui.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.wzx.huitai.presentation.form.FieldChange
import com.wzx.huitai.presentation.form.FormPatch
import com.wzx.huitai.presentation.form.SourceReference
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BusinessAgentPanelTest {
    @get:Rule
    val rule = createComposeRule()

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
    fun `composer enables only for connected authenticated identity with current thread`() {
        val uiState = mutableStateOf(composerState(BusinessAuthenticationStatus.SIGNED_OUT))
        rule.setContent {
            BusinessAgentPanel(state = uiState.value)
        }

        rule.onNodeWithTag("agent-composer-input").assertIsNotEnabled()
        listOf(
            composerState(BusinessAuthenticationStatus.EXPIRED, identity(), thread()),
            composerState(BusinessAuthenticationStatus.MEMBERSHIP_EXPIRED, identity(), thread()),
            composerState(BusinessAuthenticationStatus.AUTHENTICATED, identity = null, thread = thread()),
            composerState(BusinessAuthenticationStatus.AUTHENTICATED, identity = identity(), thread = null),
        ).forEach { invalid ->
            rule.runOnIdle { uiState.value = invalid }
            rule.onNodeWithTag("agent-composer-input").assertIsNotEnabled()
        }

        rule.runOnIdle {
            uiState.value = composerState(
                BusinessAuthenticationStatus.AUTHENTICATED,
                identity = identity(),
                thread = thread(),
            )
        }
        rule.onNodeWithTag("agent-composer-input").assertIsEnabled()
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
