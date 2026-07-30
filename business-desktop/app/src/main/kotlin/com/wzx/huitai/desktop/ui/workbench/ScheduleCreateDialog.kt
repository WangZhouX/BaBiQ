package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wzx.huitai.desktop.workbench.BusinessScheduleDraft
import com.wzx.huitai.desktop.workbench.BusinessScheduleFormOption
import com.wzx.huitai.desktop.workbench.BusinessScheduleFormState
import com.wzx.huitai.desktop.workbench.BusinessScheduleRelationType
import com.wzx.huitai.desktop.workbench.BusinessAttachmentUploadState
import com.wzx.huitai.agent.business.workbench.BusinessScheduleRelation
import kotlin.math.roundToInt

object ScheduleCreateTags {
    const val TITLE = "schedule-create-title"
    const val DESCRIPTION = "schedule-create-description"
    const val CUSTOM_REMINDER_DAYS = "schedule-create-custom-reminder-days"
    const val CUSTOM_REMINDER_HOURS = "schedule-create-custom-reminder-hours"
    const val CUSTOM_REMINDER_MINUTES = "schedule-create-custom-reminder-minutes"
    const val CUSTOM_REMINDER_TIME = "schedule-create-custom-reminder-time"
    const val CUSTOM_REMINDER_ERROR = "schedule-create-custom-reminder-error"
    const val ALL_DAY = "schedule-create-all-day"
    const val ASSIGNEE = "schedule-create-assignee"
    const val CHOOSE_ATTACHMENTS = "schedule-create-choose-attachments"
    const val UPLOAD_PROGRESS = "schedule-create-upload-progress"
    const val CANCEL_UPLOAD = "schedule-create-cancel-upload"
    const val SUBMIT = "schedule-create-submit"
    const val DISMISS = "schedule-create-dismiss"

    fun relation(type: BusinessScheduleRelationType) = "schedule-create-relation-${type.name}"
    fun relationOption(type: BusinessScheduleRelationType, id: String) =
        "schedule-create-relation-option-${type.name}-$id"
    fun type(id: String) = "schedule-create-type-$id"
    fun priority(value: Int) = "schedule-create-priority-$value"
    fun reminder(value: Int) = "schedule-create-reminder-$value"
    fun repeat(rule: String) = "schedule-create-repeat-$rule"
}

