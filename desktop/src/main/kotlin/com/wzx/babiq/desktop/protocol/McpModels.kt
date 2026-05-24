package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP server 列表响应。
 *
 * @property servers 后端当前已配置的本地 MCP server 状态列表。
 */
@Serializable
data class McpServersListResult(
	val servers: List<McpServerInfo> = emptyList(),
)

/**
 * MCP server 状态。
 *
 * @property serverId server 稳定标识。
 * @property displayName 用户可读名称。
 * @property transport 传输类型，P2-6 只显示 stdio。
 * @property enabled 是否启用。
 * @property status 当前状态，disabled、configured、connected 或 failed。
 * @property toolCount 当前已发现的工具数量。
 * @property lastError 最近一次连接或刷新失败原因。
 */
@Serializable
data class McpServerInfo(
	val serverId: String,
	val displayName: String,
	val transport: String = "stdio",
	val enabled: Boolean = false,
	val status: String = "disabled",
	val toolCount: Int = 0,
	val lastError: String? = null,
)

/**
 * MCP 工具列表响应。
 *
 * @property serverId 所属 server。
 * @property tools 当前 server 暴露的工具列表。
 */
@Serializable
data class McpToolsListResult(
	val serverId: String,
	val tools: List<McpToolInfo> = emptyList(),
)

/**
 * MCP 工具描述。
 *
 * @property serverId 工具所属 server。
 * @property toolName MCP server 原始工具名。
 * @property namespacedName BaBiQ 内部工具名。
 * @property description 工具说明。
 * @property inputSchema MCP 工具入参 schema，设置页只做只读展示。
 * @property enabled 是否启用。
 */
@Serializable
data class McpToolInfo(
	val serverId: String,
	val toolName: String,
	val namespacedName: String,
	val description: String = "",
	val inputSchema: JsonElement = buildJsonObject { put("type", "object") },
	val enabled: Boolean = true,
)

/**
 * MCP server 刷新请求参数。
 *
 * @property serverId 要刷新的 server 标识。
 */
@Serializable
data class McpServerRefreshParams(
	val serverId: String,
)

/**
 * MCP server 刷新响应。
 *
 * @property server 刷新后的 server 状态。
 */
@Serializable
data class McpServerRefreshResult(
	val server: McpServerInfo,
)
