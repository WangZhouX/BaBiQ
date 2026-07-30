package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchScope
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import com.wzx.huitai.desktop.workbench.BusinessScheduleItem
import com.wzx.huitai.desktop.workbench.BusinessScheduleState
import com.wzx.huitai.desktop.workbench.BusinessScheduleViewMode
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class SchedulePanelTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `month and week controls event dots today navigation and team onlyMine are wired`() {
        val actions = mutableListOf<String>()
        rule.setContent {
            HuitaiBusinessTheme {
                SchedulePanel(
                    state = BusinessScheduleState(
                        identityEpoch = 7,
                        generation = 9,
                        visibleMonth = YearMonth.of(2026, 7),
                        selectedDate = LocalDate.of(2026, 7, 29),
                        viewMode = BusinessScheduleViewMode.MONTH,
                        scope = BusinessWorkbenchScope.TEAM,
                        teamId = "team-1",
                        onlyMine = true,
                        eventDates = setOf(LocalDate.of(2026, 7, 29)),
                        items = listOf(BusinessScheduleItem("schedule-1", "客户会议", "2026-07-29 10:00:00", false)),
                    ),
                    onPrevious = { actions += "previous" },
                    onNext = { actions += "next" },
                    onToday = { actions += "today" },
                    onViewModeChanged = { actions += it.name },
                    onOnlyMineChanged = { actions += "onlyMine=$it" },
                    onDateSelected = { actions += it.toString() },
                    onCompletionChanged = { id, completed -> actions += "$id=$completed" },
                    onCreate = { actions += "create" },
                    modifier = Modifier.requiredSize(900.dp, 700.dp),
                )
            }
        }

        rule.onNodeWithText("2026年7月").assertIsDisplayed()
        rule.onNodeWithTag(ScheduleTags.eventDot(LocalDate.of(2026, 7, 29))).assertIsDisplayed()
        rule.onNodeWithTag(ScheduleTags.WEEK).performClick()
        rule.onNodeWithTag(ScheduleTags.PREVIOUS).performClick()
        rule.onNodeWithTag(ScheduleTags.NEXT).performClick()
        rule.onNodeWithTag(ScheduleTags.TODAY).performClick()
        rule.onNodeWithTag(ScheduleTags.ONLY_MINE).performClick()
        rule.onNodeWithTag(ScheduleTags.complete("schedule-1")).performClick()
        rule.onNodeWithTag(ScheduleTags.CREATE).performClick()

        rule.runOnIdle {
            assertEquals(
                listOf("WEEK", "previous", "next", "today", "onlyMine=false", "schedule-1=true", "create"),
                actions,
            )
        }
    }

    @Test
    fun `week crossing year keeps event dates selectable and personal empty state hides onlyMine`() {
        val selected = mutableListOf<LocalDate>()
        rule.setContent {
            HuitaiBusinessTheme {
                SchedulePanel(
                    state = BusinessScheduleState(
                        identityEpoch = 7,
                        generation = 9,
                        visibleMonth = YearMonth.of(2026, 1),
                        selectedDate = LocalDate.of(2026, 1, 1),
                        viewMode = BusinessScheduleViewMode.WEEK,
                        scope = BusinessWorkbenchScope.PERSONAL,
                        eventDates = setOf(LocalDate.of(2025, 12, 29)),
                    ),
                    onPrevious = {},
                    onNext = {},
                    onToday = {},
                    onViewModeChanged = {},
                    onOnlyMineChanged = {},
                    onDateSelected = { selected += it },
                    onCompletionChanged = { _, _ -> },
                    onCreate = {},
                    modifier = Modifier.requiredSize(900.dp, 700.dp),
                )
            }
        }

        rule.onNodeWithTag(ScheduleTags.eventDot(LocalDate.of(2025, 12, 29))).assertIsDisplayed()
        rule.onNodeWithTag(ScheduleTags.date(LocalDate.of(2025, 12, 29))).performClick()
        rule.onNodeWithText("当日暂无日程").assertIsDisplayed()
        rule.onNodeWithTag(ScheduleTags.ONLY_MINE).assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf(LocalDate.of(2025, 12, 29)), selected) }
    }

    @Test
    fun `timeline renders group all day type color priority repetition and expiry metadata`() {
        rule.setContent {
            HuitaiBusinessTheme {
                SchedulePanel(
                    state = BusinessScheduleState(
                        visibleMonth = YearMonth.of(2026, 7),
                        selectedDate = LocalDate.of(2026, 7, 29),
                        items = listOf(
                            BusinessScheduleItem(
                                id = "schedule-1",
                                title = "Client meeting",
                                at = "2026-07-29 10:00:00",
                                completed = false,
                                groupTime = "morning",
                                allDay = false,
                                typeTitle = "hearing",
                                color = "#216DFF",
                                priority = 3,
                                repetition = 2,
                                expiredDays = 4,
                            ),
                        ),
                    ),
                    onPrevious = {},
                    onNext = {},
                    onToday = {},
                    onViewModeChanged = {},
                    onOnlyMineChanged = {},
                    onDateSelected = {},
                    onCompletionChanged = { _, _ -> },
                    onCreate = {},
                    modifier = Modifier.requiredSize(900.dp, 700.dp),
                )
            }
        }

        rule.onNodeWithText("morning").assertIsDisplayed()
        rule.onNodeWithText("hearing").assertIsDisplayed()
        rule.onNodeWithText("优先级 3").assertIsDisplayed()
        rule.onNodeWithText("每周重复").assertIsDisplayed()
        rule.onNodeWithText("已过期 4 天").assertIsDisplayed()
    }
}
