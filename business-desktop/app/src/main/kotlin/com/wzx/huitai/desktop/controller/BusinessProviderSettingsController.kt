package com.wzx.huitai.desktop.controller

import com.wzx.huitai.agent.client.AgentSupervisorState
import com.wzx.huitai.agent.conversation.BusinessConversationGateway
import com.wzx.huitai.agent.conversation.BusinessProvider
import com.wzx.huitai.agent.conversation.BusinessProviderDeleteResult
import com.wzx.huitai.agent.conversation.BusinessProviderDraft
import com.wzx.huitai.agent.conversation.BusinessProviderOAuthLoginResult
import com.wzx.huitai.agent.conversation.BusinessProviderOAuthStatus
import com.wzx.huitai.agent.conversation.BusinessProviderSelection
import com.wzx.huitai.agent.conversation.BusinessProviderTestResult
import com.wzx.huitai.desktop.state.BusinessAuthenticationStatus
import com.wzx.huitai.desktop.state.BusinessDesktopState
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Provider 设置页展示安全提示时使用的视觉级别。 */
enum class BusinessProviderSettingsNoticeLevel {
    INFO,
    SUCCESS,
    ERROR,
}

/**
 * Provider 设置页可展示的脱敏提示。
 *
 * @property code 稳定提示代码，供 UI 测试和后续本地化使用。
 * @property message 已由客户端或已认证后端控制的安全文案，不包含异常正文。
 * @property level UI 应采用的信息、成功或错误样式。
 */
data class BusinessProviderSettingsNotice(
    val code: String,
    val message: String,
    val level: BusinessProviderSettingsNoticeLevel,
)

/**
 * Provider 设置页唯一状态；刻意不包含 API Key 或保存草稿。
 *
 * @property providers 后端返回的非敏感 Provider 快照。
 * @property loading 是否正在刷新 Provider 列表。
 * @property busyProviderId 当前执行单 Provider 命令的 Provider ID。
 * @property notice 最近一次安全操作提示。
 * @property oauthStatus 按 UI Provider ID 保存的全局 Anthropic CLI OAuth 状态投影。
 * @property operationsEnabled 当前连接已完成注册且业务身份有效时才为 true。
 * @property connectionGeneration 每个新 finalized connection ID 递增一次，供 UI 清理局部密钥输入。
 */
data class BusinessProviderSettingsState(
    val providers: List<BusinessProvider> = emptyList(),
    val loading: Boolean = false,
    val busyProviderId: String? = null,
    val notice: BusinessProviderSettingsNotice? = null,
    val oauthStatus: Map<String, BusinessProviderOAuthStatus> = emptyMap(),
    val operationsEnabled: Boolean = false,
    val connectionGeneration: Long = 0,
)

/**
 * 在业务桌面中编排 Provider 设置命令，但不接管共享 Agent 事件流或 gateway 生命周期。
 *
 * API Key 只作为 [BusinessProviderDraft] 的挂起调用参数经过本类，永远不写入 [state]、提示或日志。
 * 连接可用性必须同时满足 Registered lifecycle 已发布 Connected、桌面认证完成且 identity 非空。
 *
 * @param gateway 与会话控制器共享的认证 JSON-RPC gateway；[close] 不关闭它。
 * @param supervisorState RegisteredAgentConnectionLifecycle 的 finalized 状态投影。
 * @param desktopState 业务桌面认证和 identity 真相源。
 * @param scope 父作用域；本类创建自己的 child job，关闭时不取消父作用域。
 * @param onProvidersChanged 写操作完成后刷新会话侧 Provider 下拉的回调。
 */
