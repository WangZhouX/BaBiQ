package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test

class QuickEntranceCardTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `shows ten enabled entries per page loops pages and refuses unsafe paths`() {
        val opened = mutableListOf<String>()
        rule.setContent {
            HuitaiBusinessTheme {
                QuickEntranceCard(
                    data = buildJsonArray {
                        add(buildJsonObject {
                            put("title", "禁用入口")
                            put("path", "/case")
                            put("enabled", false)
                        })
                        repeat(12) { index ->
                            add(buildJsonObject {
                                put("title", "入口 ${index + 1}")
                                put("path", if (index == 10) "/appointment" else "/case")
                                put("enabled", true)
                            })
                        }
                    },
                    onOpen = opened::add,
                    modifier = Modifier.requiredSize(720.dp, 360.dp),
                )
            }
        }

        rule.onNodeWithText("禁用入口").assertDoesNotExist()
        rule.onNodeWithTag(WorkbenchTags.quickItem(9)).fetchSemanticsNode()
        rule.onNodeWithTag(WorkbenchTags.quickItem(10)).assertDoesNotExist()
        rule.onNodeWithTag("business-workbench-quick-next").performClick()
        rule.onNodeWithText("入口 11").assertDoesNotExist()
        rule.onNodeWithTag(WorkbenchTags.quickItem(0)).performClick()
        rule.onNodeWithTag("business-workbench-quick-previous").performClick()
        rule.onNodeWithTag(WorkbenchTags.quickItem(0)).performClick()

        rule.runOnIdle {
            assertEquals(listOf("/case", "/case"), opened)
        }
    }

    @Test
    fun `sort control emits the reordered canonical ids`() {
        var sorted = emptyList<String>()
        rule.setContent {
            HuitaiBusinessTheme {
                QuickEntranceCard(
                    data = buildJsonArray {
                        add(buildJsonObject {
                            put("id", "disabled")
                            put("title", "禁用")
                            put("path", "/case")
                            put("enabled", false)
                        })
                        add(buildJsonObject { put("id", "one"); put("title", "一"); put("path", "/case") })
                        add(buildJsonObject { put("id", "two"); put("title", "二"); put("path", "/team") })
                    },
                    order = listOf("one", "two"),
                    onSortChange = { sorted = it },
                    modifier = Modifier.requiredSize(720.dp, 360.dp),
                )
            }
        }

        rule.onNodeWithTag("business-workbench-quick-sort-down-one").performClick()
        rule.runOnIdle { assertEquals(listOf("two", "one"), sorted) }
    }
}
