package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchKind
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchPage
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchPageItem
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test

class BusinessListCardTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `case list renders whitelisted firm fields and opens the selected case`() {
        var selected: String? = null
        val page = page(
            BusinessWorkbenchPageItem(
                id = "case-1",
                applicationNumber = "A-001",
                categoriesName = "合同纠纷",
                values = buildJsonObject {
                    put("caseName", "合同争议")
                    put("logo", "opaque-logo")
                    put("tenant", buildJsonObject { put("name", "惠太律所") })
                    put(
                        "teamDatas",
                        buildJsonArray {
                            add(buildJsonObject { put("roleName", "负责人") })
                            add(buildJsonObject { put("roleName", "协办人") })
                        },
                    )
                },
            ),
        )
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessListCard(
                    page = page,
                    onCaseSelected = { selected = it },
                    kind = BusinessWorkbenchKind.CASE,
                    modifier = Modifier.requiredSize(800.dp, 400.dp),
                )
            }
        }

        assertNull(selected)
        rule.onNodeWithText("合同争议").fetchSemanticsNode()
        rule.onNodeWithText("惠太律所 · 2 个团队角色 · 律所标识").fetchSemanticsNode()
        rule.onNodeWithText("查看").performClick()
        rule.runOnIdle { assertEquals("case-1", selected) }
    }

    @Test
    fun `appointment list renders only appointment response fields`() {
        val page = page(
            BusinessWorkbenchPageItem(
                id = "appointment-1",
                values = buildJsonObject {
                    put("name", "预约人")
                    put("consultMode", 2)
                    put("causeAction", "合同")
                    put("appointLocation", "会议室")
                    put("createTime", "2026-07-29 10:00")
                },
            ),
        )
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessListCard(
                    page = page,
                    kind = BusinessWorkbenchKind.APPOINTMENT,
                    modifier = Modifier.requiredSize(800.dp, 400.dp),
                )
            }
        }

        rule.onNodeWithText("预约人").fetchSemanticsNode()
        rule.onNodeWithText("电话 · 合同 · 2026-07-29 10:00 · 会议室").fetchSemanticsNode()
        rule.onNodeWithText("查看").assertDoesNotExist()
    }

    @Test
    fun `counselor service list renders only counselor response fields`() {
        val page = page(
            BusinessWorkbenchPageItem(
                id = "service-1",
                values = buildJsonObject {
                    put("serviceTitle", "常法服务")
                    put("serviceObjectName", "顾问单位甲")
                    put("serviceStatus", 1)
                    put("totalServiceCount", 3)
                },
            ),
        )
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessListCard(
                    page = page,
                    kind = BusinessWorkbenchKind.COUNSELOR_SERVICE,
                    modifier = Modifier.requiredSize(800.dp, 400.dp),
                )
            }
        }

        rule.onNodeWithText("常法服务").fetchSemanticsNode()
        rule.onNodeWithText("顾问单位甲 · 进行中 · 3 项").fetchSemanticsNode()
        rule.onNodeWithText("查看").assertDoesNotExist()
    }

    @Test
    fun `visit list renders only visit response fields`() {
        val page = page(
            BusinessWorkbenchPageItem(
                id = "visit-1",
                scheduleName = "回访日程",
                values = buildJsonObject {
                    put("visitItem", "客户回访")
                    put("visitObjName", "客户甲")
                    put("visitTime", "2026-07-30")
                    put("visitDay", 1)
                    put("scheduleName", "回访日程")
                },
            ),
        )
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessListCard(
                    page = page,
                    kind = BusinessWorkbenchKind.VISIT,
                    modifier = Modifier.requiredSize(800.dp, 400.dp),
                )
            }
        }

        rule.onNodeWithText("客户回访").fetchSemanticsNode()
        rule.onNodeWithText("2026-07-30 · 客户甲 · 回访日程").fetchSemanticsNode()
        rule.onNodeWithText("查看").assertDoesNotExist()
    }

    @Test
    fun `page controls forward previous and next once`() {
        var previous = 0
        var next = 0
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessListCard(
                    page = BusinessWorkbenchPage(
                        identityEpoch = 1,
                        generation = 2,
                        total = 45,
                        pageNo = 2,
                        pageSize = 20,
                        items = listOf(BusinessWorkbenchPageItem(id = "case-1", title = "案件一")),
                    ),
                    onPrevious = { previous++ },
                    onNext = { next++ },
                    kind = BusinessWorkbenchKind.CASE,
                    modifier = Modifier.requiredSize(800.dp, 400.dp),
                )
            }
        }

        rule.onNodeWithTag(WorkbenchTags.PREVIOUS).performClick()
        rule.onNodeWithTag(WorkbenchTags.NEXT).performClick()
        rule.runOnIdle {
            assertEquals(1, previous)
            assertEquals(1, next)
        }
    }

    @Test
    fun `failed list exposes the error and forwards retry`() {
        var retries = 0
        rule.setContent {
            HuitaiBusinessTheme {
                BusinessListCard(
                    page = null,
                    error = "网络不可用",
                    onRetry = { retries++ },
                    kind = BusinessWorkbenchKind.CASE,
                    modifier = Modifier.requiredSize(800.dp, 400.dp),
                )
            }
        }

        rule.onNodeWithTag(WorkbenchTags.LIST_ERROR).assertTextContains("网络不可用")
        rule.onNodeWithTag(WorkbenchTags.LIST_RETRY).performClick()
        rule.runOnIdle { assertEquals(1, retries) }
    }

    private fun page(item: BusinessWorkbenchPageItem) =
        BusinessWorkbenchPage(1, 2, 1, 1, 20, listOf(item))
}
