package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.business.auth.BusinessNavigationTarget
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchPage
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchPageItem
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSection
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSectionStatus
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchSnapshot
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchScope
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchTeamRole
import com.wzx.huitai.desktop.workbench.BusinessWorkbenchLoadState
import com.wzx.huitai.desktop.workbench.BusinessWorkbenchState
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BusinessWorkbenchScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `screen renders the full workbench sections and forwards intents`() {
        val opened = mutableListOf<String>()
        var selectedScope: String? = null
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessWorkbenchScreen(
                    state = readyState(),
                    showHeader = false,
                    onQuickEntrance = opened::add,
                    onScopeSelected = { selectedScope = it.name },
                    modifier = Modifier.requiredSize(1000.dp, 700.dp),
                )
            }
        }

        listOf(
            WorkbenchTags.ROOT,
            WorkbenchTags.QUICK_ENTRANCES,
            WorkbenchTags.STATISTICS,
            WorkbenchTags.LIST,
            WorkbenchTags.PROFILE,
            ScheduleTags.ROOT,
        ).forEach { rule.onNodeWithTag(it).fetchSemanticsNode() }
        rule.onNodeWithTag("business-workbench-schedule").assertDoesNotExist()
        val listBounds = rule.onNodeWithTag(WorkbenchTags.LIST).fetchSemanticsNode().boundsInRoot
        val profileBounds = rule.onNodeWithTag(WorkbenchTags.PROFILE).fetchSemanticsNode().boundsInRoot
        val scheduleBounds = rule.onNodeWithTag(ScheduleTags.ROOT).fetchSemanticsNode().boundsInRoot
        assertTrue(scheduleBounds.left >= listBounds.right)
        assertTrue(scheduleBounds.top >= profileBounds.bottom)
        rule.onNodeWithTag(WorkbenchTags.quickItem(0)).performClick()
        rule.onNodeWithTag(WorkbenchTags.scopeItem("TEAM")).performClick()
        rule.runOnIdle {
            assertEquals(listOf("/case"), opened)
            assertEquals("TEAM", selectedScope)
        }
        rule.onNodeWithText("案件一").assertTextContains("案件一")
    }

    @Test
    fun `error state exposes retry instead of pretending empty`() {
        var retries = 0
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessWorkbenchScreen(
                    state = BusinessWorkbenchState(
                        identityEpoch = 1,
                        loadState = BusinessWorkbenchLoadState.READY,
                        pageError = "网络不可用",
                    ),
                    showHeader = false,
                    onRetryPage = { retries++ },
                    modifier = Modifier.requiredSize(900.dp, 700.dp),
                )
            }
        }
        rule.onNodeWithTag(WorkbenchTags.LIST_ERROR).assertTextContains("网络不可用")
        rule.onNodeWithTag(WorkbenchTags.LIST_RETRY).performClick()
        rule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun `screen does not render a second navigation inside workbench content`() {
        val base = readyState()
        val state = base.copy(
            navigation = listOf(
                BusinessNavigationTarget("WORKBENCH", "/", "工作台"),
                BusinessNavigationTarget("CASE", "/case", "案件管理"),
            ),
        )
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessWorkbenchScreen(
                    state = state,
                    showHeader = false,
                    selectedPath = "/case",
                    modifier = Modifier.requiredSize(1000.dp, 700.dp),
                )
            }
        }

        rule.onNodeWithTag(WorkbenchTags.NAVIGATION).assertDoesNotExist()
    }

    @Test
    fun `team and role selectors forward only displayed authorized values`() {
        var team: String? = null
        var role: String? = null
        val base = readyState()
        val state = base.copy(
            scope = BusinessWorkbenchScope.TEAM,
            teamId = "team-1",
            roleCode = "OWNER",
            roles = listOf(
                BusinessWorkbenchTeamRole("OWNER", "负责人"),
                BusinessWorkbenchTeamRole("MEMBER", "成员"),
            ),
            snapshot = base.snapshot?.copy(
                teams = BusinessWorkbenchSection(
                    BusinessWorkbenchSectionStatus.OK,
                    kotlinx.serialization.json.Json.parseToJsonElement(
                        """[{"id":"team-1","name":"第一团队"},{"id":"team-2","name":"第二团队"}]""",
                    ),
                ),
            ),
        )
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessWorkbenchScreen(
                    state = state,
                    showHeader = false,
                    onTeamSelected = { team = it },
                    onRoleSelected = { role = it },
                    modifier = Modifier.requiredSize(1000.dp, 700.dp),
                )
            }
        }

        rule.onNodeWithTag("business-workbench-team-team-2").performClick()
        rule.onNodeWithTag("business-workbench-role-MEMBER").performClick()
        rule.runOnIdle {
            assertEquals("team-2", team)
            assertEquals("MEMBER", role)
        }
    }

    @Test
    fun `case action and page controls forward the visible page intents`() {
        var previous = 0
        var next = 0
        var selectedCase: String? = null
        val state = readyState().copy(
            page = BusinessWorkbenchPage(
                identityEpoch = 1,
                generation = 2,
                total = 45,
                pageNo = 2,
                pageSize = 20,
                items = listOf(
                    BusinessWorkbenchPageItem(
                        id = "case-1",
                        applicationNumber = "A-1",
                        title = "案件一",
                    ),
                ),
            ),
        )
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessWorkbenchScreen(
                    state = state,
                    showHeader = false,
                    onPreviousPage = { previous++ },
                    onNextPage = { next++ },
                    onCaseSelected = { selectedCase = it },
                    modifier = Modifier.requiredSize(1000.dp, 700.dp),
                )
            }
        }

        rule.onNodeWithText("查看").performClick()
        rule.onNodeWithTag(WorkbenchTags.PREVIOUS).performClick()
        rule.onNodeWithTag(WorkbenchTags.NEXT).performClick()
        rule.runOnIdle {
            assertEquals("case-1", selectedCase)
            assertEquals(1, previous)
            assertEquals(1, next)
        }
    }

    private fun readyState() = BusinessWorkbenchState(
        loadState = BusinessWorkbenchLoadState.READY,
        identityEpoch = 1,
        generation = 2,
        snapshot = BusinessWorkbenchSnapshot(
            identityEpoch = 1,
            generation = 2,
            shortcuts = BusinessWorkbenchSection(BusinessWorkbenchSectionStatus.OK, buildJsonObject { put("title", "新建案件"); put("path", "/case") }),
            summary = BusinessWorkbenchSection(BusinessWorkbenchSectionStatus.OK, buildJsonObject { put("title", "案件"); put("count", 3) }),
            profile = BusinessWorkbenchSection(BusinessWorkbenchSectionStatus.OK, buildJsonObject { put("nickname", "李律师") }),
            schedule = BusinessWorkbenchSection(BusinessWorkbenchSectionStatus.OK, buildJsonObject { put("count", 2); put("day", "2026-07-27") }),
        ),
        navigation = listOf(BusinessNavigationTarget("WORKBENCH", "/", "工作台")),
        page = BusinessWorkbenchPage(1, 2, 1, 1, 20, listOf(BusinessWorkbenchPageItem("case-1", applicationNumber = "A-1", title = "案件一"))),
    )
}
