package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 用户在输入框中显式选择的执行意图。
 *
 * 它是 `turn/start` 的旁路元数据，不替代用户原始输入文本；后端用它创建或关联工作容器。
 */
@Serializable
sealed interface ExecutionIntent {
	/**
	 * 创建或复用一个命名工作容器，并追加一个待配置/待启动的目标。
	 */
	@Serializable
	@SerialName("create_work_unit")
	data class CreateWorkUnit(
		val kind: String,
		val name: String,
		val goal: String,
		val goalId: String? = null,
	) : ExecutionIntent
}
