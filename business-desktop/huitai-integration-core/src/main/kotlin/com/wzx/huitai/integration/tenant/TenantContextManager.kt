package com.wzx.huitai.integration.tenant

import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.integration.auth.AuthIdentitySnapshot
import com.wzx.huitai.integration.auth.AuthSessionManager
import com.wzx.huitai.integration.auth.AuthTokenSet
import com.wzx.huitai.integration.identity.IdentityBoundaryActionPort
import com.wzx.huitai.integration.permission.PermissionSnapshotProvider
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/**
 * 目标租户切换所需的认证资料。
 *
 * 角色和权限不由调用方携带，而是在切换时通过权威权限端口重新加载。
 */
data class TenantSwitchRequest(
    val userId: String,
    val tenantId: String,
    val platformId: String,
    val authenticatedAt: Instant,
    val tokens: AuthTokenSet,
)

/**
 * 编排租户切换前的旧页面、Patch 和动作边界收束。
 *
 * 所有旧身份清理均在认证会话发布新身份前完成，防止新租户观察到旧上下文或动作结果。
 */
class TenantContextManager(
    private val authSessionManager: AuthSessionManager,
    private val permissionSnapshotProvider: PermissionSnapshotProvider,
    private val actionBoundary: IdentityBoundaryActionPort,
    private val desktopInstanceId: String,
    private val desktopSessionId: String,
    private val clearPageContext: suspend (ActionIdentityScope) -> Unit,
    private val clearUnappliedPatches: suspend (ActionIdentityScope) -> Unit,
) {
    private val switchMutex = Mutex()

    init {
        require(desktopInstanceId.isNotBlank()) { "desktopInstanceId must not be blank" }
        require(desktopSessionId.isNotBlank()) { "desktopSessionId must not be blank" }
    }

    /**
     * 收束旧身份边界并发布目标身份。
     *
     * 任一加载或清理步骤失败时异常原样传播，且不会调用认证刷新发布新身份。
     */
    suspend fun switchTenant(request: TenantSwitchRequest): AuthIdentitySnapshot = switchMutex.withLock {
        val oldIdentity = checkNotNull(authSessionManager.identity.value) {
            "Authenticated identity is required for tenant switching"
        }
        val oldScope = oldIdentity.toActionScope()
        val permissionSnapshot = permissionSnapshotProvider.load(
            userId = request.userId,
            tenantId = request.tenantId,
            platformId = request.platformId,
        )

        clearPageContext(oldScope)
        clearUnappliedPatches(oldScope)
        actionBoundary.cancelPreExecution(oldScope, PRE_EXECUTION_STATES)
        actionBoundary.detachExecutingForReconciliation(oldScope)

        authSessionManager.refresh(
            userId = request.userId,
            tenantId = request.tenantId,
            platformId = request.platformId,
            roles = permissionSnapshot.roles,
            permissions = permissionSnapshot.permissions,
            authenticatedAt = request.authenticatedAt,
            tokens = request.tokens,
        )
        checkNotNull(authSessionManager.identity.value) {
            "Tenant switch completed without an authenticated identity"
        }
    }

    /** 将完整 scope 原样交给动作边界查询，不尝试按租户或 executionId 降级匹配。 */
    suspend fun result(
        executionId: String,
        identityScope: ActionIdentityScope,
    ): ActionResult<JsonElement>? = actionBoundary.result(executionId, identityScope)

    private fun AuthIdentitySnapshot.toActionScope() = ActionIdentityScope(
        desktopInstanceId = desktopInstanceId,
        desktopSessionId = desktopSessionId,
        authSessionId = authSessionId,
        identityEpoch = identityEpoch,
        userId = userId,
        tenantId = tenantId,
        platformId = platformId,
    )

    private companion object {
        val PRE_EXECUTION_STATES = setOf(
            ActionExecutionState.RECEIVED,
            ActionExecutionState.VALIDATING,
            ActionExecutionState.PREVIEWED,
            ActionExecutionState.WAITING_APPROVAL,
        )
    }
}
