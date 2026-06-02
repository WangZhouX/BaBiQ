package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable

/** 本地 Skill metadata。 */
@Serializable
data class SkillInfo(
	val id: String,
	val namespace: String,
	val name: String,
	val description: String,
	val sourceDirectory: String,
	val skillFile: String,
	val contentHash: String,
	val allowedTools: List<String> = emptyList(),
)

/** skills/list 响应。 */
@Serializable
data class SkillListResult(
	val skills: List<SkillInfo> = emptyList(),
)

/** skills/get 响应。 */
@Serializable
data class SkillGetResult(
	val skill: SkillInfo,
	val content: String,
	val truncated: Boolean = false,
)
