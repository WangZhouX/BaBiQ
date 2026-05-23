package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable

@Serializable
data class ApprovalRequestPayload(
	val threadId: String,
	val turnId: String,
	val itemId: String,
	val toolName: String,
	val arguments: String,
	val description: String,
)

@Serializable
data class ApprovalRespondParams(
	val threadId: String,
	val turnId: String,
	val decision: String,
	val editedArgs: String? = null,
)
