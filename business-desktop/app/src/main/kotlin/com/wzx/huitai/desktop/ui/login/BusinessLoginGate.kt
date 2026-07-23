package com.wzx.huitai.desktop.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wzx.huitai.desktop.auth.BusinessAccessGateState

object BusinessLoginGateTags {
    const val RECOVERY_PROGRESS = "business-login-recovery-progress"
    const val SIGNING_OUT_PROGRESS = "business-login-signing-out-progress"
}

/**
 * 唯一的业务桌面组合门禁。READY 之外不会执行 [ready] 内容，因此表单、审批框和 Agent composer
 * 都不会在未登录、恢复或退出阶段进入 Compose 树。
 */
@Composable
fun BusinessLoginGate(
    gate: BusinessAccessGateState,
    login: @Composable () -> Unit,
    ready: @Composable () -> Unit,
) {
    when (gate) {
        BusinessAccessGateState.READY -> ready()
        BusinessAccessGateState.STARTING,
        BusinessAccessGateState.RESTORING,
        -> GateProgress(
            message = "正在恢复登录状态…",
            tag = BusinessLoginGateTags.RECOVERY_PROGRESS,
        )
        BusinessAccessGateState.SIGNING_OUT -> GateProgress(
            message = "正在安全退出…",
            tag = BusinessLoginGateTags.SIGNING_OUT_PROGRESS,
        )
        BusinessAccessGateState.SIGNED_OUT,
        BusinessAccessGateState.VERIFYING,
        BusinessAccessGateState.AUTHENTICATING,
        BusinessAccessGateState.SELECTING_TENANT,
        BusinessAccessGateState.REGISTERING_AGENT,
        -> login()
    }
}

@Composable
private fun GateProgress(
    message: String,
    tag: String,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator()
            Text(message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
