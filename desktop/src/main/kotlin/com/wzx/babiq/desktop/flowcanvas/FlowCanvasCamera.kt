package com.wzx.babiq.desktop.flowcanvas

/**
 * FlowCanvasCamera 保存画布内容相对视口的缩放和平移状态。
 *
 * 这里刻意不依赖 Compose 的 Offset/Size 类型，方便用普通单元测试锁定坐标换算。
 */
data class FlowCanvasCamera(
	val scale: Float = 1f,
	val offsetX: Float = 0f,
	val offsetY: Float = 0f,
) {
	/** 把屏幕横坐标反算为未变换前的画布横坐标。 */
	fun worldX(screenX: Float): Float = (screenX - offsetX) / scale

	/** 把屏幕纵坐标反算为未变换前的画布纵坐标。 */
	fun worldY(screenY: Float): Float = (screenY - offsetY) / scale

	/**
	 * 以鼠标光标为锚点缩放。
	 *
	 * 缩放后，光标正下方对应的世界坐标保持不变，用户不会感觉画布被突然甩开。
	 */
	fun zoomAt(cursorX: Float, cursorY: Float, scaleMultiplier: Float): FlowCanvasCamera {
		val nextScale = (scale * scaleMultiplier).coerceIn(MIN_SCALE, MAX_SCALE)
		val anchoredWorldX = worldX(cursorX)
		val anchoredWorldY = worldY(cursorY)
		return copy(
			scale = nextScale,
			offsetX = cursorX - anchoredWorldX * nextScale,
			offsetY = cursorY - anchoredWorldY * nextScale,
		)
	}

	/** 按屏幕拖拽量平移画布内容。 */
	fun pan(deltaX: Float, deltaY: Float): FlowCanvasCamera =
		copy(offsetX = offsetX + deltaX, offsetY = offsetY + deltaY)

	companion object {
		const val MIN_SCALE: Float = 0.4f
		const val MAX_SCALE: Float = 2.0f
	}
}
