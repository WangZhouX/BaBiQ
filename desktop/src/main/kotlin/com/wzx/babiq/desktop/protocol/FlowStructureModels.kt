package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable

@Serializable
data class FlowStructureDto(
	val root: FlowEntryDto,
)

@Serializable
data class FlowEntryDto(
	val nodeId: String? = null,
	val groupId: String? = null,
	val topology: String? = null,
	val children: List<FlowEntryDto> = emptyList(),
) {
	val isNodeRef: Boolean
		get() = !nodeId.isNullOrBlank()

	val isGroup: Boolean
		get() = !groupId.isNullOrBlank()
}
