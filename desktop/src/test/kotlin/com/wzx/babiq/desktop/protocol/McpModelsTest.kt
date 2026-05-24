package com.wzx.babiq.desktop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * MCP 协议模型解码测试。
 *
 * 后端返回的字段必须能被桌面端稳定解析，否则设置页只能显示空占位。
 */
class McpModelsTest {

	@Test
	fun `servers result 可以解析状态和错误`() {
		val result = protocolJson.decodeFromString(
			McpServersListResult.serializer(),
			"""
			{
			  "servers": [
			    {
			      "serverId": "local-filesystem",
			      "displayName": "本地文件 MCP",
			      "transport": "stdio",
			      "enabled": true,
			      "status": "failed",
			      "toolCount": 0,
			      "lastError": "boom"
			    }
			  ]
			}
			""".trimIndent(),
		)

		assertEquals("local-filesystem", result.servers.single().serverId)
		assertEquals("failed", result.servers.single().status)
		assertEquals("boom", result.servers.single().lastError)
	}

	@Test
	fun `tools result 可以解析工具 schema`() {
		val result = protocolJson.decodeFromString(
			McpToolsListResult.serializer(),
			"""
			{
			  "serverId": "local-filesystem",
			  "tools": [
			    {
			      "serverId": "local-filesystem",
			      "toolName": "read_file",
			      "namespacedName": "mcp.local-filesystem.read_file",
			      "description": "Read file",
			      "inputSchema": {"type": "object"},
			      "enabled": true
			    }
			  ]
			}
			""".trimIndent(),
		)

		assertEquals("local-filesystem", result.serverId)
		assertEquals("read_file", result.tools.single().toolName)
		assertEquals("mcp.local-filesystem.read_file", result.tools.single().namespacedName)
		assertEquals("object", result.tools.single().inputSchema.jsonObject["type"]?.jsonPrimitive?.content)
	}
}
