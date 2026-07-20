package com.wzx.huitai.desktop.controller

import com.wzx.huitai.agent.client.AgentJsonRpcException
import com.wzx.huitai.agent.conversation.BusinessAgentEvent
import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import com.wzx.huitai.agent.conversation.BusinessConversationGateway
import com.wzx.huitai.agent.conversation.BusinessProvider
import com.wzx.huitai.agent.conversation.BusinessProviderSelection
import com.wzx.huitai.agent.conversation.BusinessThread
import com.wzx.huitai.agent.conversation.BusinessTurn
import com.wzx.huitai.desktop.state.BusinessDesktopEvent
import com.wzx.huitai.desktop.state.BusinessDesktopReducer
import com.wzx.huitai.desktop.state.BusinessDesktopStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

class BusinessConversationControllerTest {
    @Test
    fun `start turn forwards immutable attachment metadata to the gateway`() = runTest {
        val gateway = RecordingGateway()
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        store.dispatch(BusinessDesktopEvent.ThreadChanged(BusinessThread("thread-1", "demo", "C:/demo")))
        val controller = BusinessConversationController(gateway, store, this)
        val attachment = attachment()

        controller.startTurn("check", listOf(attachment), "provider-1")

        assertEquals("check", gateway.text)
        assertEquals(listOf(attachment), gateway.attachments)
        assertEquals("provider-1", gateway.providerId)
        controller.close()
    }

    @Test
    fun `attachment error code maps to actionable Chinese message without remote diagnostics`() = runTest {
        val privatePath = "C:\\Users\\secret\\customer-contract.pdf"
        val gateway = RecordingGateway().apply {
            failure = AgentJsonRpcException(
                remoteCode = -32602,
                attachmentCode = "ATTACHMENT_NOT_FOUND",
            ).also { it.addSuppressed(IllegalStateException("cannot read $privatePath")) }
        }
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        store.dispatch(BusinessDesktopEvent.ThreadChanged(BusinessThread("thread-1", "demo", "C:/demo")))
        val controller = BusinessConversationController(gateway, store, this)

        assertFailsWith<AgentJsonRpcException> {
            controller.startTurn("check", listOf(attachment()), "provider-1")
        }

        assertEquals("TURN_START_FAILED", store.state.value.error?.code)
        assertEquals("附件已不存在，请重新选择后再发送", store.state.value.error?.message)
        check(!store.state.value.error?.message.orEmpty().contains(privatePath))
        check(!store.state.value.error?.message.orEmpty().contains("cannot read"))
        controller.close()
    }

    private class RecordingGateway : BusinessConversationGateway {
        override val events: Flow<BusinessAgentEvent> = emptyFlow()
        var text: String? = null
        var attachments: List<BusinessAttachmentDraft>? = null
        var providerId: String? = null
        var failure: Exception? = null
        override suspend fun listProviders(): List<BusinessProvider> = emptyList()
        override suspend fun setActiveProvider(providerId: String, modelId: String?): BusinessProviderSelection =
            error("unused")
        override suspend fun createThread(cwd: String): BusinessThread = error("unused")
        override suspend fun startTurn(
            threadId: String,
            text: String,
            attachments: List<BusinessAttachmentDraft>,
            providerId: String?,
        ): BusinessTurn {
            failure?.let { throw it }
            this.text = text
            this.attachments = attachments
            this.providerId = providerId
            return BusinessTurn("turn-1", threadId)
        }
        override suspend fun cancelTurn(turnId: String): Boolean = false
        override fun close() = Unit
    }

    private fun attachment(): BusinessAttachmentDraft = BusinessAttachmentDraft(
        id = "00000000-0000-0000-0000-000000000501",
        displayId = "A-BCDEFG",
        name = "合同.pdf",
        localPath = "C:/private/合同.pdf",
        sizeBytes = 5,
        displayType = "PDF",
    )
}
