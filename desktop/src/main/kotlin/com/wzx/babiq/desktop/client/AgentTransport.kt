package com.wzx.babiq.desktop.client

import kotlinx.coroutines.flow.Flow

interface AgentTransport : AutoCloseable {
	val incoming: Flow<String>

	suspend fun connect()

	suspend fun send(text: String)
}
