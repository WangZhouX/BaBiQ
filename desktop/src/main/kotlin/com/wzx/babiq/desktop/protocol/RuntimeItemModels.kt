package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable

@Serializable
data class RuntimeItemRemoveParams(
	val itemId: String,
	val type: String,
)

@Serializable
data class RuntimeItemRemoveResult(
	val itemId: String,
	val type: String,
	val status: String,
	val removed: Boolean,
)
