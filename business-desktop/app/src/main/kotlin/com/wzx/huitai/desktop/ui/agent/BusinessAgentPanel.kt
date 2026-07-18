package com.wzx.huitai.desktop.ui.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.conversation.BusinessThreadItem
import com.wzx.huitai.desktop.state.BusinessConnectionStatus
import com.wzx.huitai.desktop.state.BusinessDesktopState
import com.wzx.huitai.desktop.ui.common.ConnectionBanner
import com.wzx.huitai.desktop.ui.shell.BusinessUiTags
import com.wzx.huitai.presentation.form.FormPatch
import java.util.Locale

/**
 * 业务 Agent 的纯展示面板。
 *
 * 面板只消费 Task 31 的不可变状态和回调，不执行协议分发、ActionBus 调用或持久化。
 */
@Composable
fun BusinessAgentPanel(
    state: BusinessDesktopState,
    formPatch: FormPatch? = null,
    selectedModelId: String? = null,
    composerText: String = "",
    onComposerTextChanged: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onReconnect: () -> Unit = {},
    onProviderSelected: (providerId: String, modelId: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize().testTag(BusinessUiTags.AGENT_PANEL),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("业务 Agent", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                ConnectionBanner(state.connectionStatus, onReconnect)
                BusinessProviderSelector(
                    providers = state.providers,
                    activeProviderId = state.activeProviderId,
                    selectedModelId = selectedModelId,
                    onSelected = onProviderSelected,
                )
            }
            HorizontalDivider()
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.messages.forEach { item ->
                    when (item) {
                        is BusinessThreadItem.UserMessage -> MessageCard(item.text, user = true)
                        is BusinessThreadItem.AgentMessage -> MessageCard(item.text.orEmpty() + item.textDelta.orEmpty(), user = false)
                        is BusinessThreadItem.Reasoning -> ReasoningCard(item)
                        else -> Unit
                    }
                }
                state.plan?.let { AgentPlanCard(it) }
                formPatch?.let { FormPatchCard(it) }
                state.applicationActions.values.forEach { ApplicationActionProgressCard(it) }
                state.turnSummary?.let { TurnSummaryCard(it) }
                state.error?.let {
                    Text(
                        it.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("business-error"),
                    )
                }
                if (state.unknownEventCount > 0) {
                    Text(
                        "未知类型事件 · ${state.unknownEventCount} 条（内容已安全忽略）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("unknown-event-diagnostic"),
                    )
                }
            }
            HorizontalDivider()
            AgentComposer(
                value = composerText,
                enabled = state.connectionStatus == BusinessConnectionStatus.CONNECTED,
                onValueChanged = onComposerTextChanged,
                onSend = onSend,
            )
        }
    }
}

/** 渲染用户或 Agent 的一条普通消息。 */
@Composable
private fun MessageCard(text: String, user: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (user) "用户" else "Agent", style = MaterialTheme.typography.labelSmall)
            Text(text)
        }
    }
}

/** reasoning 默认折叠，且只在用户主动展开时显示正文。 */
@Composable
private fun ReasoningCard(item: BusinessThreadItem.Reasoning) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .testTag("reasoning-${item.id}"),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("思考过程", style = MaterialTheme.typography.labelLarge)
            if (expanded) Text(item.text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** TurnSummary 严格只展示 token、耗时和工具次数。 */
@Composable
private fun TurnSummaryCard(summary: BusinessThreadItem.TurnSummary) {
    val seconds = String.format(Locale.ROOT, "%.1f", summary.durationMs / 1000.0)
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("turn-summary"),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Tokens ${summary.totalTokens}（输入 ${summary.promptTokens} / 输出 ${summary.completionTokens}）")
            Text("耗时 $seconds 秒 · 工具 ${summary.toolCalls} 次")
        }
    }
}
