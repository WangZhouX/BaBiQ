package com.wzx.huitai.desktop.controller

import com.wzx.huitai.agent.business.auth.BusinessAuthStateChanged
import com.wzx.huitai.agent.client.AgentJsonRpcException
import com.wzx.huitai.agent.conversation.BusinessAgentIngressEvent
import com.wzx.huitai.agent.conversation.BusinessConversationGateway
import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import com.wzx.huitai.agent.conversation.BusinessProvider
import com.wzx.huitai.agent.conversation.BusinessProviderSelection
import com.wzx.huitai.agent.conversation.BusinessThread
import com.wzx.huitai.agent.conversation.BusinessTurn
import com.wzx.huitai.desktop.state.BusinessDesktopEvent
import com.wzx.huitai.desktop.state.BusinessDesktopState
import com.wzx.huitai.desktop.state.BusinessDesktopStore
import com.wzx.huitai.desktop.auth.AgentAuthenticationRequiredException
import com.wzx.huitai.desktop.auth.ReadyAgentUsageGate
import com.wzx.huitai.desktop.auth.StaleAgentUsageException
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
    private val usageGate: ReadyAgentUsageGate,
    scope: CoroutineScope,
    private val onAuthStateChanged: suspend (BusinessAuthStateChanged) -> Unit = {},
) : Closeable {
    private val eventCollector: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        gateway.ingressEvents.collect { ingress ->
            when (ingress) {
                is BusinessAgentIngressEvent.AuthStateChanged -> try {
                    onAuthStateChanged(ingress.change)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Auth reconciliation is isolated from the shared notification collector.
                }

                is BusinessAgentIngressEvent.Conversation -> {
                    val authentication = usageGate.captureIfReady() ?: return@collect
                    if (
                        ingress.authSessionId != authentication.identity.authSessionId ||
                        ingress.identityEpoch != authentication.identity.identityEpoch
                    ) return@collect
                    usageGate.commitIfCurrent(authentication) {
                        store.dispatch(BusinessDesktopEvent.AgentEventReceived(ingress.event))
                    }
                }
            }
        }
    }

    val state: StateFlow<BusinessDesktopState> = store.state

    suspend fun refreshProviders(): List<BusinessProvider> = gated("PROVIDER_LIST_FAILED", gateway::listProviders) {
        store.dispatch(BusinessDesktopEvent.ProvidersChanged(it))
    }

    /** 接受设置控制器已加载且通过连接代次校验的 Provider 快照，不发起第二次网络请求。 */
    fun acceptProviders(providers: List<BusinessProvider>) {
        val authentication = usageGate.captureIfReady() ?: throw AgentAuthenticationRequiredException()
        if (!usageGate.commitIfCurrent(authentication) {
                store.dispatch(BusinessDesktopEvent.ProvidersChanged(providers))
            }
        ) throw StaleAgentUsageException()
    }

    suspend fun selectProvider(providerId: String, modelId: String? = null): BusinessProviderSelection =
        gated("PROVIDER_SELECTION_FAILED", { gateway.setActiveProvider(providerId, modelId) }) {
            store.dispatch(BusinessDesktopEvent.ProviderSelected(it))
        }

    suspend fun createThread(cwd: String): BusinessThread = gated("THREAD_CREATE_FAILED", { gateway.createThread(cwd) }) {
        store.dispatch(BusinessDesktopEvent.ThreadChanged(it))
    }

    suspend fun startTurn(
        text: String,
        attachments: List<BusinessAttachmentDraft> = emptyList(),
        providerId: String? = state.value.activeProviderId,
    ): BusinessTurn =
        gated("TURN_START_FAILED", {
            val thread = requireNotNull(state.value.currentThread) { "No active business thread" }
            gateway.startTurn(thread.id, text, attachments.toList(), providerId)
        }) {
            store.dispatch(BusinessDesktopEvent.TurnRequested(it))
        }

    suspend fun cancelActiveTurn(): Boolean = gated("TURN_CANCEL_FAILED", {
        val turn = requireNotNull(state.value.activeTurn) { "No active business turn" }
        gateway.cancelTurn(turn.id)
    }) { }

    override fun close() {
        eventCollector.cancel()
        gateway.close()
    }

    private suspend fun <T> guarded(
        authentication: com.wzx.huitai.desktop.auth.ReadyAgentUsageSnapshot,
        code: String,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (failure is AgentAuthenticationRequiredException || failure is StaleAgentUsageException) throw failure
        usageGate.commitIfCurrent(authentication) {
            store.dispatch(BusinessDesktopEvent.Failed(code, safeFailureMessage(failure)))
        }
        throw failure
    }

    private suspend fun <T> gated(
        code: String,
        request: suspend () -> T,
        commit: (T) -> Unit,
    ): T {
        val authentication = usageGate.requireReady()
        val result = guarded(authentication, code, request)
        if (!usageGate.commitIfCurrent(authentication) { commit(result) }) throw StaleAgentUsageException()
        return result
    }
}

private fun safeFailureMessage(failure: Exception): String = when (failure) {
    is AgentJsonRpcException -> safeAttachmentFailureMessage(failure.attachmentCode)
    is IllegalArgumentException, is IllegalStateException -> failure.message?.take(160) ?: "Request failed"
    else -> "Request failed"
}

private fun safeAttachmentFailureMessage(code: String?): String = when (code) {
    "ATTACHMENT_EMPTY" -> "附件内容为空，请重新选择后再发送"
    "ATTACHMENT_LIMIT_EXCEEDED" -> "附件数量过多，请移除部分附件后再发送"
    "ATTACHMENT_FILE_TOO_LARGE" -> "单个附件超过大小限制，请选择更小的文件"
    "ATTACHMENT_TOTAL_TOO_LARGE" -> "附件总大小超过限制，请移除部分附件后再发送"
    "ATTACHMENT_PATH_INVALID",
    "ATTACHMENT_NOT_REGULAR_FILE",
    "ATTACHMENT_CHANGED",
    -> "附件已移动、变更或不可读取，请重新选择后再发送"
    "ATTACHMENT_NOT_FOUND" -> "附件已不存在，请重新选择后再发送"
    "ATTACHMENT_TYPE_UNSUPPORTED" -> "不支持该附件类型，请选择图片、文本、PDF 或 Office 文件"
    "ATTACHMENT_PARSE_FAILED" -> "附件内容无法解析，请检查文件后重试"
    "ATTACHMENT_ENCRYPTED" -> "附件已加密，请先解除密码保护后重试"
    "ATTACHMENT_TEXT_LIMIT_EXCEEDED" -> "附件文字内容过多，请拆分文件后重试"
    "ATTACHMENT_IMAGE_TOO_LARGE" -> "图片尺寸过大，请压缩后重试"
    "ATTACHMENT_MODEL_UNSUPPORTED" -> "当前模型不支持该附件，请更换模型后重试"
    "ATTACHMENT_CLIPBOARD_FAILED" -> "截图附件读取失败，请重新粘贴"
    "ATTACHMENT_PARSE_TIMEOUT" -> "附件解析超时，请缩小文件后重试"
    "ATTACHMENT_PARSE_OVERLOADED" -> "附件解析任务繁忙，请稍后重试"
    "ATTACHMENT_ARCHIVE_UNSAFE" -> "Office 附件结构不安全，请检查文件后重试"
    "ATTACHMENT_REFERENCE_AMBIGUOUS" -> "附件标识存在冲突，请重新选择或明确引用"
    else -> "请求失败，请检查后重试"
}
