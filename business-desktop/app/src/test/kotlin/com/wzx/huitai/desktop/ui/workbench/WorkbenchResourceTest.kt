package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import com.wzx.huitai.desktop.workbench.BusinessScheduleItem
import com.wzx.huitai.desktop.workbench.BusinessScheduleState
import java.security.MessageDigest
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.jetbrains.skia.Image

class WorkbenchResourceTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `schedule bitmap is the exact reachable OA asset`() {
        val bytes = BusinessWorkbenchAssets.read(BusinessWorkbenchAssets.SCHEDULE_ICON_PATH)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02X".format(it) }

        assertEquals(550, bytes.size)
        assertEquals("91D12B9B8157D00E3E69B22306D0CA723E34969F054F982D889252D88F163132", digest)
        Image.makeFromEncoded(bytes).use { image ->
            assertEquals(20, image.width)
            assertEquals(20, image.height)
        }
        render(
            BusinessScheduleState(
                items = listOf(BusinessScheduleItem("schedule-1", "客户会议", "10:00", false)),
            ),
        )
        rule.onNodeWithTag(ScheduleTags.ICON).assertIsDisplayed()
    }

    @Test
    fun `schedule empty bitmap is the exact reachable OA asset`() {
        val bytes = BusinessWorkbenchAssets.read(BusinessWorkbenchAssets.SCHEDULE_EMPTY_PATH)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02X".format(it) }

        assertEquals(12_326, bytes.size)
        assertEquals("D0D31FB48B4CFA4FF19E4EAC6452C55356A10E5A9E753A622229BADDC703DCF7", digest)
        Image.makeFromEncoded(bytes).use { image ->
            assertEquals(280, image.width)
            assertEquals(306, image.height)
        }
        render(BusinessScheduleState())
        rule.onNodeWithTag(ScheduleTags.ICON).assertIsDisplayed()
        rule.onNodeWithTag(ScheduleTags.EMPTY_IMAGE).assertIsDisplayed()
    }

    private fun render(state: BusinessScheduleState) {
        rule.setContent {
            HuitaiBusinessTheme {
                SchedulePanel(
                    state = state,
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
    }
}
