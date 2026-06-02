package com.wzx.babiq.desktop.state

/**
 * 输入框中的显式斜杠命令。
 *
 * 斜杠命令只表达用户希望创建或复用一个工作容器，不直接代表开始执行。
 */
sealed interface SlashCommand {
	data class WorkUnit(
		val kind: WorkUnitKind,
		val name: String,
		val goal: String,
	) : SlashCommand
}

/**
 * P6-4 支持的工作容器类型。
 */
enum class WorkUnitKind {
	Orchestration,
	Team,
}

/**
 * 解析中文斜杠命令。
 *
 * 支持格式：
 * - /编排 名称：目标
 * - /团队 名称: 目标
 */
object SlashCommandParser {
	private val workUnitPattern = Regex("^/(编排|团队)\\s+([^:：]+)[:：](.+)$")

	fun parse(input: String): SlashCommand? {
		val text = input.trim()
		val match = workUnitPattern.matchEntire(text) ?: return null
		val kind = when (match.groupValues[1]) {
			"编排" -> WorkUnitKind.Orchestration
			"团队" -> WorkUnitKind.Team
			else -> return null
		}
		val name = match.groupValues[2].trim()
		val goal = match.groupValues[3].trim()
		if (name.isBlank() || goal.isBlank()) {
			return null
		}
		return SlashCommand.WorkUnit(kind, name, goal)
	}
}
