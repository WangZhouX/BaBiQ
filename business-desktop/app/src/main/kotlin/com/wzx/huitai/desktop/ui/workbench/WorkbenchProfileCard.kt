package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 用户资料卡复刻 Web 的 92dp 蓝色背景头部和两行组织信息。
 */
@Composable
fun WorkbenchProfileCard(data: JsonElement?, modifier: Modifier = Modifier) {
    val profile = data as? JsonObject
    val name = profile?.text("nickname") ?: profile?.text("name") ?: "已登录用户"
    val tenant = profile.firstName("tenantList", "tenantName")
        ?: profile?.text("tenantName")
        ?: profile?.text("lawFirmName")
        ?: "-"
    val team = profile.firstName("teamList", "name")
        ?: profile?.text("teamName")
        ?: "-"
    Column(
        modifier
            .background(BusinessWorkbenchVisualSpec.surface)
            .testTag(WorkbenchTags.PROFILE),
    ) {
        Box(Modifier.fillMaxWidth().height(92.dp)) {
            Image(
                bitmap = BusinessWorkbenchAssets.profileBackgroundImage(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize(),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(Color(0xFFC7CBD2)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name.take(1),
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        name,
                        color = BusinessWorkbenchVisualSpec.textPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        membershipText(profile),
                        color = BusinessWorkbenchVisualSpec.textTertiary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OrganizationLine("任职律所：", tenant)
            OrganizationLine("加入团队：", team)
        }
    }
}

/** 组织信息行保持 Web 的灰色圆点、标签、值和蓝色查看入口。 */
@Composable
private fun OrganizationLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFFD9D9D9)))
        Text(
            label,
            color = BusinessWorkbenchVisualSpec.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
        Text(
            value,
            color = BusinessWorkbenchVisualSpec.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "查看",
            color = BusinessWorkbenchVisualSpec.primary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clearAndSetSemantics { contentDescription = "organization-view" },
        )
    }
}

private fun membershipText(profile: JsonObject?): String {
    val member = profile?.get("member") as? JsonObject
    val expire = member?.text("expireTime")
    val membershipStatus = profile?.text("membershipStatus")
    return when {
        !expire.isNullOrBlank() -> "会员有效期：${expire.take(10)}"
        !membershipStatus.isNullOrBlank() -> "会员状态：$membershipStatus"
        member?.boolean("exists") == true -> "会员权益已生效"
        else -> "会员信息待加载"
    }
}

private fun JsonObject?.firstName(listKey: String, nameKey: String): String? {
    val list = this?.get(listKey) as? JsonArray ?: return null
    return (list.firstOrNull() as? JsonObject)?.text(nameKey)
}
