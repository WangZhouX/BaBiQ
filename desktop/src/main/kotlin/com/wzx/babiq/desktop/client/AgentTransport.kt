package com.wzx.babiq.desktop.client

import kotlinx.coroutines.flow.Flow

/**
 * WebSocket 传输层抽象。
 *
 * 这一层只处理“字符串进、字符串出”，故意不认识 JSON-RPC、Thread、Turn 等业务概念。
 * 这样单元测试可以用内存 FakeTransport，真实运行时再用 Ktor 实现。
 */
interface AgentTransport : AutoCloseable {
	/** 后端发来的原始文本帧流。 */
	val incoming: Flow<String>

	/** 建立到底层后端的连接。 */
	suspend fun connect()

	/** 发送一个已经序列化好的 JSON-RPC 文本帧。 */
	suspend fun send(text: String)
}
