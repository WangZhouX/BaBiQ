package com.wzx.babiq.desktop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class CapabilityModelsTest {

	@Test
	fun `能力目录模型可以解析后端响应`() {
		val result = Json.decodeFromString(
			CapabilityStatusResult.serializer(),
			"""
			{
			  "totalCount":1,
			  "enabledCount":1,
			  "visibleCount":0,
			  "deferredCount":1,
			  "disabledCount":0,
			  "capabilities":[
			    {
			      "capabilityId":"mcp.fs.read",
			      "type":"MCP_TOOL",
			      "namespace":"fs",
			      "name":"read",
			      "displayName":"read",
			      "description":"读取文件",
			      "exposureMode":"DEFERRED",
			      "enabled":true
			    }
			  ]
			}
			""".trimIndent(),
		)

		assertEquals(1, result.deferredCount)
		assertEquals("mcp.fs.read", result.capabilities.single().capabilityId)
	}
}
