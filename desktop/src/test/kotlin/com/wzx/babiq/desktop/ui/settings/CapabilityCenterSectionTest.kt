package com.wzx.babiq.desktop.ui.settings

import com.wzx.babiq.desktop.protocol.CapabilityInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapabilityCenterSectionTest {

	@Test
	fun `能力中心按类型和暴露模式分组`() {
		val groups = groupCapabilitiesForCenter(
			listOf(
				CapabilityInfo("local.read_file", "LOCAL_TOOL", "local", "read_file", "读取文件", "Read", "VISIBLE", true),
				CapabilityInfo("mcp.fs.read_text_file", "MCP_TOOL", "mcp.fs", "read_text_file", "读取文本", "Read text", "DEFERRED", true),
				CapabilityInfo("skill.plan", "SKILL", "skill", "plan", "计划", "Plan", "DISABLED", false),
			),
		)

		assertEquals(1, groups.local.size)
		assertEquals(1, groups.mcp.size)
		assertEquals(1, groups.skills.size)
		assertTrue(capabilityExampleQueries().contains("读取文件"))
		assertTrue(capabilityExampleQueries().contains("运行命令"))
	}
}
