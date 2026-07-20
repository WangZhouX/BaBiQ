package com.wzx.huitai.desktop.controller

import com.wzx.huitai.agent.conversation.BusinessConversationGateway
import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import com.wzx.huitai.agent.conversation.BusinessProvider
import com.wzx.huitai.agent.conversation.BusinessProviderSelection
import com.wzx.huitai.agent.conversation.BusinessThread
import com.wzx.huitai.agent.conversation.BusinessTurn
import com.wzx.huitai.desktop.state.BusinessDesktopEvent
import com.wzx.huitai.desktop.state.BusinessDesktopState
import com.wzx.huitai.desktop.state.BusinessDesktopStore
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 只管理 thread/turn/chat；连接、身份和页面发布由其它控制器负责。 */
class BusinessConversationController(
    private val gateway: BusinessConversationGateway,
    private val store: BusinessDesktopStore,
    scope: CoroutineScope,
) : Closeable {
    private val eventCollector: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        gateway.events.collect { store.dispatch(BusinessDesktopEvent.AgentEventReceived(it)) }
    }

    val state: StateFlow<BusinessDesktopState> = store.state

    suspend fun refreshProviders(): List<BusinessProvider> = guarded("PROVIDER_LIST_FAILED") {
        gateway.listProviders().also { store.dispatch(BusinessDesktopEvent.ProvidersChanged(it)) }
    }

    /** 接受设置控制器已加载且通过连接代次校验的 Provider 快照，不发起第二次网络请求。 */
    fun acceptProviders(providers: List<BusinessProvider>) {
        store.dispatch(BusinessDesktopEvent.ProvidersChanged(providers))
    }

    suspend fun selectProvider(providerId: String, modelId: String? = null): BusinessProviderSelection =
        guarded("PROVIDER_SELECTION_FAILED") {
            gateway.setActiveProvider(providerId, modelId).also {
                store.dispatch(BusinessDesktopEvent.ProviderSelected(it))
            }
        }

    suspend fun createThread(cwd: String): BusinessThread = guarded("THREAD_CREATE_FAILED") {
        gateway.createThread(cwd).also { store.dispatch(BusinessDesktopEvent.ThreadChanged(it)) }
    }

    suspend fun startTurn(
        text: String,
        attachments: List<BusinessAttachmentDraft> = emptyList(),
        providerId: String? = state.value.activeProviderId,
    ): BusinessTurn =
        guarded("TURN_START_FAILED") {
            val thread = requireNotNull(state.value.currentThread) { "No active business thread" }
            gateway.startTurn(thread.id, text, attachments.toList(), providerId).also {
                store.dispatch(BusinessDesktopEvent.TurnRequested(it))
            }
        }

    suspend fun cancelActiveTurn(): Boolean = guarded("TURN_CANCEL_FAILED") {
        val turn = requireNotNull(state.value.activeTurn) { "No active business turn" }
        gateway.cancelTurn(turn.id)
    }

    override fun close() {
        eventCollector.cancel()
        gateway.close()
    }

    private suspend fun <T> guarded(code: String, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        store.dispatch(BusinessDesktopEvent.Failed(code, safeFailureMessage(failure)))
        throw failure
    }
}

private fun safeFailureMessage(failure: Exception): String = when (failure) {
    is IllegalArgumentException, is IllegalStateException -> failure.message?.take(160) ?: "Request failed"
    else -> "Request failed"
}
