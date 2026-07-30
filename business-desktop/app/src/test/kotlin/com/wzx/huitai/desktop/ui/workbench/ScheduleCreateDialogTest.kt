package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchScope
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import com.wzx.huitai.desktop.workbench.BusinessScheduleDraft
import com.wzx.huitai.desktop.workbench.BusinessScheduleFormOption
import com.wzx.huitai.desktop.workbench.BusinessScheduleFormState
import com.wzx.huitai.desktop.workbench.BusinessScheduleRelationType
import com.wzx.huitai.desktop.workbench.BusinessAttachmentUploadState
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class ScheduleCreateDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `complete form exposes assignment priority time reminders repeat relations and attachments`() {
        val actions = mutableListOf<String>()
        rule.setContent {
            HuitaiBusinessTheme {
                ScheduleCreateDialog(
                    state = BusinessScheduleFormState(
                        visible = true,
                        canAssignOthers = false,
                        draft = BusinessScheduleDraft(
                            scope = BusinessWorkbenchScope.TEAM,
                            teamId = "team-1",
                            title = "会见客户",
                            typeId = "type-1",
                            at = "2026-07-29 10:00:00",
                            allDay = false,
                            priority = 2,
                            description = "讨论案件",
                            reminderMinutes = listOf(10, 45),
                            repeatRule = "NONE",
                        ),
                        types = listOf(BusinessScheduleFormOption("type-1", "会议")),
                        members = listOf(BusinessScheduleFormOption("user-1", "当前用户")),
                        relationTypes = BusinessScheduleRelationType.entries.toSet(),
                        attachmentNames = listOf("证据.pdf"),
                    ),
                    onDraftChanged = { actions += "draft" },
                    onRelationTypeSelected = { actions += it.name },
                    onLoadRelationOptions = { actions += "relations" },
                    onChooseAttachments = { actions += "attachments" },
                    onRemoveAttachment = { actions += "remove=$it" },
                    onSubmit = { actions += "submit" },
                    onDismiss = { actions += "dismiss" },
                    modifier = Modifier.requiredSize(900.dp, 760.dp),
                )
            }
        }

        listOf("标题", "类型", "指派", "优先级", "日期时间", "全天", "描述", "提醒", "重复",
            "客户", "案件", "拜访", "服务项目", "附件").forEach {
            rule.onNodeWithText(it).assertExists()
        }
        rule.onNodeWithTag(ScheduleCreateTags.ASSIGNEE).assertExists()
        rule.onNodeWithTag(ScheduleCreateTags.CUSTOM_REMINDER_DAYS).assertExists()
        rule.onNodeWithTag(ScheduleCreateTags.CUSTOM_REMINDER_HOURS).assertExists()
        rule.onNodeWithTag(ScheduleCreateTags.CUSTOM_REMINDER_MINUTES).assertExists()
        rule.onNodeWithTag(ScheduleCreateTags.relation(BusinessScheduleRelationType.CASE)).performScrollTo().performClick()
        rule.onNodeWithTag(ScheduleCreateTags.CHOOSE_ATTACHMENTS).performScrollTo().performClick()
        rule.onNodeWithTag(ScheduleCreateTags.SUBMIT).performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf("CASE", "attachments", "submit"), actions) }
    }

    @Test
    fun `field limits typed options member authority relation selection and repeat rules update draft`() {
        val drafts = mutableListOf<BusinessScheduleDraft>()
        val initial = BusinessScheduleDraft(
            scope = BusinessWorkbenchScope.TEAM,
            teamId = "team-1",
            assigneeUserId = "user-1",
            title = "会议",
            typeId = "type-1",
            at = "2026-07-29 10:00:00",
            relations = listOf(
                com.wzx.huitai.agent.business.workbench.BusinessScheduleRelation("VISIT", "visit-1"),
            ),
        )
        rule.setContent {
            HuitaiBusinessTheme {
                ScheduleCreateDialog(
                    state = BusinessScheduleFormState(
                        visible = true,
                        canAssignOthers = false,
                        draft = initial,
                        types = listOf(
                            BusinessScheduleFormOption("type-1", "会议"),
                            BusinessScheduleFormOption("type-2", "庭审"),
                        ),
                        members = listOf(
                            BusinessScheduleFormOption("user-1", "当前用户"),
                            BusinessScheduleFormOption("user-2", "其他成员"),
                        ),
                        selectedRelationType = BusinessScheduleRelationType.CASE,
                        relationOptions = listOf(BusinessScheduleFormOption("case-1", "合同纠纷")),
                    ),
                    onDraftChanged = { drafts += it },
                    onRelationTypeSelected = {},
                    onLoadRelationOptions = {},
                    onChooseAttachments = {},
                    onRemoveAttachment = {},
                    onSubmit = {},
                    onDismiss = {},
                    modifier = Modifier.requiredSize(900.dp, 760.dp),
                )
            }
        }

        rule.onNodeWithTag(ScheduleCreateTags.TITLE).performTextReplacement("题".repeat(60))
        rule.onNodeWithTag(ScheduleCreateTags.DESCRIPTION).performTextReplacement("描".repeat(250))
        rule.onNodeWithTag(ScheduleCreateTags.type("type-2")).performClick()
        rule.onNodeWithTag(ScheduleCreateTags.priority(4)).performClick()
        rule.onNodeWithText("其他成员").assertIsNotEnabled()
        rule.onNodeWithText("合同纠纷").performScrollTo().performClick()
        rule.onNodeWithTag(ScheduleCreateTags.repeat("DAILY")).performScrollTo().assertIsNotEnabled()

        rule.runOnIdle {
            assertTrue(drafts.any { it.title.length == 50 })
            assertTrue(drafts.any { it.description.length == 200 })
            assertTrue(drafts.any { it.typeId == "type-2" })
            assertTrue(drafts.any { it.priority == 4 })
            assertTrue(drafts.any { it.relations.any { relation -> relation.id == "case-1" } })
        }
    }

    @Test
    fun `upload progress disables choose and submit and exposes an independent cancel action`() {
        var cancelled = 0
        rule.setContent {
            HuitaiBusinessTheme {
                ScheduleCreateDialog(
                    state = BusinessScheduleFormState(
                        visible = true,
                        draft = BusinessScheduleDraft.validForTest(),
                    ),
                    uploadState = BusinessAttachmentUploadState(uploading = true, progress = 0.42f),
                    onDraftChanged = {},
                    onRelationTypeSelected = {},
                    onLoadRelationOptions = {},
                    onChooseAttachments = {},
                    onCancelUpload = { cancelled++ },
                    onRemoveAttachment = {},
                    onSubmit = {},
                    onDismiss = {},
                    modifier = Modifier.requiredSize(900.dp, 760.dp),
                )
            }
        }

        rule.onNodeWithTag(ScheduleCreateTags.UPLOAD_PROGRESS).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("42%").assertIsDisplayed()
        rule.onNodeWithTag(ScheduleCreateTags.CHOOSE_ATTACHMENTS).assertIsNotEnabled()
        rule.onNodeWithTag(ScheduleCreateTags.SUBMIT).assertIsNotEnabled()
        rule.onNodeWithTag(ScheduleCreateTags.CANCEL_UPLOAD).performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test
    fun `non all day custom reminder combines days hours and minutes without loading relations`() {
        val drafts = mutableListOf<BusinessScheduleDraft>()
        var relationLoads = 0
        rule.setContent {
            HuitaiBusinessTheme {
                ScheduleCreateDialog(
                    state = BusinessScheduleFormState(
                        visible = true,
                        draft = BusinessScheduleDraft.validForTest(),
                    ),
                    onDraftChanged = { drafts += it },
                    onRelationTypeSelected = {},
                    onLoadRelationOptions = { relationLoads++ },
                    onChooseAttachments = {},
                    onRemoveAttachment = {},
                    onSubmit = {},
                    onDismiss = {},
                    modifier = Modifier.requiredSize(900.dp, 760.dp),
                )
            }
        }

        rule.onNodeWithTag(ScheduleCreateTags.CUSTOM_REMINDER_DAYS).performTextInput("1")
        rule.onNodeWithTag(ScheduleCreateTags.CUSTOM_REMINDER_HOURS).performTextInput("2")
        rule.onNodeWithTag(ScheduleCreateTags.CUSTOM_REMINDER_MINUTES).performTextInput("3")

        rule.runOnIdle {
            assertEquals(listOf(1563), drafts.last().reminderMinutes)
            assertEquals(0, relationLoads)
        }
    }

    @Test
    fun `all day custom reminder combines days and clock time and ignores invalid time`() {
        val drafts = mutableListOf<BusinessScheduleDraft>()
        var submitted = 0
        rule.setContent {
            HuitaiBusinessTheme {
                ScheduleCreateDialog(
                    state = BusinessScheduleFormState(
                        visible = true,
                        draft = BusinessScheduleDraft.validForTest().copy(allDay = true),
                    ),
                    onDraftChanged = { drafts += it },
                    onRelationTypeSelected = {},
                    onLoadRelationOptions = {},
                    onChooseAttachments = {},
                    onRemoveAttachment = {},
                    onSubmit = { submitted++ },
                    onDismiss = {},
                    modifier = Modifier.requiredSize(900.dp, 760.dp),
                )
            }
        }

        rule.onNodeWithTag(ScheduleCreateTags.CUSTOM_REMINDER_DAYS).performTextInput("1")
        rule.onNodeWithTag(ScheduleCreateTags.CUSTOM_REMINDER_TIME).performTextInput("02:30")
        rule.onNodeWithTag(ScheduleCreateTags.CUSTOM_REMINDER_TIME).performTextReplacement("25:99")

        rule.runOnIdle {
            assertTrue(drafts.any { 1590 in it.reminderMinutes })
            assertTrue(drafts.last().reminderMinutes.none { it == 2999 })
        }
        rule.onNodeWithTag(ScheduleCreateTags.SUBMIT).assertIsNotEnabled()
        rule.onNodeWithTag(ScheduleCreateTags.CUSTOM_REMINDER_ERROR).assertIsDisplayed()
        rule.onNodeWithTag(ScheduleCreateTags.SUBMIT).performClick()
        rule.runOnIdle { assertEquals(0, submitted) }
    }

    @Test
    fun `real OA reminder presets and all day midnight toggle are wired`() {
        val drafts = mutableListOf<BusinessScheduleDraft>()
        rule.setContent {
            var draft by remember { mutableStateOf(BusinessScheduleDraft.validForTest()) }
            HuitaiBusinessTheme {
                ScheduleCreateDialog(
                    state = BusinessScheduleFormState(
                        visible = true,
                        draft = draft,
                    ),
                    onDraftChanged = {
                        drafts += it
                        draft = it
                    },
                    onRelationTypeSelected = {},
                    onLoadRelationOptions = {},
                    onChooseAttachments = {},
                    onRemoveAttachment = {},
                    onSubmit = {},
                    onDismiss = {},
                    modifier = Modifier.requiredSize(900.dp, 760.dp),
                )
            }
        }

        rule.onNodeWithTag(ScheduleCreateTags.reminder(15)).performScrollTo().performClick()
        rule.onNodeWithTag(ScheduleCreateTags.reminder(120)).performScrollTo().performClick()
        rule.onNodeWithText("提前 1 个月").assertExists()
        rule.onNodeWithText("43200 分钟").assertDoesNotExist()
        rule.onNodeWithTag(ScheduleCreateTags.ALL_DAY).performScrollTo().performClick()
        rule.onNodeWithText("当天 09:00").assertExists()
        rule.onNodeWithText("当天 10:00").assertExists()
        rule.onNodeWithText("当天 14:00").assertExists()
        rule.onNodeWithText("提前 1 天 20:00").assertExists()
        rule.onNodeWithText("-540 分钟").assertDoesNotExist()

        rule.runOnIdle {
            assertTrue(drafts.any { 15 in it.reminderMinutes })
            assertTrue(drafts.any { 120 in it.reminderMinutes })
            assertTrue(drafts.any { it.allDay && it.at.endsWith("00:00:00") })
        }
    }

    @Test
    fun `service project click is delegated with the selected service record context`() {
        val selections = mutableListOf<Pair<BusinessScheduleRelationType, String>>()
        rule.setContent {
            HuitaiBusinessTheme {
                ScheduleCreateDialog(
                    state = BusinessScheduleFormState(
                        visible = true,
                        draft = BusinessScheduleDraft.validForTest(),
                        selectedRelationType = BusinessScheduleRelationType.SERVICE,
                        selectedServiceRecord = BusinessScheduleFormOption("record-1", "常年顾问"),
                        relationOptions = listOf(BusinessScheduleFormOption("project-1", "合同审查")),
                    ),
                    onDraftChanged = {},
                    onRelationTypeSelected = {},
                    onRelationOptionSelected = { type, option -> selections += type to option.id },
                    onLoadRelationOptions = {},
                    onChooseAttachments = {},
                    onRemoveAttachment = {},
                    onSubmit = {},
                    onDismiss = {},
                    modifier = Modifier.requiredSize(900.dp, 760.dp),
                )
            }
        }

        rule.onNodeWithText("服务记录：常年顾问").assertExists()
        rule.onNodeWithTag(
            ScheduleCreateTags.relationOption(BusinessScheduleRelationType.SERVICE, "project-1"),
        ).performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(listOf(BusinessScheduleRelationType.SERVICE to "project-1"), selections)
        }
    }
}
