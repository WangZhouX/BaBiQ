package com.wzx.huitai.desktop.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import com.wzx.huitai.agent.conversation.BusinessMessageAttachment
import java.util.Locale

/** 展示受连接状态控制的消息输入框，只通过回调提交用户输入。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentComposer(
    value: String,
    enabled: Boolean,
    submitting: Boolean = false,
    attachments: List<BusinessAttachmentDraft> = emptyList(),
    attachmentError: String? = null,
    onValueChanged: (String) -> Unit,
    onChooseFiles: () -> Unit = {},
    onPasteImage: () -> Boolean = { false },
    onRemoveAttachment: (String) -> Unit = {},
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .testTag(BusinessAssistantChromeTags.COMPOSER),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (attachments.isNotEmpty()) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 96.dp)
                    .clipToBounds()
                    .verticalScroll(rememberScrollState())
                    .testTag(BusinessAssistantChromeTags.ATTACHMENTS_CONTAINER),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                attachments.forEach { attachment ->
                    DraftAttachmentChip(attachment, onRemoveAttachment)
                }
            }
        }
        attachmentError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("agent-attachment-error"),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextButton(
                onClick = onChooseFiles,
                enabled = enabled,
                modifier = Modifier
                    .testTag("agent-composer-attach")
                    .semantics { contentDescription = "选择附件" },
            ) {
                Text("📎")
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChanged,
                enabled = enabled,
                placeholder = { Text("告诉 Agent 需要整理或修改的内容") },
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        handleComposerPasteKey(
                            isKeyDown = event.type == KeyEventType.KeyDown,
                            isCtrlPressed = event.isCtrlPressed,
                            isV = event.key == Key.V,
                            onPasteImage = onPasteImage,
                        )
                    }
                    .testTag("agent-composer-input"),
                minLines = 2,
                maxLines = 4,
            )
            Button(
                onClick = onSend,
                enabled = enabled && !submitting && (value.isNotBlank() || attachments.isNotEmpty()),
                modifier = Modifier.testTag("agent-composer-send"),
            ) {
                Text("发送")
            }
        }
    }
}

@Composable
private fun DraftAttachmentChip(
    attachment: BusinessAttachmentDraft,
    onRemoveAttachment: (String) -> Unit,
) {
    val formattedSize = formatAttachmentSize(attachment.sizeBytes)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .testTag("agent-attachment-${attachment.displayId}")
            .semantics {
                contentDescription =
                    "附件 ${attachment.displayId}，${attachment.name}，${attachment.displayType}，$formattedSize"
            },
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.widthIn(max = 240.dp)) {
                Text(
                    "${attachment.displayId} · ${attachment.name}",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${attachment.displayType} · $formattedSize",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { onRemoveAttachment(attachment.id) },
                modifier = Modifier.semantics {
                    contentDescription = "移除附件 ${attachment.displayId}"
                },
            ) {
                Text("×")
            }
        }
    }
}

@Composable
fun MessageAttachmentChip(attachment: BusinessMessageAttachment) {
    val formattedSize = formatAttachmentSize(attachment.sizeBytes)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .testTag("agent-message-attachment-${attachment.displayId}")
            .semantics {
                contentDescription =
                    "附件 ${attachment.displayId}，${attachment.name}，${attachment.mediaType}，$formattedSize"
            },
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                "${attachment.displayId} · ${attachment.name}",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${attachment.mediaType} · $formattedSize",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun formatAttachmentSize(sizeBytes: Long): String = when {
    sizeBytes < 1024 -> "$sizeBytes B"
    sizeBytes < 1024L * 1024 ->
        String.format(Locale.ROOT, "%.1f KiB", sizeBytes / 1024.0)
    else ->
        String.format(Locale.ROOT, "%.1f MiB", sizeBytes / (1024.0 * 1024.0))
}

internal fun handleComposerPasteKey(
    isKeyDown: Boolean,
    isCtrlPressed: Boolean,
    isV: Boolean,
    onPasteImage: () -> Boolean,
): Boolean =
    if (isKeyDown && isCtrlPressed && isV) onPasteImage() else false
