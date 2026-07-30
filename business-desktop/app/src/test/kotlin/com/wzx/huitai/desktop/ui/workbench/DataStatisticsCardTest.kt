package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class DataStatisticsCardTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `selection uses original summary index after disabled entries are filtered`() {
        rule.setContent {
            HuitaiBusinessTheme {
                DataStatisticsCard(
                    data = buildJsonArray {
                        add(buildJsonObject {
                            put("configCode", "case_handle")
                            put("configName", "案件")
                            put("total", 4)
                            put("enabled", false)
                        })
                        add(buildJsonObject {
                            put("configCode", "appointment")
                            put("configName", "预约")
                            put("total", 2)
                            put("enabled", true)
                        })
                    },
                    selectedIndex = 1,
                    modifier = Modifier.requiredSize(720.dp, 200.dp),
                )
            }
        }

        rule.onNodeWithText("案件 4").assertDoesNotExist()
        rule.onNodeWithTag(WorkbenchTags.statisticItem(1)).assertIsSelected()
    }

    @Test
    fun `sort control emits summary ids in the new order`() {
        var sorted = emptyList<String>()
        rule.setContent {
            HuitaiBusinessTheme {
                DataStatisticsCard(
                    data = buildJsonArray {
                        add(buildJsonObject { put("id", "1007"); put("configName", "案件"); put("enabled", true) })
                        add(buildJsonObject { put("id", "1006"); put("configName", "预约"); put("enabled", true) })
                    },
                    order = listOf("1007", "1006"),
                    onSortChange = { sorted = it },
                    modifier = Modifier.requiredSize(720.dp, 200.dp),
                )
            }
        }

        rule.onNodeWithTag("business-workbench-statistic-sort-down-1007").performClick()
        rule.runOnIdle { assertEquals(listOf("1006", "1007"), sorted) }
    }

    @Test
    fun `single disabled summary object is filtered like an array entry`() {
        rule.setContent {
            HuitaiBusinessTheme {
                DataStatisticsCard(
                    data = buildJsonObject {
                        put("id", "1007")
                        put("configName", "案件")
                        put("total", 4)
                        put("enabled", false)
                    },
                    modifier = Modifier.requiredSize(720.dp, 200.dp),
                )
            }
        }

        rule.onNodeWithText("案件 4").assertDoesNotExist()
        rule.onNodeWithTag(WorkbenchTags.STATISTICS_EMPTY).assertExists()
    }
}
