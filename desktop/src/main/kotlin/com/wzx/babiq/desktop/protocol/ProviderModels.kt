package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable

/**
 * 后端 `model/providers/list` 的响应体。
 */
@Serializable
data class ProviderListResult(
	val providers: List<ProviderInfo> = emptyList(),
)

/**
 * 一个模型供应商，例如 DashScope 或 OpenAI-compatible provider。
 *
 * @property active 后端当前是否把它作为默认 provider。
 * @property models 该 provider 下可选择的具体模型。
 */
@Serializable
data class ProviderInfo(
	val id: String,
	val label: String,
	val active: Boolean = false,
	val models: List<ModelInfo> = emptyList(),
)

/**
 * Provider 下的具体模型配置。
 */
@Serializable
data class ModelInfo(
	val id: String,
	val label: String = id,
	val active: Boolean = false,
)

/**
 * 桌面端切换 active provider/model 时发送给后端的参数。
 */
@Serializable
data class SetActiveProviderParams(
	val providerId: String,
	val modelId: String? = null,
)
