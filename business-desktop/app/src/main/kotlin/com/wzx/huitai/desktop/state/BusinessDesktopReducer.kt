package com.wzx.huitai.desktop.state

import com.wzx.huitai.agent.conversation.BusinessAgentEvent
import com.wzx.huitai.agent.conversation.BusinessProvider
import com.wzx.huitai.agent.conversation.BusinessProviderSelection
import com.wzx.huitai.agent.conversation.BusinessThread
import com.wzx.huitai.agent.conversation.BusinessThreadItem
import com.wzx.huitai.agent.conversation.BusinessTurn
import com.wzx.huitai.presentation.context.PageContextSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface BusinessDesktopEvent {
    data class ConnectionChanged(val status: BusinessConnectionStatus) : BusinessDesktopEvent
    data class IdentityAuthenticated(val identity: BusinessIdentity) : BusinessDesktopEvent
    data object SignedOut : BusinessDesktopEvent
    data object AuthenticationExpired : BusinessDesktopEvent
    data object MembershipExpired : BusinessDesktopEvent
    data class PageChanged(val page: PageContextSnapshot) : BusinessDesktopEvent
    data class SuggestionsChanged(val suggestions: List<BusinessFieldSuggestion>) : BusinessDesktopEvent
    data class AgentEventReceived(val event: BusinessAgentEvent) : BusinessDesktopEvent
    data class ProvidersChanged(val providers: List<BusinessProvider>) : BusinessDesktopEvent
    data class ProviderSelected(val selection: BusinessProviderSelection) : BusinessDesktopEvent
    data class ThreadChanged(val thread: BusinessThread) : BusinessDesktopEvent
    data class TurnRequested(val turn: BusinessTurn) : BusinessDesktopEvent
    data class Failed(val code: String, val message: String) : BusinessDesktopEvent
    data object ClearError : BusinessDesktopEvent
}

class BusinessDesktopReducer {
    fun reduce(state: BusinessDesktopState, event: BusinessDesktopEvent): BusinessDesktopState = when (event) {
        is BusinessDesktopEvent.ConnectionChanged -> state.copy(connectionStatus = event.status)
        is BusinessDesktopEvent.IdentityAuthenticated -> authenticated(state, event.identity)
        BusinessDesktopEvent.SignedOut -> clearIdentity(state, BusinessAuthenticationStatus.SIGNED_OUT, null)
        BusinessDesktopEvent.AuthenticationExpired -> clearIdentity(
            state,
            BusinessAuthenticationStatus.EXPIRED,
            BusinessDesktopError("AUTH_EXPIRED", "Authentication expired"),
        )
        BusinessDesktopEvent.MembershipExpired -> clearIdentity(
            state,
            BusinessAuthenticationStatus.MEMBERSHIP_EXPIRED,
            BusinessDesktopError("MEMBERSHIP_EXPIRED", "Membership expired"),
        )
        is BusinessDesktopEvent.PageChanged -> state.copy(page = event.page)
        is BusinessDesktopEvent.SuggestionsChanged -> state.copy(
            suggestions = event.suggestions.associateBy(BusinessFieldSuggestion::fieldId),
        )
        is BusinessDesktopEvent.AgentEventReceived -> reduceAgentEvent(state, event.event)
        is BusinessDesktopEvent.ProvidersChanged -> state.copy(providers = event.providers.toList())
        is BusinessDesktopEvent.ProviderSelected -> state.copy(activeProviderId = event.selection.providerId)
        is BusinessDesktopEvent.ThreadChanged -> state.copy(currentThread = event.thread)
        is BusinessDesktopEvent.TurnRequested -> state.copy(activeTurn = event.turn, turnStatus = "starting")
        is BusinessDesktopEvent.Failed -> state.copy(error = BusinessDesktopError(event.code, event.message.take(240)))
        BusinessDesktopEvent.ClearError -> state.copy(error = null)
    }

