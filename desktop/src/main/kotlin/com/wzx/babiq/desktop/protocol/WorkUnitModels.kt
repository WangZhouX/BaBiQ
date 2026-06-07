package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable

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
	val goals: List<WorkUnitGoalInfo> = emptyList(),
) {
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
