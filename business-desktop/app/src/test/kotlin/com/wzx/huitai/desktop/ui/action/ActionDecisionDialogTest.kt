package com.wzx.huitai.desktop.ui.action

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.desktop.decision.ActionDecisionDifference
import com.wzx.huitai.desktop.decision.ConfirmationDecisionDialogState
import com.wzx.huitai.desktop.decision.HighRiskApprovalDialogState
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import com.wzx.huitai.security.audit.AuditRedactor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ActionDecisionDialogTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `preview dialog renders structured safe differences and confirm is submitted once`() {
        var confirms = 0
        var cancels = 0
        rule.setContent {
            HuitaiBusinessTheme {
                ActionPreviewDialog(
                    state = confirmationState(),
                    onConfirm = { confirms += 1 },
                    onCancel = { cancels += 1 },
                )
            }
        }

        rule.onNodeWithTag("action-preview-dialog-execution-preview").assertExists()
        listOf(
            "确认动作预览",
            "通用资料更新",
            "来源：Agent 建议",
            "将更新两个通用字段",
            "资料名称",
            "变更前：旧名称",
            "变更后：新名称",
            "联系人",
            "变更前：${AuditRedactor.REDACTED}",
            "请核对字段差异",
        ).forEach { rule.onNodeWithText(it).assertExists() }
        listOf("raw-secret", "raw-user", "raw-tenant").forEach {
            rule.onAllNodesWithText(it, substring = true).assertCountEquals(0)
        }

        rule.onNodeWithTag("action-preview-confirm-execution-preview").performClick()
        rule.onNodeWithTag("action-preview-confirm-execution-preview").assertIsNotEnabled()
        rule.onNodeWithTag("action-preview-cancel-execution-preview").assertIsNotEnabled()
        rule.runOnIdle {
            assertEquals(1, confirms)
            assertEquals(0, cancels)
        }
    }

    @Test
    fun `preview cancel is a real single click and does not expose persistent permission controls`() {
        var cancels = 0
        rule.setContent {
            HuitaiBusinessTheme {
                ActionPreviewDialog(
                    state = confirmationState(),
                    onConfirm = {},
                    onCancel = { cancels += 1 },
                )
            }
        }

        rule.onNodeWithTag("action-preview-cancel-execution-preview").performClick()
        rule.onNodeWithTag("action-preview-cancel-execution-preview").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(1, cancels) }
        listOf("始终允许", "记住选择", "会话授权", "永久允许").forEach {
            rule.onAllNodesWithText(it, substring = true).assertCountEquals(0)
        }
    }

    @Test
    fun `high risk approval requires checked consent and renders only redacted identity and warnings`() {
        var approvals = 0
        var denials = 0
        rule.setContent {
            HuitaiBusinessTheme {
                HighRiskApprovalDialog(
                    state = approvalState(),
                    onApprove = { approvals += 1 },
                    onDeny = { denials += 1 },
                )
            }
        }

        rule.onNodeWithTag("high-risk-approval-dialog-execution-risk").assertExists()
        listOf(
            "高风险动作审批",
            "通用资料提交",
            "来源：用户操作",
            "将提交当前通用资料",
            "风险原因：会在远端产生提交副作用",
            "租户与用户已安全绑定（身份标识已隐藏）",
            "此动作可能在远端产生不可逆副作用，请核对后仅批准本次执行。",
            "我已核对差异，并仅批准本次执行",
        ).forEach { rule.onNodeWithText(it).assertExists() }
        listOf("raw-secret", "raw-user", "raw-tenant", "始终允许", "记住选择", "会话授权").forEach {
            rule.onAllNodesWithText(it, substring = true).assertCountEquals(0)
        }
        rule.onNodeWithContentDescription("仅批准本次高风险动作").assertExists()

        rule.onNodeWithTag("high-risk-approve-execution-risk").assertIsNotEnabled()
        rule.onNodeWithTag("high-risk-consent-execution-risk").performClick()
        rule.onNodeWithTag("high-risk-approve-execution-risk").assertIsEnabled().performClick()
        rule.onNodeWithTag("high-risk-approve-execution-risk").assertIsNotEnabled()
        rule.onNodeWithTag("high-risk-deny-execution-risk").assertIsNotEnabled()
        rule.runOnIdle {
            assertEquals(1, approvals)
            assertEquals(0, denials)
        }
    }

    @Test
    fun `high risk denial needs no consent and expired coordinator state removes dialog`() {
        var dialog by mutableStateOf<HighRiskApprovalDialogState?>(approvalState())
        var denials = 0
        rule.setContent {
            HuitaiBusinessTheme {
                dialog?.let { current ->
                    HighRiskApprovalDialog(
                        state = current,
                        onApprove = {},
                        onDeny = {
                            denials += 1
                            dialog = null
                        },
                    )
                }
            }
        }

        rule.onNodeWithTag("high-risk-deny-execution-risk").assertIsEnabled().performClick()
        rule.onNodeWithTag("high-risk-approval-dialog-execution-risk").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, denials) }

        rule.runOnIdle { dialog = approvalState() }
        rule.onNodeWithTag("high-risk-approval-dialog-execution-risk").assertExists()
        rule.runOnIdle { dialog = null }
        rule.onNodeWithTag("high-risk-approval-dialog-execution-risk").assertDoesNotExist()
    }

    @Test
    fun `long preview content scrolls to its last warning while action buttons remain reachable`() {
        val state = confirmationState().copy(
            executionId = "execution-long-preview",
            decisionId = "confirmation-long-preview",
            differences = (1..40).map { index ->
                ActionDecisionDifference("通用字段 $index", "旧值 $index", "新值 $index", redacted = false)
            },
            warnings = (1..20).map { index ->
                if (index == 20) "末项预览警告" else "预览警告 $index"
            },
        )
        rule.setContent {
            HuitaiBusinessTheme {
                ActionPreviewDialog(state, onConfirm = {}, onCancel = {})
            }
        }

        rule.onNodeWithTag("action-preview-scroll-execution-long-preview").assertExists()
        rule.onNodeWithText("末项预览警告").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("action-preview-confirm-execution-long-preview").assertIsDisplayed()
        rule.onNodeWithTag("action-preview-cancel-execution-long-preview").assertIsDisplayed()
    }

    @Test
    fun `long high risk content scrolls through final reason while approval controls remain reachable`() {
        val state = approvalState().copy(
            executionId = "execution-long-risk",
            decisionId = "approval-long-risk",
            differences = (1..30).map { index ->
                ActionDecisionDifference("审批字段 $index", "原值 $index", "目标值 $index", redacted = false)
            },
            warnings = (1..10).map { "审批警告 $it" },
            riskReasons = (1..20).map { index ->
                if (index == 20) "末项高风险原因" else "高风险原因 $index"
            },
        )
        rule.setContent {
            HuitaiBusinessTheme {
                HighRiskApprovalDialog(state, onApprove = {}, onDeny = {})
            }
        }

        rule.onNodeWithTag("high-risk-scroll-execution-long-risk").assertExists()
        rule.onNodeWithTag("high-risk-consent-execution-long-risk").assertIsDisplayed()
        rule.onNodeWithText("风险原因：末项高风险原因").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("high-risk-consent-execution-long-risk").assertIsDisplayed()
        rule.onNodeWithTag("high-risk-approve-execution-long-risk").assertIsDisplayed()
        rule.onNodeWithTag("high-risk-deny-execution-long-risk").assertIsDisplayed()
    }

    private fun confirmationState(): ConfirmationDecisionDialogState = ConfirmationDecisionDialogState(
        executionId = "execution-preview",
        decisionId = "confirmation-execution-preview",
        actionTitle = "通用资料更新",
        origin = ActionOrigin.AGENT,
        summary = "将更新两个通用字段",
        differences = differences(),
        warnings = listOf("请核对字段差异"),
        expiresAtEpochMillis = 1_800_000_000_000,
    )

    private fun approvalState(): HighRiskApprovalDialogState = HighRiskApprovalDialogState(
        executionId = "execution-risk",
        decisionId = "approval-execution-risk",
        actionTitle = "通用资料提交",
        origin = ActionOrigin.USER,
        summary = "将提交当前通用资料",
        differences = differences(),
        warnings = listOf("提交后请按执行编号核对远端状态"),
        expiresAtEpochMillis = 1_800_000_000_000,
        riskReasons = listOf("会在远端产生提交副作用"),
        identitySummary = "租户与用户已安全绑定（身份标识已隐藏）",
        remoteSideEffectWarning = "此动作可能在远端产生不可逆副作用，请核对后仅批准本次执行。",
    )

    private fun differences(): List<ActionDecisionDifference> = listOf(
        ActionDecisionDifference("资料名称", "旧名称", "新名称", redacted = false),
        ActionDecisionDifference(
            "联系人",
            AuditRedactor.REDACTED,
            AuditRedactor.REDACTED,
            redacted = true,
        ),
    )
}