    private fun authenticated(state: BusinessDesktopState, identity: BusinessIdentity): BusinessDesktopState {
        if (state.identity == identity) {
            return state.copy(authenticationStatus = BusinessAuthenticationStatus.AUTHENTICATED)
        }
        return state.copy(
            authenticationStatus = BusinessAuthenticationStatus.AUTHENTICATED,
            identity = identity,
            page = null,
            suggestions = emptyMap(),
            applicationActions = emptyMap(),
            activeTurn = null,
            turnStatus = null,
            error = null,
        )
    }

    private fun clearIdentity(
        state: BusinessDesktopState,
        status: BusinessAuthenticationStatus,
        error: BusinessDesktopError?,
    ): BusinessDesktopState = state.copy(
        authenticationStatus = status,
        identity = null,
        page = null,
        suggestions = emptyMap(),
        applicationActions = emptyMap(),
        activeTurn = null,
        turnStatus = null,
        error = error,
    )

    private fun reduceAgentEvent(state: BusinessDesktopState, event: BusinessAgentEvent): BusinessDesktopState = when (event) {
        is BusinessAgentEvent.TurnStarted -> state.copy(
            activeTurn = BusinessTurn(event.turnId, event.threadId),
            turnStatus = "running",
        )
        is BusinessAgentEvent.ItemAdded -> reduceItem(state, event.item)
        is BusinessAgentEvent.ItemUpdated -> reduceItem(state, event.item)
        is BusinessAgentEvent.ItemCompleted -> reduceItem(state, event.item)
        is BusinessAgentEvent.TurnCompleted -> state.copy(turnStatus = event.status, activeTurn = null)
        is BusinessAgentEvent.TurnFailed -> state.copy(
            turnStatus = "failed",
            activeTurn = null,
            error = BusinessDesktopError("TURN_FAILED", event.reason),
        )
        is BusinessAgentEvent.Unknown -> state.copy(unknownEventCount = state.unknownEventCount + 1)
    }

    private fun reduceItem(state: BusinessDesktopState, item: BusinessThreadItem): BusinessDesktopState = when (item) {
        is BusinessThreadItem.UserMessage,
        is BusinessThreadItem.AgentMessage,
        is BusinessThreadItem.Reasoning,
        -> state.copy(messages = upsert(state.messages, item))
        is BusinessThreadItem.Plan -> state.copy(plan = item)
        is BusinessThreadItem.ApplicationAction -> reduceApplicationAction(state, item)
        is BusinessThreadItem.TurnSummary -> state.copy(turnSummary = item)
        is BusinessThreadItem.Unknown -> state.copy(unknownEventCount = state.unknownEventCount + 1)
    }

    private fun reduceApplicationAction(
        state: BusinessDesktopState,
        item: BusinessThreadItem.ApplicationAction,
    ): BusinessDesktopState {
        val currentEpoch = state.identity?.identityEpoch
        val boundEpoch = state.actionIdentityEpochs[item.executionId] ?: currentEpoch
        if (boundEpoch == null) return state.copy(unknownEventCount = state.unknownEventCount + 1)
        val epochs = state.actionIdentityEpochs + (item.executionId to boundEpoch)
        if (boundEpoch != currentEpoch) {
            return state.copy(
                actionIdentityEpochs = epochs,
                auditOnlyActions = state.auditOnlyActions + BusinessActionAuditObservation(
                    executionId = item.executionId,
                    identityEpoch = boundEpoch,
                    status = item.status,
                ),
            )
        }
        return state.copy(
            applicationActions = state.applicationActions + (item.executionId to item),
            actionIdentityEpochs = epochs,
        )
    }

    private fun upsert(items: List<BusinessThreadItem>, candidate: BusinessThreadItem): List<BusinessThreadItem> {
        val index = items.indexOfFirst { it.id == candidate.id }
        return if (index < 0) items + candidate else items.toMutableList().also { it[index] = candidate }.toList()
    }
}

class BusinessDesktopStore(
    private val reducer: BusinessDesktopReducer,
    initial: BusinessDesktopState = BusinessDesktopState(),
) {
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<BusinessDesktopState> = mutableState.asStateFlow()

    fun dispatch(event: BusinessDesktopEvent) {
        mutableState.update { reducer.reduce(it, event) }
    }
}
