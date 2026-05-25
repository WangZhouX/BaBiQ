package com.wzx.babiq.desktop.ui

import com.wzx.babiq.desktop.ui.approval.approvalArgumentRows
import com.wzx.babiq.desktop.ui.approval.approvalToolLabel
import kotlin.test.Test
import kotlin.test.assertEquals

class ApprovalDialogFormattingTest {
	@Test
	fun `审批工具名使用中文标签展示`() {
		assertEquals("写入文件", approvalToolLabel("write_file"))
		assertEquals("执行命令", approvalToolLabel("exec_shell"))
		assertEquals("read_file", approvalToolLabel("read_file"))
	}

	@Test
	fun `审批参数只展示关键字段摘要`() {
		val rows = approvalArgumentRows(
			"""{"path":"hello.html","content":"<!DOCTYPE html>\n<html><body>你好</body></html>","extra":"ignored"}""",
		)

		assertEquals(listOf("path" to "hello.html", "content" to "<!DOCTYPE html> <html><body>你好</body></html>"), rows)
	}
}
