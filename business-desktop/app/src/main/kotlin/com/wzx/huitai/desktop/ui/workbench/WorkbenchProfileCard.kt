package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Composable
fun WorkbenchProfileCard(data: JsonElement?, modifier: Modifier = Modifier) {
    val profile = data as? JsonObject
    Card(modifier.testTag(WorkbenchTags.PROFILE)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("用户信息")
            Text(profile?.text("nickname") ?: profile?.text("name") ?: "已登录用户")
            profile?.text("tenantName")?.let { Text(it) }
            profile?.text("membershipStatus")?.let { Text("会员状态：$it") }
        }
    }
}
