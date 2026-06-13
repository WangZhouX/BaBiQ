package com.wzx.babiq.desktop.flowcanvas

import kotlin.test.Test
import kotlin.test.assertEquals

class FlowCanvasCameraTest {

	@Test
	fun `zoomAt keeps world coordinate under cursor stable`() {
		val camera = FlowCanvasCamera(scale = 1.2f, offsetX = 40f, offsetY = -16f)
		val beforeX = camera.worldX(screenX = 160f)
		val beforeY = camera.worldY(screenY = 92f)

		val zoomed = camera.zoomAt(cursorX = 160f, cursorY = 92f, scaleMultiplier = 1.25f)

		assertEquals(beforeX, zoomed.worldX(screenX = 160f), absoluteTolerance = 0.001f)
		assertEquals(beforeY, zoomed.worldY(screenY = 92f), absoluteTolerance = 0.001f)
		assertEquals(1.5f, zoomed.scale, absoluteTolerance = 0.001f)
	}

	@Test
	fun `zoomAt clamps scale to editor limits`() {
		val tiny = FlowCanvasCamera(scale = 0.5f).zoomAt(cursorX = 0f, cursorY = 0f, scaleMultiplier = 0.1f)
		val huge = FlowCanvasCamera(scale = 1.8f).zoomAt(cursorX = 0f, cursorY = 0f, scaleMultiplier = 5f)

		assertEquals(0.4f, tiny.scale, absoluteTolerance = 0.001f)
		assertEquals(2.0f, huge.scale, absoluteTolerance = 0.001f)
	}

	@Test
	fun `pan accumulates screen offset`() {
		val camera = FlowCanvasCamera(scale = 1f, offsetX = 4f, offsetY = 6f)

		val panned = camera.pan(deltaX = -12f, deltaY = 18f)

		assertEquals(-8f, panned.offsetX, absoluteTolerance = 0.001f)
		assertEquals(24f, panned.offsetY, absoluteTolerance = 0.001f)
	}
}