class BusinessProviderSettingsController(
    private val gateway: BusinessConversationGateway,
    private val supervisorState: StateFlow<AgentSupervisorState>,
    private val desktopState: StateFlow<BusinessDesktopState>,
    scope: CoroutineScope,
    private val onProvidersChanged: suspend () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val operationMutex = Mutex()
    private val controllerJob = SupervisorJob(scope.coroutineContext[Job])
    private val controllerScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + controllerJob)
    private val mutableState = MutableStateFlow(BusinessProviderSettingsState())
    private var lastFinalizedConnectionId: String? = null
    private var refreshJob: Job? = null
    private val availabilityObserver = controllerScope.launch(start = CoroutineStart.UNDISPATCHED) {
        combine(supervisorState, desktopState) { supervisor, desktop ->
            availableConnectionId(supervisor, desktop)
        }.distinctUntilChanged().collect(::onAvailabilityChanged)
    }

    /** 设置页只读状态流，不含 Provider 草稿或 API Key。 */
    val state: StateFlow<BusinessProviderSettingsState> = mutableState.asStateFlow()

    /** 手动刷新非敏感 Provider 列表；连接不可用时只发布固定提示。 */
    suspend fun refresh(): List<BusinessProvider>? = perform(loading = true) {
        reloadProviders().also {
            publishNotice("PROVIDER_LIST_REFRESHED", "Provider 列表已刷新", BusinessProviderSettingsNoticeLevel.SUCCESS)
        }
    }

    /** 创建 Provider；草稿仅存在于当前挂起调用栈，成功后同步两个 Provider 视图。 */
    suspend fun create(draft: BusinessProviderDraft): BusinessProvider? = perform(draft.providerId) {
        gateway.createProvider(draft).also {
            reloadProviders(notifyConversation = true)
            publishNotice("PROVIDER_CREATED", "Provider 已创建", BusinessProviderSettingsNoticeLevel.SUCCESS)
        }
    }

    /** 更新 Provider；留空密钥是否沿用由后端 SecretStore 语义决定。 */
    suspend fun update(draft: BusinessProviderDraft): BusinessProvider? = perform(draft.providerId) {
        gateway.updateProvider(draft).also {
            reloadProviders(notifyConversation = true)
            publishNotice("PROVIDER_UPDATED", "Provider 已更新", BusinessProviderSettingsNoticeLevel.SUCCESS)
        }
    }

    /** 删除/禁用 Provider，并使用后端返回的 activeProviderId 确定本地 active 投影。 */
    suspend fun delete(providerId: String): BusinessProviderDeleteResult? = perform(providerId) {
        gateway.deleteProvider(providerId).also { result ->
            reloadProviders(activeProviderId = result.activeProviderId, notifyConversation = true)
            publishNotice("PROVIDER_DELETED", "Provider 已删除", BusinessProviderSettingsNoticeLevel.SUCCESS)
        }
    }

    /** 执行后端轻量配置检查，并原样展示后端已经脱敏的固定文案。 */
    suspend fun test(providerId: String): BusinessProviderTestResult? = perform(providerId) {
        gateway.testProvider(providerId).also { result ->
            publishNotice(
                code = if (result.ok) "PROVIDER_TEST_SUCCEEDED" else "PROVIDER_TEST_FAILED",
                message = result.message,
                level = if (result.ok) BusinessProviderSettingsNoticeLevel.SUCCESS else BusinessProviderSettingsNoticeLevel.ERROR,
            )
        }
    }

    /** 切换当前 Provider，随后刷新列表和会话输入区的选择数据。 */
    suspend fun setActive(providerId: String, modelId: String? = null): BusinessProviderSelection? = perform(providerId) {
        gateway.setActiveProvider(providerId, modelId).also { selection ->
            reloadProviders(activeProviderId = selection.providerId, notifyConversation = true)
            publishNotice("PROVIDER_ACTIVATED", "已设为当前 Provider", BusinessProviderSettingsNoticeLevel.SUCCESS)
        }
    }

    /** 查询全局 Anthropic CLI OAuth 状态，并按发起操作的 Provider ID 投影给 UI。 */
    suspend fun oauthStatus(providerId: String): BusinessProviderOAuthStatus? = perform(providerId) {
        if (!supportsOAuth(providerId)) {
            publishNotice("PROVIDER_OAUTH_UNAVAILABLE", "当前 Provider 不支持 OAuth CLI", BusinessProviderSettingsNoticeLevel.ERROR)
            return@perform null
        }
        gateway.providerOAuthStatus().also { result ->
            mutableState.update { current ->
                current.copy(oauthStatus = current.oauthStatus + (providerId to result))
            }
            publishNotice("PROVIDER_OAUTH_STATUS", result.message, BusinessProviderSettingsNoticeLevel.INFO)
        }
    }

    /** 启动全局 Anthropic CLI OAuth 登录；桌面端本身不执行 shell。 */
    suspend fun oauthLogin(providerId: String): BusinessProviderOAuthLoginResult? = perform(providerId) {
        if (!supportsOAuth(providerId)) {
            publishNotice("PROVIDER_OAUTH_UNAVAILABLE", "当前 Provider 不支持 OAuth CLI", BusinessProviderSettingsNoticeLevel.ERROR)
            return@perform null
        }
        gateway.loginProviderOAuth().also { result ->
            publishNotice(
                code = if (result.ok) "PROVIDER_OAUTH_LOGIN_STARTED" else "PROVIDER_OAUTH_LOGIN_FAILED",
                message = result.message,
                level = if (result.ok) BusinessProviderSettingsNoticeLevel.SUCCESS else BusinessProviderSettingsNoticeLevel.ERROR,
            )
        }
    }

    /** 幂等取消本类观察和自动刷新任务，不关闭共享 gateway 或父作用域。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mutableState.update {
            it.copy(operationsEnabled = false, loading = false, busyProviderId = null)
        }
        refreshJob?.cancel()
        availabilityObserver.cancel()
        controllerScope.cancel()
    }

    /** availability 变化时先收紧操作门禁，新 finalized connection 才递增 generation 并自动刷新。 */
    private fun onAvailabilityChanged(connectionId: String?) {
        if (closed.get()) return
        if (connectionId == null) {
            refreshJob?.cancel()
            mutableState.update {
                it.copy(operationsEnabled = false, loading = false, busyProviderId = null)
            }
            return
        }
        val isNewConnection = lastFinalizedConnectionId != connectionId
        if (isNewConnection) lastFinalizedConnectionId = connectionId
        mutableState.update { current ->
            current.copy(
                operationsEnabled = true,
                connectionGeneration = current.connectionGeneration + if (isNewConnection) 1 else 0,
            )
        }
        if (isNewConnection) {
            refreshJob?.cancel()
            refreshJob = controllerScope.launch { refresh() }
        }
    }

    /** 串行化设置命令，确保 busy/loading 和安全失败提示不会被并发操作互相覆盖。 */
    private suspend fun <T> perform(
        providerId: String? = null,
        loading: Boolean = false,
        block: suspend () -> T?,
    ): T? = operationMutex.withLock {
        if (!isCurrentlyAvailable()) {
            mutableState.update { current ->
                current.copy(
                    operationsEnabled = false,
                    notice = unavailableNotice(),
                )
            }
            return@withLock null
        }
        mutableState.update { current ->
            current.copy(
                loading = loading,
                busyProviderId = if (loading) null else providerId,
            )
        }
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            publishNotice("PROVIDER_SETTINGS_FAILED", "Provider 设置操作失败", BusinessProviderSettingsNoticeLevel.ERROR)
            null
        } finally {
            mutableState.update { current -> current.copy(loading = false, busyProviderId = null) }
        }
    }

    /** 读取设置页快照；activeProviderId 非空时以后端 mutation 结果校正 active/model 标记。 */
    private suspend fun reloadProviders(
        activeProviderId: String? = null,
        notifyConversation: Boolean = false,
    ): List<BusinessProvider> {
        val providers = gateway.listProviders().withActiveProvider(activeProviderId)
        mutableState.update { current -> current.copy(providers = providers) }
        if (notifyConversation) onProvidersChanged()
        return providers
    }

    /** OAuth 是后端全局能力，但 UI 只允许从 Anthropic oauth_cli Provider 发起。 */
    private fun supportsOAuth(providerId: String): Boolean = state.value.providers.any { provider ->
        provider.id == providerId &&
            provider.type.equals("ANTHROPIC", ignoreCase = true) &&
            provider.authMode.equals("oauth_cli", ignoreCase = true)
    }

    private fun isCurrentlyAvailable(): Boolean = !closed.get() &&
        availableConnectionId(supervisorState.value, desktopState.value) != null &&
        state.value.operationsEnabled

    private fun publishNotice(code: String, message: String, level: BusinessProviderSettingsNoticeLevel) {
        mutableState.update { current ->
            current.copy(notice = BusinessProviderSettingsNotice(code, message, level))
        }
    }

    private fun unavailableNotice() = BusinessProviderSettingsNotice(
        code = "PROVIDER_SETTINGS_UNAVAILABLE",
        message = "Provider 设置暂不可用",
        level = BusinessProviderSettingsNoticeLevel.ERROR,
    )
}

/** 只有 finalized Connected 与已认证 identity 同时成立时，返回允许设置操作的 connection ID。 */
private fun availableConnectionId(
    supervisor: AgentSupervisorState,
    desktop: BusinessDesktopState,
): String? = (supervisor as? AgentSupervisorState.Connected)?.connectionId?.takeIf {
    desktop.authenticationStatus == BusinessAuthenticationStatus.AUTHENTICATED && desktop.identity != null
}

/** 用 mutation 返回的 active ID 校正列表，确保 Provider 与其配置模型的 active 状态一致。 */
private fun List<BusinessProvider>.withActiveProvider(activeProviderId: String?): List<BusinessProvider> {
    if (activeProviderId == null) return toList()
    return map { provider ->
        val active = provider.id == activeProviderId
        provider.copy(
            active = active,
            models = provider.models.map { model ->
                model.copy(active = active && model.id == provider.model)
            },
        )
    }
}
