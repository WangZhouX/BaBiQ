package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString

@Serializable
data class WorkUnitListParams(
	val threadId: String,
)

@Serializable
data class WorkUnitRemoveParams(
	val workUnitId: String,
)

@Serializable
data class WorkUnitGoalUpdateParams(
	val threadId: String,
	val workUnitId: String,
	val goalId: String,
	val goalText: String,
)

@Serializable
data class WorkUnitConfigUpdateParams(
	val threadId: String,
	val workUnitId: String,
	val configJson: String,
	val structureJson: String? = null,
)

@Serializable
data class WorkUnitConfiguration(
	val topology: String = "sequential",
	val nodes: List<WorkUnitConfigEntry> = emptyList(),
	val members: List<WorkUnitConfigEntry> = emptyList(),
	val structure: FlowStructureDto? = null,
)

@Serializable
data class WorkUnitConfigEntry(
	val id: String,
	val name: String? = null,
	val role: String? = null,
	val task: String? = null,
	val model: String? = null,
	val mode: String? = null,
)

@Serializable
data class WorkUnitGoalInfo(
	val goalId: String,
	val workUnitId: String,
	val goalText: String,
	val status: String,
	val runRefType: String? = null,
	val runRefId: String? = null,
	val summary: String? = null,
	val errorMessage: String? = null,
	val createdAt: String? = null,
	val startedAt: String? = null,
	val completedAt: String? = null,
)

@Serializable
data class WorkUnitInfo(
	val workUnitId: String,
	val threadId: String,
	val kind: String,
	val name: String,
	val status: String,
	val currentGoalId: String? = null,
	val cwd: String? = null,
	val sandboxMode: String? = null,
	val removed: Boolean = false,
	val updatedAt: String? = null,
	val configJson: String? = null,
	val structureJson: String? = null,
	val goals: List<WorkUnitGoalInfo> = emptyList(),
) {
	val configuration: WorkUnitConfiguration?
		get() = decodeWorkUnitConfiguration(configJson)

	val structure: FlowStructureDto?
		get() = decodeFlowStructure(structureJson) ?: configuration?.structure

	fun toThreadItem(): ThreadItem.WorkUnit {
		val currentGoal = goals.lastOrNull { goal -> goal.goalId == currentGoalId }
			?: goals.lastOrNull()
		return ThreadItem.WorkUnit(
			id = "it_workunit_$workUnitId",
			workUnitId = workUnitId,
			kind = kind,
			name = name,
			status = status,
			currentGoalId = currentGoal?.goalId ?: currentGoalId,
			currentGoal = currentGoal?.goalText,
			goalCount = goals.size,
			removed = removed,
		)
	}
}

@Serializable
data class WorkUnitListResult(
	val workUnits: List<WorkUnitInfo> = emptyList(),
)

@Serializable
data class WorkUnitRemoveResult(
	val workUnitId: String,
	val kind: String,
	val name: String,
	val status: String,
	val removed: Boolean,
) {
	fun toThreadItem(): ThreadItem.WorkUnit =
		ThreadItem.WorkUnit(
			id = "it_workunit_$workUnitId",
			workUnitId = workUnitId,
			kind = kind,
			name = name,
			status = status,
			removed = removed,
	)
}

@Serializable
data class WorkUnitGoalUpdateResult(
	val updatedGoal: WorkUnitGoalInfo,
	val workUnit: WorkUnitInfo,
)

@Serializable
data class WorkUnitConfigUpdateResult(
	val workUnit: WorkUnitInfo,
)

private fun decodeWorkUnitConfiguration(configJson: String?): WorkUnitConfiguration? {
	val json = configJson?.takeIf { it.isNotBlank() } ?: return null
	return try {
		protocolJson.decodeFromString<WorkUnitConfiguration>(json)
	} catch (_: SerializationException) {
		null
	} catch (_: IllegalArgumentException) {
		null
	}
}

private fun decodeFlowStructure(structureJson: String?): FlowStructureDto? {
	val json = structureJson?.takeIf { it.isNotBlank() } ?: return null
	return try {
		protocolJson.decodeFromString<FlowStructureDto>(json)
	} catch (_: SerializationException) {
		null
	} catch (_: IllegalArgumentException) {
		null
	}
}
