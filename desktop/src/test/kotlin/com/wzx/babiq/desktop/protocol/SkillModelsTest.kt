package com.wzx.babiq.desktop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class SkillModelsTest {

	@Test
	fun `Skill 模型可以解析 metadata 与正文`() {
		val result = Json.decodeFromString(
			SkillGetResult.serializer(),
			"""
			{
			  "skill":{
			    "id":"local.context",
			    "namespace":"local",
			    "name":"context",
			    "description":"上下文治理",
			    "sourceDirectory":"E:/skills",
			    "skillFile":"E:/skills/context/SKILL.md",
			    "contentHash":"hash"
			  },
			  "content":"# Context",
			  "truncated":false
			}
			""".trimIndent(),
		)

		assertEquals("context", result.skill.name)
		assertEquals("# Context", result.content)
	}

	@Test
	fun `Skill 模型可以兼容后端新增 allowedTools 字段`() {
		val result = Json.decodeFromString(
			SkillListResult.serializer(),
			"""
			{
			  "skills":[
			    {
			      "id":"local.context",
			      "namespace":"local",
			      "name":"context",
			      "description":"上下文治理",
			      "sourceDirectory":"E:/skills",
			      "skillFile":"E:/skills/context/SKILL.md",
			      "contentHash":"hash",
			      "allowedTools":["read_file","list_dir"]
			    }
			  ]
			}
			""".trimIndent(),
		)

		assertEquals("local.context", result.skills.single().id)
	}
}
