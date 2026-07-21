package com.wzx.huitai.desktop.ui.layout

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusinessDesktopLayoutPolicyTest {
    @Test
    fun `docked assistant constants match the business width contract`() {
        assertEquals(640.dp, BusinessDesktopLayoutPolicy.minimumBusinessWidth)
        assertEquals(360.dp, BusinessDesktopLayoutPolicy.minimumAssistantWidth)
        assertEquals(720.dp, BusinessDesktopLayoutPolicy.maximumAssistantWidth)
        assertEquals(8.dp, BusinessDesktopLayoutPolicy.dividerWidth)
        assertEquals(460.dp, BusinessDesktopLayoutPolicy.defaultAssistantWidth)
        assertEquals(1008.dp, BusinessDesktopLayoutPolicy.expandThreshold)
    }

    @Test
    fun `collapsed assistant releases the full non-negative width to business content`() {
        listOf(0.dp, 900.dp, 1008.dp, 1600.dp).forEach { availableWidth ->
            val layout = BusinessDesktopLayoutPolicy.resolveDocked(
                availableWidth = availableWidth,
                assistantExpanded = false,
            )

            assertEquals(availableWidth, layout.businessWidth)
            assertEquals(0.dp, layout.dividerWidth)
            assertEquals(0.dp, layout.assistantWidth)
            assertFalse(layout.assistantExpanded)
        }
    }

    @Test
    fun `exact threshold expands to minimum assistant beside minimum business content`() {
        val layout = BusinessDesktopLayoutPolicy.resolveDocked(
            availableWidth = 1008.dp,
            assistantExpanded = true,
        )

        assertTrue(layout.canExpand)
        assertTrue(layout.assistantExpanded)
        assertEquals(640.dp, layout.businessWidth)
        assertEquals(8.dp, layout.dividerWidth)
        assertEquals(360.dp, layout.assistantWidth)
        assertEquals(1008.dp, layout.businessWidth + layout.dividerWidth + layout.assistantWidth)
    }

    @Test
    fun `expanded assistant clamps requested width to static and dynamic bounds`() {
        val minimum = BusinessDesktopLayoutPolicy.resolveDocked(
            availableWidth = 1600.dp,
            assistantExpanded = true,
            requestedAssistantWidth = 100.dp,
        )
        val default = BusinessDesktopLayoutPolicy.resolveDocked(
            availableWidth = 1600.dp,
            assistantExpanded = true,
        )
        val maximum = BusinessDesktopLayoutPolicy.resolveDocked(
            availableWidth = 1368.dp,
            assistantExpanded = true,
            requestedAssistantWidth = 900.dp,
        )
        val dynamicMaximum = BusinessDesktopLayoutPolicy.resolveDocked(
            availableWidth = 1100.dp,
            assistantExpanded = true,
            requestedAssistantWidth = 720.dp,
        )

        assertEquals(360.dp, minimum.assistantWidth)
        assertEquals(460.dp, default.assistantWidth)
        assertEquals(720.dp, maximum.assistantWidth)
        assertEquals(452.dp, dynamicMaximum.assistantWidth)
        listOf(minimum, default, maximum, dynamicMaximum).forEach { layout ->
            assertTrue(layout.businessWidth >= 640.dp)
            assertEquals(
                layout.availableWidth,
                layout.businessWidth + layout.dividerWidth + layout.assistantWidth,
            )
        }
    }

    @Test
    fun `width below exact threshold refuses expansion and safely falls back to collapsed`() {
        val layout = BusinessDesktopLayoutPolicy.resolveDocked(
            availableWidth = 1007.dp,
            assistantExpanded = true,
        )

        assertFalse(layout.canExpand)
        assertFalse(layout.assistantExpanded)
        assertEquals(1007.dp, layout.businessWidth)
        assertEquals(0.dp, layout.dividerWidth)
        assertEquals(0.dp, layout.assistantWidth)
    }

    @Test
    fun `invalid available widths normalize to finite zero width`() {
        listOf(
            (-1).dp,
            Float.NaN.dp,
            Float.POSITIVE_INFINITY.dp,
            Float.NEGATIVE_INFINITY.dp,
        ).forEach { invalidWidth ->
            val layout = BusinessDesktopLayoutPolicy.resolveDocked(
                availableWidth = invalidWidth,
                assistantExpanded = true,
            )

            assertEquals(0.dp, layout.availableWidth)
            assertEquals(0.dp, layout.businessWidth)
            assertEquals(0.dp, layout.dividerWidth)
            assertEquals(0.dp, layout.assistantWidth)
            assertFalse(layout.canExpand)
            assertFalse(layout.assistantExpanded)
            assertTrue(layout.availableWidth.value.isFinite())
        }
    }

    @Test
    fun `invalid requested widths never propagate invalid or negative slot widths`() {
        val invalidRequests = listOf(
            (-1).dp,
            Float.NaN.dp,
            Float.POSITIVE_INFINITY.dp,
            Float.NEGATIVE_INFINITY.dp,
        )

        invalidRequests.forEach { invalidRequest ->
            val layout = BusinessDesktopLayoutPolicy.resolveDocked(
                availableWidth = 1600.dp,
                assistantExpanded = true,
                requestedAssistantWidth = invalidRequest,
            )

            assertTrue(layout.businessWidth.value.isFinite())
            assertTrue(layout.assistantWidth.value.isFinite())
            assertTrue(layout.businessWidth >= 640.dp)
            assertTrue(layout.assistantWidth in 360.dp..720.dp)
            assertEquals(
                1600.dp,
                layout.businessWidth + layout.dividerWidth + layout.assistantWidth,
            )
        }
    }

    @Test
    fun `resize uses dp delta and dragging divider left grows assistant`() {
        assertEquals(
            560.dp,
            BusinessDesktopLayoutPolicy.resizeAssistantWidth(
                current = 460.dp,
                dragDeltaX = (-100).dp,
                availableWidth = 1600.dp,
            ),
        )
        assertEquals(
            360.dp,
            BusinessDesktopLayoutPolicy.resizeAssistantWidth(
                current = 460.dp,
                dragDeltaX = 200.dp,
                availableWidth = 1600.dp,
            ),
        )
    }

    @Test
    fun `resize obeys dynamic maximum and returns zero when expansion is unavailable`() {
        assertEquals(
            452.dp,
            BusinessDesktopLayoutPolicy.resizeAssistantWidth(
                current = 460.dp,
                dragDeltaX = (-100).dp,
                availableWidth = 1100.dp,
            ),
        )
        assertEquals(
            0.dp,
            BusinessDesktopLayoutPolicy.resizeAssistantWidth(
                current = 460.dp,
                dragDeltaX = (-100).dp,
                availableWidth = 1007.dp,
            ),
        )
    }

    @Test
    fun `resize normalizes invalid current delta and available values`() {
        assertEquals(
            460.dp,
            BusinessDesktopLayoutPolicy.resizeAssistantWidth(
                current = Float.NaN.dp,
                dragDeltaX = Float.POSITIVE_INFINITY.dp,
                availableWidth = 1600.dp,
            ),
        )
        assertEquals(
            0.dp,
            BusinessDesktopLayoutPolicy.resizeAssistantWidth(
                current = (-100).dp,
                dragDeltaX = Float.NaN.dp,
                availableWidth = Float.NaN.dp,
            ),
        )
    }
}
