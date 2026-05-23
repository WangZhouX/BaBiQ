package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable

/**
 * 后端 `model/providers/list` 的响应体。
 *
 * @property providers 后端可用的 provider 列表，UI 下拉框直接基于它渲染。
 */
@Serializable
data class ProviderListResult(
	val providers: List<ProviderInfo> = emptyList(),
)

/**
 * 一个模型供应商，例如 DashScope 或 OpenAI-compatible provider。
 *
 * @property id 后端唯一 provider id，切换模型时要把它传回后端。
 * @property label 给用户看的供应商名称。
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
 *
 * @property id 后端识别的模型 id。
 * @property label 给用户看的模型名称，默认等于 id。
 * @property active 当前 provider 下是否选中这个模型。
 */
@Serializable
data class ModelInfo(
	val id: String,
	val label: String = id,
	val active: Boolean = false,
)

/**
 * 桌面端切换 active provider/model 时发送给后端的参数。
 *
 * @property providerId 要切换到的 provider id。
 * @property modelId provider 下的模型 id；为空时使用 provider 默认模型。
 */
@Serializable
data class SetActiveProviderParams(
	val providerId: String,
	val modelId: String? = null,
)
