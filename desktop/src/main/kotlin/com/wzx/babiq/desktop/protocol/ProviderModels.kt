package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable

@Serializable
data class ProviderListResult(
	val providers: List<ProviderInfo> = emptyList(),
)

@Serializable
data class ProviderInfo(
	val id: String,
	val label: String,
	val active: Boolean = false,
	val models: List<ModelInfo> = emptyList(),
)

@Serializable
data class ModelInfo(
	val id: String,
	val label: String = id,
	val active: Boolean = false,
)

@Serializable
data class SetActiveProviderParams(
	val providerId: String,
	val modelId: String? = null,
)
