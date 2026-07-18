package com.wzx.huitai.desktop.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wzx.huitai.desktop.state.BusinessConnectionStatus

/** 显示连接异常及明确重连入口；已连接状态不占据面板空间。 */
@Composable
fun ConnectionBanner(
    status: BusinessConnectionStatus,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (status == BusinessConnectionStatus.CONNECTED) return
    val (message, canRetry) = when (status) {
        BusinessConnectionStatus.DISCONNECTED -> "连接已断开" to true
        BusinessConnectionStatus.CONNECTING -> "正在连接" to false
        BusinessConnectionStatus.RECONNECTING -> "正在重新连接" to false
        BusinessConnectionStatus.MANUAL_RETRY_REQUIRED -> "自动重连已停止，请手动重试" to true
        BusinessConnectionStatus.AUTHENTICATION_FAILED -> "身份校验失败，请重新连接" to true
        BusinessConnectionStatus.SHUTDOWN -> "桌面服务已关闭" to false
        BusinessConnectionStatus.CONNECTED -> return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
        if (canRetry) {
            TextButton(onClick = onReconnect, modifier = Modifier.testTag("reconnect-action")) {
                Text("重新连接")
            }
        }
    }
}
