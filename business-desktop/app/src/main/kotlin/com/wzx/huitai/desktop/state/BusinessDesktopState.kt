package com.wzx.huitai.desktop.state

import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.agent.conversation.BusinessProvider
import com.wzx.huitai.agent.conversation.BusinessThread
import com.wzx.huitai.agent.conversation.BusinessThreadItem
import com.wzx.huitai.agent.conversation.BusinessTurn
import com.wzx.huitai.presentation.context.PageContextSnapshot
import kotlinx.serialization.json.JsonElement

enum class BusinessConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    MANUAL_RETRY_REQUIRED,
    AUTHENTICATION_FAILED,
    SHUTDOWN,
}

enum class BusinessAuthenticationStatus {
    SIGNED_OUT,
    AUTHENTICATED,
    EXPIRED,
    MEMBERSHIP_EXPIRED,
}

data class BusinessIdentity(
    val desktopInstanceId: String,
    val desktopSessionId: String,
    val authSessionId: String,
    val identityEpoch: Long,
    val userId: String,
    val tenantId: String,
    val platformId: String,
    val roles: Set<String>,
    val permissions: Set<String>,
) {
    init {
        require(identityEpoch > 0) { "identityEpoch must be positive" }
    }

    fun actionScope(): ActionIdentityScope = ActionIdentityScope(
        desktopInstanceId = desktopInstanceId,
        desktopSessionId = desktopSessionId,
        authSessionId = authSessionId,
        identityEpoch = identityEpoch,
        userId = userId,
        tenantId = tenantId,
        platformId = platformId,
    )

    override fun toString(): String = "BusinessIdentity(identityEpoch=$identityEpoch, values=[REDACTED])"
}

data class BusinessFieldSuggestion(
    val fieldId: String,
    val value: JsonElement,
    val source: String,
    val confidence: Double? = null,
) {
    override fun toString(): String =
        "BusinessFieldSuggestion(fieldId=$fieldId, source=$source, confidence=$confidence, value=[REDACTED])"
}

data class BusinessDesktopError(
    val code: String,
    val message: String,
)

data class BusinessActionAuditObservation(
    val executionId: String,
    val identityEpoch: Long?,
    val status: String,
)

data class BusinessTurnBinding(
    val threadId: String,
    val identityEpoch: Long,
)

data class BusinessActionBinding(
    val threadId: String,
    val turnId: String,
    val identityEpoch: Long,
)

data class BusinessDesktopState(
    val connectionStatus: BusinessConnectionStatus = BusinessConnectionStatus.DISCONNECTED,
    val authenticationStatus: BusinessAuthenticationStatus = BusinessAuthenticationStatus.SIGNED_OUT,
    val identity: BusinessIdentity? = null,
    val page: PageContextSnapshot? = null,
    val suggestions: Map<String, BusinessFieldSuggestion> = emptyMap(),
    val messages: List<BusinessThreadItem> = emptyList(),
    val plan: BusinessThreadItem.Plan? = null,
    val applicationActions: Map<String, BusinessThreadItem.ApplicationAction> = emptyMap(),
    /** 保留 execution 的原始身份代次，用于身份切换后的迟到结果仅走审计。 */
    internal val actionBindings: Map<String, BusinessActionBinding> = emptyMap(),
    val auditOnlyActions: List<BusinessActionAuditObservation> = emptyList(),
    val turnSummary: BusinessThreadItem.TurnSummary? = null,
    val providers: List<BusinessProvider> = emptyList(),
    val activeProviderId: String? = null,
    val currentThread: BusinessThread? = null,
    val activeTurn: BusinessTurn? = null,
    val turnStatus: String? = null,
    internal val turnBindings: Map<String, BusinessTurnBinding> = emptyMap(),
    internal val terminalTurnStatuses: Map<String, String> = emptyMap(),
    internal val latestObservedTurnId: String? = null,
    val error: BusinessDesktopError? = null,
    val unknownEventCount: Int = 0,
)
