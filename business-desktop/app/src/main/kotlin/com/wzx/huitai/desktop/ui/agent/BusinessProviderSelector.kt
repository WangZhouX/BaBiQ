package com.wzx.huitai.desktop.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.conversation.BusinessProvider
import com.wzx.huitai.agent.conversation.BusinessProviderModel

/** Provider 切换时优先选择服务端标记的 active 模型，否则稳定回退到首个模型。 */
internal fun defaultModelFor(provider: BusinessProvider): BusinessProviderModel? =
    provider.models.firstOrNull { it.active } ?: provider.models.firstOrNull()

/**
 * 只展示 Provider/模型公开标签并回调稳定 ID；认证模式、密钥和地址永不进入组件。
 */
@Composable
fun BusinessProviderSelector(
    providers: List<BusinessProvider>,
    activeProviderId: String?,
    selectedModelId: String?,
    onSelected: (providerId: String, modelId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    val activeProvider = providers.firstOrNull { it.id == activeProviderId } ?: providers.firstOrNull()
    val activeModel = activeProvider?.models?.firstOrNull { it.id == selectedModelId }
        ?: activeProvider?.models?.firstOrNull { it.active }
        ?: activeProvider?.models?.firstOrNull()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = { providerExpanded = true },
                enabled = providers.isNotEmpty(),
                modifier = Modifier.testTag("provider-selector"),
            ) {
                Text(activeProvider?.displayName ?: "选择 Provider")
            }
            DropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                providers.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.displayName) },
                        onClick = {
                            providerExpanded = false
                            defaultModelFor(provider)?.let { model -> onSelected(provider.id, model.id) }
                        },
                        modifier = Modifier.testTag("provider-option-${provider.id}"),
                    )
                }
            }
            TextButton(
                onClick = { modelExpanded = true },
                enabled = activeProvider?.models?.isNotEmpty() == true,
                modifier = Modifier.testTag("model-selector"),
            ) {
                Text(activeModel?.displayName ?: "选择模型")
            }
            DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                activeProvider?.models?.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.displayName) },
                        onClick = {
                            modelExpanded = false
                            onSelected(activeProvider.id, model.id)
                        },
                        modifier = Modifier.testTag("model-option-${model.id}"),
                    )
                }
            }
        }
        Text(
            "下轮对话生效",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
