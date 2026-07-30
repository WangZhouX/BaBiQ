package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.huitai.desktop.ui.brand.BusinessBrandResources
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 复刻 Web 的 64dp 顶部品牌栏。
 *
 * 顶栏只展示已经过本地 BFF 清洗的资料信息，不接收 OA Token 或远程请求地址。
 */
@Composable
fun WorkbenchHeader(
    profile: JsonElement? = null,
    notice: String? = null,
    onRefresh: () -> Unit = {},
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val data = profile as? JsonObject
    val userName = data?.text("nickname") ?: data?.text("name") ?: "已登录用户"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(BusinessWorkbenchVisualSpec.topHeaderHeight)
            .background(BusinessWorkbenchVisualSpec.surface)
            .padding(horizontal = 16.dp)
            .testTag(WorkbenchTags.HEADER),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            bitmap = BusinessBrandResources.logoImageBitmap(),
            contentDescription = "翔鸟律智",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(5.dp)),
        )
        Text(
            "翔鸟律智",
            color = BusinessWorkbenchVisualSpec.textPrimary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.size(48.dp))
        Text("▥", color = BusinessWorkbenchVisualSpec.primary, style = MaterialTheme.typography.titleMedium)
        Text(
            "智能律师办公平台",
            color = BusinessWorkbenchVisualSpec.textPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text("⌄", color = BusinessWorkbenchVisualSpec.textSecondary)
        Spacer(Modifier.weight(1f))
        notice?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                color = BusinessWorkbenchVisualSpec.warning,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onRefresh, modifier = Modifier.testTag(WorkbenchTags.REFRESH)) {
            Text("刷新", color = BusinessWorkbenchVisualSpec.primary)
        }
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(0xFFF0F2F5)),
            contentAlignment = Alignment.Center,
        ) {
            Text(userName.take(1), color = BusinessWorkbenchVisualSpec.textSecondary)
        }
        Text(userName, color = BusinessWorkbenchVisualSpec.textPrimary, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = onLogout) {
            Text("退出", color = BusinessWorkbenchVisualSpec.textSecondary)
        }
    }
}
