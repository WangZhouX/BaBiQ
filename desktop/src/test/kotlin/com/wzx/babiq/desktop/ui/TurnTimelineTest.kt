package com.wzx.babiq.desktop.ui

import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.state.ChatMessage
import com.wzx.babiq.desktop.ui.chat.TimelineItem
import com.wzx.babiq.desktop.ui.chat.deriveTurnTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TurnTimelineTest {
	@Test
	fun `completed turn keeps final answer before folded process card`() {
		val messages = listOf(
			ChatMessage.User("u1", "查看当前目录"),
			ChatMessage.Reasoning("r1", "先列目录，再读取唯一文件。", completed = true),
			ChatMessage.Tool(
				id = "tool-list",
				title = "list_dir path=H:\\aaa",
				status = "completed",
				detail = """<untrusted-data source="tool:list_dir">["index.html"]</untrusted-data>""",
			),
			ChatMessage.FileChange(
				id = "file-read",
				action = "read",
				path = "H:\\aaa\\index.html",
				status = "completed",
				preview = "大家好",
			),
			ChatMessage.Agent("a1", "当前目录只有 index.html，内容为“大家好”。"),
			ChatMessage.TurnSummary("s1", summary(toolCalls = 2)),
		)

		val timeline = deriveTurnTimeline(messages)

		assertEquals(4, timeline.size)
		assertIs<TimelineItem.Message>(timeline[0])
		assertIs<TimelineItem.Message>(timeline[1])
		val process = assertIs<TimelineItem.Process>(timeline[2])
		assertEquals("本轮工作过程 · 3 步", process.title)
		assertFalse(process.expandedByDefault)
		assertEquals(listOf("推理", "列出目录 H:\\aaa", "读取文件 H:\\aaa\\index.html"), process.rows.map { it.summary })
		assertEquals("先列目录，再读取唯一文件。", process.rows[0].detail)
		assertEquals("index.html", process.rows[1].detail)
		assertIs<TimelineItem.Message>(timeline[3])
	}

	@Test
	fun `tool process detail unwraps spotlight tags and prefers output or error text`() {
		val messages = listOf(
			ChatMessage.User("u1", "启动编排"),
			ChatMessage.Tool(
				id = "tool-ok",
				title = "work_unit_manage",
				status = "completed",
				detail = """<untrusted-data source="tool:work_unit_manage">{"ok":true,"output":"已准备工作容器，尚未启动真实执行。","truncated":false}</untrusted-data>""",
			),
			ChatMessage.Tool(
				id = "tool-error",
				title = "orchestrate_flow",
				status = "completed",
				detail = """<untrusted-data source="tool:orchestrate_flow">"Flow failed: Resume request without a valid checkpoint!"</untrusted-data>""",
			),
			ChatMessage.Tool(
				id = "tool-fail",
				title = "work_unit_manage",
				status = "failed",
				detail = """<untrusted-data source="tool:work_unit_manage">{"ok":false,"output":"","error":"工作容器没有可启动的待处理目标。","truncated":false}</untrusted-data>""",
			),
		)

		val process = deriveTurnTimeline(messages).filterIsInstance<TimelineItem.Process>().single()

		assertEquals("已准备工作容器，尚未启动真实执行。", process.rows[0].detail)
		assertEquals("Flow failed: Resume request without a valid checkpoint!", process.rows[1].detail)
		assertEquals("错误：工作容器没有可启动的待处理目标。", process.rows[2].detail)
		assertFalse(process.rows.any { it.detail.contains("<untrusted-data") || it.detail.contains("</untrusted-data>") })
	}

	@Test
	fun `running turn expands process card and keeps live assistant output inside process`() {
		val messages = listOf(
			ChatMessage.User("u1", "分析登录页"),
			ChatMessage.Reasoning("r1", "正在检查页面结构", completed = false),
			ChatMessage.Tool(
				id = "tool-read",
				title = "read_file path=H:\\aaa\\index.html",
				status = "running",
				detail = "等待工具返回",
			),
			ChatMessage.Agent("a-live", "我正在读取页面...", streaming = true),
		)

		val timeline = deriveTurnTimeline(messages)

		assertEquals(2, timeline.size)
		val process = assertIs<TimelineItem.Process>(timeline[1])
		assertEquals("正在处理 · 3 步", process.title)
		assertTrue(process.expandedByDefault)
		assertEquals(listOf("推理", "读取文件 H:\\aaa\\index.html", "输出草稿"), process.rows.map { it.summary })
		assertEquals("我正在读取页面...", process.rows.last().detail)
	}

	@Test
	fun `failed tool expands process card by default`() {
		val messages = listOf(
			ChatMessage.User("u1", "运行命令"),
			ChatMessage.Tool(
				id = "tool-exec",
				title = "exec_shell command=git status",
				status = "failed",
				detail = "fatal: not a git repository",
			),
			ChatMessage.TurnSummary("s1", summary(status = "failed", toolCalls = 1)),
		)

		val timeline = deriveTurnTimeline(messages)

		val process = assertIs<TimelineItem.Process>(timeline[1])
		assertEquals("本轮工作过程 · 1 步", process.title)
		assertTrue(process.expandedByDefault)
		assertEquals("执行命令 git status", process.rows.single().summary)
	}

	private fun summary(status: String = "completed", toolCalls: Int = 0): ThreadItem.TurnSummary =
		ThreadItem.TurnSummary(
			id = "summary-$status-$toolCalls",
			status = status,
			model = "deepseek-v4-pro",
			totalTokens = 120,
			toolCalls = toolCalls,
			durationMs = 1500,
		)
}