@Composable
fun ScheduleCreateDialog(
    state: BusinessScheduleFormState,
    uploadState: BusinessAttachmentUploadState = BusinessAttachmentUploadState(),
    onDraftChanged: (BusinessScheduleDraft) -> Unit,
    onRelationTypeSelected: (BusinessScheduleRelationType) -> Unit,
    onRelationOptionSelected: ((BusinessScheduleRelationType, BusinessScheduleFormOption) -> Unit)? = null,
    onLoadRelationOptions: () -> Unit,
    onChooseAttachments: () -> Unit,
    onCancelUpload: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return
    var customDays by remember(state.draft.clientOperationId, state.draft.allDay) { mutableStateOf("") }
    var customHours by remember(state.draft.clientOperationId, state.draft.allDay) { mutableStateOf("") }
    var customMinutes by remember(state.draft.clientOperationId, state.draft.allDay) { mutableStateOf("") }
    var customTime by remember(state.draft.clientOperationId, state.draft.allDay) { mutableStateOf("") }
    var customReminderInvalid by remember(state.draft.clientOperationId, state.draft.allDay) {
        mutableStateOf(false)
    }
    val presetReminders =
        if (state.draft.allDay) ALL_DAY_PRESET_REMINDERS else TIMED_PRESET_REMINDERS
    var selectedPresetReminders by remember(state.draft.clientOperationId, state.draft.allDay) {
        mutableStateOf(state.draft.reminderMinutes.filter { it in presetReminders }.toSet())
    }
    var customReminderValue by remember(state.draft.clientOperationId, state.draft.allDay) {
        mutableStateOf(state.draft.reminderMinutes.firstOrNull { it !in presetReminders })
    }
    fun publishReminders() {
        onDraftChanged(
            state.draft.copy(
                reminderMinutes = (selectedPresetReminders + listOfNotNull(customReminderValue))
                    .distinct()
                    .sorted(),
            ),
        )
    }
    fun updateCustomReminder(total: Int?) {
        customReminderValue = total?.takeIf { it > 0 }
        publishReminders()
    }
    fun updateNonAllDayReminder() {
        val days = customDays.toIntOrNull() ?: 0
        val hours = customHours.toIntOrNull() ?: 0
        val minutes = customMinutes.toIntOrNull() ?: 0
        val hasInput = customDays.isNotBlank() || customHours.isNotBlank() || customMinutes.isNotBlank()
        val total = days * 24 * 60 + hours * 60 + minutes
        val valid = days >= 0 && hours in 0..23 && minutes in 0..59 && total > 0
        customReminderInvalid = hasInput && !valid
        updateCustomReminder(if (valid) total else null)
    }
    fun updateAllDayReminder() {
        val days = customDays.toIntOrNull()
        val parts = customTime.split(':')
        val hours = parts.getOrNull(0)?.toIntOrNull()
        val minutes = parts.getOrNull(1)?.toIntOrNull()
        val valid = days != null && days >= 0 &&
            hours != null && minutes != null && hours in 0..23 && minutes in 0..59
        customReminderInvalid =
            (customDays.isNotBlank() || customTime.isNotBlank()) && !valid
        updateCustomReminder(
            if (valid) requireNotNull(days) * 24 * 60 + requireNotNull(hours) * 60 + requireNotNull(minutes)
            else null,
        )
    }
    Card(modifier.padding(18.dp)) {
        Column(
            Modifier.padding(18.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("新增日程", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss, modifier = Modifier.testTag(ScheduleCreateTags.DISMISS)) {
                    Text("取消")
                }
                Button(
                    onClick = onSubmit,
                    enabled = !state.submitting && !uploadState.uploading && !customReminderInvalid,
                    modifier = Modifier.testTag(ScheduleCreateTags.SUBMIT),
                ) {
                    Text(if (state.submitting) "提交中" else "保存")
                }
            }
            OutlinedTextField(
                value = state.draft.title,
                onValueChange = { onDraftChanged(state.draft.copy(title = it.take(50))) },
                label = { Text("标题") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(ScheduleCreateTags.TITLE),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("类型")
                state.types.forEach { option ->
                    FilterChip(
                        selected = option.id == state.draft.typeId,
                        onClick = { onDraftChanged(state.draft.copy(typeId = option.id)) },
                        label = { Text(option.name) },
                        modifier = Modifier.testTag(ScheduleCreateTags.type(option.id)),
                    )
                }
                Text("优先级")
                (1..4).forEach { priority ->
                    FilterChip(
                        selected = priority == state.draft.priority,
                        onClick = { onDraftChanged(state.draft.copy(priority = priority)) },
                        label = { Text(priority.toString()) },
                        modifier = Modifier.testTag(ScheduleCreateTags.priority(priority)),
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.testTag(ScheduleCreateTags.ASSIGNEE),
            ) {
                Text("指派")
                state.members.forEach { option ->
                    FilterChip(
                        selected = option.id == state.draft.assigneeUserId,
                        enabled = state.canAssignOthers || option.id == state.draft.assigneeUserId,
                        onClick = { onDraftChanged(state.draft.copy(assigneeUserId = option.id)) },
                        label = { Text(option.name) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.draft.at,
                    onValueChange = { onDraftChanged(state.draft.copy(at = it)) },
                    label = { Text("日期时间") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.draft.allDay,
                        onCheckedChange = {
                            onDraftChanged(
                                state.draft.copy(
                                    allDay = it,
                                    at = if (it) state.draft.at.toMidnight() else state.draft.at,
                                    reminderMinutes = emptyList(),
                                ),
                            )
                        },
                        modifier = Modifier.testTag(ScheduleCreateTags.ALL_DAY),
                    )
                    Text("全天")
                }
            }
            OutlinedTextField(
                value = state.draft.description,
                onValueChange = { onDraftChanged(state.draft.copy(description = it.take(200))) },
                label = { Text("描述") },
                minLines = 2,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth().testTag(ScheduleCreateTags.DESCRIPTION),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("提醒")
                presetReminders.forEach { minutes ->
                    FilterChip(
                        selected = minutes in selectedPresetReminders,
                        modifier = Modifier.testTag(ScheduleCreateTags.reminder(minutes)),
                        onClick = {
                            selectedPresetReminders = if (minutes in selectedPresetReminders) {
                                selectedPresetReminders - minutes
                            } else {
                                selectedPresetReminders + minutes
                            }
                            publishReminders()
                        },
                        label = { Text(reminderLabel(minutes, state.draft.allDay)) },
                    )
                }
                OutlinedTextField(
                    value = customDays,
                    onValueChange = {
                        customDays = it.filter(Char::isDigit).take(5)
                        if (state.draft.allDay) updateAllDayReminder() else updateNonAllDayReminder()
                    },
                    label = { Text("提前天数") },
                    singleLine = true,
                    modifier = Modifier.testTag(ScheduleCreateTags.CUSTOM_REMINDER_DAYS),
                )
                if (state.draft.allDay) {
                    OutlinedTextField(
                        value = customTime,
                        onValueChange = {
                            customTime = it.filter { char -> char.isDigit() || char == ':' }.take(5)
                            updateAllDayReminder()
                        },
                        label = { Text("提醒时间 HH:mm") },
                        singleLine = true,
                        modifier = Modifier.testTag(ScheduleCreateTags.CUSTOM_REMINDER_TIME),
                    )
                } else {
                    OutlinedTextField(
                        value = customHours,
                        onValueChange = {
                            customHours = it.filter(Char::isDigit).take(2)
                            updateNonAllDayReminder()
                        },
                        label = { Text("提前小时（0-23）") },
                        singleLine = true,
                        modifier = Modifier.testTag(ScheduleCreateTags.CUSTOM_REMINDER_HOURS),
                    )
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = {
                            customMinutes = it.filter(Char::isDigit).take(2)
                            updateNonAllDayReminder()
                        },
                        label = { Text("提前分钟（0-59）") },
                        singleLine = true,
                        modifier = Modifier.testTag(ScheduleCreateTags.CUSTOM_REMINDER_MINUTES),
                    )
                }
            }
            if (customReminderInvalid) {
                Text(
                    "请输入有效的提醒时间",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(ScheduleCreateTags.CUSTOM_REMINDER_ERROR),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("重复")
                listOf("NONE", "DAILY", "WEEKLY", "MONTHLY", "YEARLY").forEach { rule ->
                    val repeatingAllowed = rule == "NONE" || state.draft.relations.none {
                        it.relationType == BusinessScheduleRelationType.VISIT.name ||
                            it.relationType == BusinessScheduleRelationType.SERVICE.name
                    }
                    FilterChip(
                        selected = state.draft.repeatRule == rule,
                        enabled = repeatingAllowed,
                        onClick = { onDraftChanged(state.draft.copy(repeatRule = rule)) },
                        label = { Text(repeatLabel(rule)) },
                        modifier = Modifier.testTag(ScheduleCreateTags.repeat(rule)),
                    )
                }
            }
            Text("关联事项")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.relationTypes.forEach { type ->
                    FilterChip(
                        selected = state.draft.relations.any { it.relationType == type.name },
                        onClick = { onRelationTypeSelected(type) },
                        label = { Text(type.label()) },
                        modifier = Modifier.testTag(ScheduleCreateTags.relation(type)),
                    )
                }
            }
            if (state.relationOptions.isNotEmpty() && state.selectedRelationType != null) {
                state.selectedServiceRecord?.let { record ->
                    Text("服务记录：${record.name}")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.relationOptions.forEach { option ->
                        val type = state.selectedRelationType
                        FilterChip(
                            selected = state.draft.relations.any {
                                it.relationType == type.name && it.id == option.id
                            },
                            onClick = {
                                if (onRelationOptionSelected != null) {
                                    onRelationOptionSelected(type, option)
                                } else {
                                    onDraftChanged(
                                        state.draft.copy(
                                            relations = listOf(
                                                BusinessScheduleRelation(type.name, option.id, option.name),
                                            ),
                                        ),
                                    )
                                }
                            },
                            label = { Text(option.name) },
                            modifier = Modifier.testTag(ScheduleCreateTags.relationOption(type, option.id)),
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("附件")
                OutlinedButton(
                    onClick = onChooseAttachments,
                    enabled = !uploadState.uploading,
                    modifier = Modifier.testTag(ScheduleCreateTags.CHOOSE_ATTACHMENTS),
                ) {
                    Text("选择附件")
                }
                state.attachmentNames.forEach { name ->
                    FilterChip(
                        selected = true,
                        onClick = { onRemoveAttachment(name) },
                        label = { Text("$name ×") },
                    )
                }
            }
            if (uploadState.uploading) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        progress = { uploadState.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).testTag(ScheduleCreateTags.UPLOAD_PROGRESS),
                    )
                    Text("${(uploadState.progress.coerceIn(0f, 1f) * 100).roundToInt()}%")
                    OutlinedButton(
                        onClick = onCancelUpload,
                        modifier = Modifier.testTag(ScheduleCreateTags.CANCEL_UPLOAD),
                    ) {
                        Text("取消上传")
                    }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun OptionField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun BusinessScheduleRelationType.label(): String = when (this) {
    BusinessScheduleRelationType.CUSTOMER -> "客户"
    BusinessScheduleRelationType.CASE -> "案件"
    BusinessScheduleRelationType.VISIT -> "拜访"
    BusinessScheduleRelationType.SERVICE -> "服务项目"
}

private fun repeatLabel(rule: String): String = when (rule.uppercase()) {
    "DAILY" -> "每天"
    "WEEKLY" -> "每周"
    "MONTHLY" -> "每月"
    "YEARLY" -> "每年"
    else -> "不重复"
}

private fun String.toMidnight(): String =
    substringBefore(' ').takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
        ?.let { "$it 00:00:00" }
        ?: this

private val TIMED_PRESET_REMINDERS =
    listOf(15, 30, 60, 120, 1440, 2880, 4320, 10080, 21600, 43200)
private val ALL_DAY_PRESET_REMINDERS =
    listOf(-540, -600, -840, 240, 3120, 8880, 42000)

private fun reminderLabel(minutes: Int, allDay: Boolean): String {
    if (!allDay) {
        return when (minutes) {
            15 -> "提前 15 分钟"
            30 -> "提前 30 分钟"
            60 -> "提前 1 小时"
            120 -> "提前 2 小时"
            1440 -> "提前 1 天"
            2880 -> "提前 2 天"
            4320 -> "提前 3 天"
            10080 -> "提前 1 周"
            21600 -> "提前 15 天"
            43200 -> "提前 1 个月"
            else -> "$minutes 分钟"
        }
    }
    return when (minutes) {
        -540 -> "当天 09:00"
        -600 -> "当天 10:00"
        -840 -> "当天 14:00"
        240 -> "提前 1 天 20:00"
        3120 -> "提前 3 天 20:00"
        8880 -> "提前 7 天 20:00"
        42000 -> "提前 30 天 20:00"
        else -> "$minutes 分钟"
    }
}
