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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Provider 设置页展示安全提示时使用的视觉级别。 */
enum class BusinessProviderSettingsNoticeLevel {
    INFO,
    SUCCESS,
    ERROR,
}

/** Provider 设置页可展示的脱敏提示。 */
data class BusinessProviderSettingsNotice(
    val code: String,
    val message: String,
    val level: BusinessProviderSettingsNoticeLevel,
)

/** Provider 设置页唯一状态；刻意不包含 API Key 或保存草稿。 */
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
 * 每次操作都绑定启动时的 finalized connection 代次。网络返回后只有该代次仍然有效时，
 * 才能原子提交设置页状态和会话 Provider 投影。所有网络操作都是 controller job 的子任务。
 */
class BusinessProviderSettingsController(
    private val gateway: BusinessConversationGateway,
    private val supervisorState: StateFlow<AgentSupervisorState>,
    private val desktopState: StateFlow<BusinessDesktopState>,
    scope: CoroutineScope,
    private val onProvidersChanged: (List<BusinessProvider>) -> Unit,
) : Closeable {
    private data class OperationToken(
        val connectionId: String,
        val generation: Long,
        val operationMutex: Mutex,
    )

    private val closed = AtomicBoolean(false)
    private val stateMonitor = Any()
    private val controllerJob = SupervisorJob(scope.coroutineContext[Job])
    private val controllerScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + controllerJob)
    private val mutableState = MutableStateFlow(BusinessProviderSettingsState())
    private var lastFinalizedConnectionId: String? = null
    private var operationGeneration = 0L
    private var epochOperationMutex = Mutex()
    private var currentToken: OperationToken? = null
    private var refreshJob: Job? = null
    private val availabilityObserver = controllerScope.launch(start = CoroutineStart.UNDISPATCHED) {
        combine(supervisorState, desktopState) { supervisor, desktop ->
            availableConnectionId(supervisor, desktop)
        }.distinctUntilChanged().collect(::onAvailabilityChanged)
    }

    /** 设置页只读状态流，不含 Provider 草稿或 API Key。 */
    val state: StateFlow<BusinessProviderSettingsState> = mutableState.asStateFlow()

    /** 手动刷新非敏感 Provider 列表。 */
    suspend fun refresh(): List<BusinessProvider>? = submitOperation(loading = true) { token ->
        refreshProviders(token, publishSuccess = true)
    }

    /** 创建 Provider，成功后用同一次列表读取同步设置页和会话投影。 */
    suspend fun create(draft: BusinessProviderDraft): BusinessProvider? = submitOperation(draft.providerId) { token ->
        val result = gateway.createProvider(draft)
        if (!isOperationCurrent(token)) return@submitOperation null
        val providers = gateway.listProviders()
        if (!isOperationCurrent(token)) return@submitOperation null
        if (!commitProviders(token, providers, "PROVIDER_CREATED", "Provider 已创建")) return@submitOperation null
        result
    }

    /** 更新 Provider，留空密钥是否沿用由后端 SecretStore 语义决定。 */
    suspend fun update(draft: BusinessProviderDraft): BusinessProvider? = submitOperation(draft.providerId) { token ->
        val result = gateway.updateProvider(draft)
        if (!isOperationCurrent(token)) return@submitOperation null
        val providers = gateway.listProviders()
        if (!isOperationCurrent(token)) return@submitOperation null
        if (!commitProviders(token, providers, "PROVIDER_UPDATED", "Provider 已更新")) return@submitOperation null
        result
    }

    /** 删除/禁用 Provider，并以后端 activeProviderId 校正本地 active 投影。 */
    suspend fun delete(providerId: String): BusinessProviderDeleteResult? = submitOperation(providerId) { token ->
        val result = gateway.deleteProvider(providerId)
        if (!isOperationCurrent(token)) return@submitOperation null
        val providers = gateway.listProviders().withActiveProvider(result.activeProviderId)
        if (!isOperationCurrent(token)) return@submitOperation null
        if (!commitProviders(token, providers, "PROVIDER_DELETED", "Provider 已删除")) return@submitOperation null
        result
    }

    /** 执行后端轻量配置检查，并展示后端已经脱敏的固定文案。 */
    suspend fun test(providerId: String): BusinessProviderTestResult? = submitOperation(providerId) { token ->
        val result = gateway.testProvider(providerId)
        if (!isOperationCurrent(token)) return@submitOperation null
        val committed = commitIfCurrent(token) { current ->
            current.copy(
                notice = BusinessProviderSettingsNotice(
                    code = if (result.ok) "PROVIDER_TEST_SUCCEEDED" else "PROVIDER_TEST_FAILED",
                    message = result.message,
                    level = if (result.ok) {
                        BusinessProviderSettingsNoticeLevel.SUCCESS
                    } else {
                        BusinessProviderSettingsNoticeLevel.ERROR
                    },
                ),
            )
        }
        result.takeIf { committed }
    }

    /** 切换当前 Provider，随后刷新设置页和会话输入区的选择数据。 */
    suspend fun setActive(providerId: String, modelId: String? = null): BusinessProviderSelection? =
        submitOperation(providerId) { token ->
            val result = gateway.setActiveProvider(providerId, modelId)
            if (!isOperationCurrent(token)) return@submitOperation null
            val providers = gateway.listProviders().withActiveProvider(result.providerId)
            if (!isOperationCurrent(token)) return@submitOperation null
            if (!commitProviders(token, providers, "PROVIDER_ACTIVATED", "已设为当前 Provider")) {
                return@submitOperation null
            }
            result
        }

    /** 查询全局 Anthropic CLI OAuth 状态，并按发起操作的 Provider ID 投影给 UI。 */
    suspend fun oauthStatus(providerId: String): BusinessProviderOAuthStatus? = submitOperation(providerId) { token ->
        if (!supportsOAuth(providerId, token)) {
            publishIfCurrent(
                token,
                "PROVIDER_OAUTH_UNAVAILABLE",
                "当前 Provider 不支持 OAuth CLI",
                BusinessProviderSettingsNoticeLevel.ERROR,
            )
            return@submitOperation null
        }
        val result = gateway.providerOAuthStatus()
        if (!isOperationCurrent(token)) return@submitOperation null
        val committed = commitIfCurrent(token) { current ->
            current.copy(
                oauthStatus = current.oauthStatus + (providerId to result),
                notice = BusinessProviderSettingsNotice(
                    "PROVIDER_OAUTH_STATUS",
                    result.message,
                    BusinessProviderSettingsNoticeLevel.INFO,
                ),
            )
        }
        result.takeIf { committed }
    }

    /** 启动全局 Anthropic CLI OAuth 登录；桌面端本身不执行 shell。 */
    suspend fun oauthLogin(providerId: String): BusinessProviderOAuthLoginResult? = submitOperation(providerId) { token ->
        if (!supportsOAuth(providerId, token)) {
            publishIfCurrent(
                token,
                "PROVIDER_OAUTH_UNAVAILABLE",
                "当前 Provider 不支持 OAuth CLI",
                BusinessProviderSettingsNoticeLevel.ERROR,
            )
            return@submitOperation null
        }
        val result = gateway.loginProviderOAuth()
        if (!isOperationCurrent(token)) return@submitOperation null
        val committed = publishIfCurrent(
            token = token,
            code = if (result.ok) "PROVIDER_OAUTH_LOGIN_STARTED" else "PROVIDER_OAUTH_LOGIN_FAILED",
            message = result.message,
            level = if (result.ok) {
                BusinessProviderSettingsNoticeLevel.SUCCESS
            } else {
                BusinessProviderSettingsNoticeLevel.ERROR
            },
        )
        result.takeIf { committed }
    }

    /** 幂等取消本类观察、自动刷新和所有手工操作，不关闭共享 gateway 或父作用域。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(stateMonitor) {
            operationGeneration += 1
            currentToken = null
            mutableState.value = mutableState.value.copy(
                operationsEnabled = false,
                loading = false,
                busyProviderId = null,
            )
        }
        controllerScope.cancel()
    }

    /**
     * 创建 controller-owned 子任务，并把调用方取消显式传递给该子任务。
     * token 在排队前捕获，因此旧连接上排队的命令不会落到新连接执行。
     */
    private suspend fun <T> submitOperation(
        providerId: String? = null,
        loading: Boolean = false,
        block: suspend (OperationToken) -> T?,
    ): T? {
        val token = captureTokenOrPublishUnavailable() ?: return null
        val child = controllerScope.async(start = CoroutineStart.UNDISPATCHED) {
            perform(token, providerId, loading, block)
        }
        return try {
            child.await()
        } catch (cancelled: CancellationException) {
            child.cancel(cancelled)
            throw cancelled
        }
    }

    /** 串行化网络命令，但不在网络挂起期间持有状态提交监视器。 */
    private suspend fun <T> perform(
        token: OperationToken,
        providerId: String?,
        loading: Boolean,
        block: suspend (OperationToken) -> T?,
    ): T? = token.operationMutex.withLock {
        if (!commitIfCurrent(token) { current ->
                current.copy(loading = loading, busyProviderId = if (loading) null else providerId)
            }
        ) {
            return@withLock null
        }
        try {
            block(token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            publishIfCurrent(
                token,
                "PROVIDER_SETTINGS_FAILED",
                "Provider 设置操作失败",
                BusinessProviderSettingsNoticeLevel.ERROR,
            )
            null
        } finally {
            commitIfCurrent(token) { current -> current.copy(loading = false, busyProviderId = null) }
        }
    }

    /** 新 finalized connection 自动刷新；任务同样属于 controller job。 */
    private fun onAvailabilityChanged(connectionId: String?) {
        if (closed.get()) return
        var refreshToken: OperationToken? = null
        synchronized(stateMonitor) {
            if (closed.get()) return
            operationGeneration += 1
            epochOperationMutex = Mutex()
            if (connectionId == null) {
                currentToken = null
                mutableState.value = mutableState.value.copy(
                    operationsEnabled = false,
                    loading = false,
                    busyProviderId = null,
                )
            } else {
                val isNewConnection = lastFinalizedConnectionId != connectionId
                if (isNewConnection) lastFinalizedConnectionId = connectionId
                val token = OperationToken(connectionId, operationGeneration, epochOperationMutex)
                currentToken = token
                mutableState.value = mutableState.value.copy(
                    operationsEnabled = true,
                    connectionGeneration = mutableState.value.connectionGeneration + if (isNewConnection) 1 else 0,
                )
                if (isNewConnection) refreshToken = token
            }
        }
        refreshToken?.let { token ->
            refreshJob?.cancel()
            refreshJob = controllerScope.launch {
                perform(token, providerId = null, loading = true) {
                    refreshProviders(token, publishSuccess = true)
                }
            }
        }
    }

    private suspend fun refreshProviders(token: OperationToken, publishSuccess: Boolean): List<BusinessProvider>? {
        val providers = gateway.listProviders()
        if (!isOperationCurrent(token)) return null
        val committed = commitIfCurrent(token) { current ->
            current.copy(
                providers = providers,
                notice = if (publishSuccess) {
                    BusinessProviderSettingsNotice(
                        "PROVIDER_LIST_REFRESHED",
                        "Provider 列表已刷新",
                        BusinessProviderSettingsNoticeLevel.SUCCESS,
                    )
                } else {
                    current.notice
                },
            )
        }
        return providers.takeIf { committed }
    }

    /** Provider 列表和会话投影在同一次代次校验后提交，不再触发第二次网络读取。 */
    private fun commitProviders(
        token: OperationToken,
        providers: List<BusinessProvider>,
        noticeCode: String,
        noticeMessage: String,
    ): Boolean = synchronized(stateMonitor) {
        if (!isTokenCurrentLocked(token)) return@synchronized false
        mutableState.value = mutableState.value.copy(
            providers = providers,
            notice = BusinessProviderSettingsNotice(
                noticeCode,
                noticeMessage,
                BusinessProviderSettingsNoticeLevel.SUCCESS,
            ),
        )
        onProvidersChanged(providers)
        true
    }

    private fun captureTokenOrPublishUnavailable(): OperationToken? = synchronized(stateMonitor) {
        val token = currentToken
        if (token != null && isTokenCurrentLocked(token)) return@synchronized token
        if (!closed.get()) {
            mutableState.value = mutableState.value.copy(
                operationsEnabled = false,
                notice = unavailableNotice(),
            )
        }
        null
    }

    private suspend fun isOperationCurrent(token: OperationToken): Boolean {
        currentCoroutineContext().ensureActive()
        return synchronized(stateMonitor) { isTokenCurrentLocked(token) }
    }

    private fun isTokenCurrentLocked(token: OperationToken): Boolean =
        !closed.get() &&
            currentToken == token &&
            availableConnectionId(supervisorState.value, desktopState.value) == token.connectionId &&
            mutableState.value.operationsEnabled

    private inline fun commitIfCurrent(
        token: OperationToken,
        update: (BusinessProviderSettingsState) -> BusinessProviderSettingsState,
    ): Boolean = synchronized(stateMonitor) {
        if (!isTokenCurrentLocked(token)) return@synchronized false
        mutableState.value = update(mutableState.value)
        true
    }

    private fun publishIfCurrent(
        token: OperationToken,
        code: String,
        message: String,
        level: BusinessProviderSettingsNoticeLevel,
    ): Boolean = commitIfCurrent(token) { current ->
        current.copy(notice = BusinessProviderSettingsNotice(code, message, level))
    }

    /** OAuth 是后端全局能力，但 UI 只允许从 Anthropic oauth_cli Provider 发起。 */
    private fun supportsOAuth(providerId: String, token: OperationToken): Boolean = synchronized(stateMonitor) {
        isTokenCurrentLocked(token) && mutableState.value.providers.any { provider ->
            provider.id == providerId &&
                provider.type.equals("ANTHROPIC", ignoreCase = true) &&
                provider.authMode.equals("oauth_cli", ignoreCase = true)
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
