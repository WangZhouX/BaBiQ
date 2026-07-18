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
        is BusinessDesktopEvent.ConnectionChanged -> connectionChanged(state, event.status)
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
        is BusinessDesktopEvent.ThreadChanged -> newThread(state, event.thread)
        is BusinessDesktopEvent.TurnRequested -> turnRequested(state, event.turn)
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
            currentThread = null,
            suggestions = emptyMap(),
            messages = emptyList(),
            plan = null,
            applicationActions = emptyMap(),
            turnSummary = null,
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
        currentThread = null,
        suggestions = emptyMap(),
        messages = emptyList(),
        plan = null,
        applicationActions = emptyMap(),
        turnSummary = null,
        activeTurn = null,
        turnStatus = null,
        error = error,
    )

    private fun reduceAgentEvent(state: BusinessDesktopState, event: BusinessAgentEvent): BusinessDesktopState = when (event) {
        is BusinessAgentEvent.TurnStarted -> turnStarted(state, event)
        is BusinessAgentEvent.ItemAdded -> correlatedItem(state, event.threadId, event.turnId, event.item)
        is BusinessAgentEvent.ItemUpdated -> correlatedItem(state, event.threadId, event.turnId, event.item)
        is BusinessAgentEvent.ItemCompleted -> correlatedItem(state, event.threadId, event.turnId, event.item)
        is BusinessAgentEvent.TurnCompleted -> turnCompleted(state, event.threadId, event.turnId, event.status)
        is BusinessAgentEvent.TurnFailed -> turnCompleted(
            state,
            event.threadId,
            event.turnId,
            "failed",
            BusinessDesktopError("TURN_FAILED", event.reason),
        )
        is BusinessAgentEvent.Unknown -> state.copy(unknownEventCount = state.unknownEventCount + 1)
    }

    private fun reduceItem(state: BusinessDesktopState, item: BusinessThreadItem): BusinessDesktopState = when (item) {
        is BusinessThreadItem.UserMessage,
        is BusinessThreadItem.AgentMessage,
        is BusinessThreadItem.Reasoning,
        -> state.copy(messages = upsert(state.messages, item))
        is BusinessThreadItem.Plan -> state.copy(plan = item)
        is BusinessThreadItem.ApplicationAction -> stale(state)
        is BusinessThreadItem.TurnSummary -> state.copy(turnSummary = item)
        is BusinessThreadItem.Unknown -> state.copy(unknownEventCount = state.unknownEventCount + 1)
    }

    private fun reduceApplicationAction(
        state: BusinessDesktopState,
        item: BusinessThreadItem.ApplicationAction,
        turnBinding: BusinessTurnBinding?,
        threadId: String,
        turnId: String,
        currentTurn: Boolean,
    ): BusinessDesktopState {
        val currentEpoch = state.identity?.identityEpoch
        val existing = state.actionBindings[item.executionId]
        val candidate = turnBinding?.let { BusinessActionBinding(threadId, turnId, it.identityEpoch) }
        val trusted = existing ?: candidate
        val matchesEvent = trusted?.threadId == threadId && trusted.turnId == turnId
        if (trusted == null || trusted.identityEpoch != currentEpoch || !currentTurn || !matchesEvent) {
            return state.copy(
                auditOnlyActions = state.auditOnlyActions + BusinessActionAuditObservation(
                    executionId = item.executionId,
                    identityEpoch = trusted?.identityEpoch,
                    status = item.status,
                ),
            )
        }
        val bindings = state.actionBindings + (item.executionId to trusted)
        return state.copy(
            applicationActions = state.applicationActions + (item.executionId to item),
            actionBindings = bindings,
        )
    }

    private fun connectionChanged(
        state: BusinessDesktopState,
        status: BusinessConnectionStatus,
    ): BusinessDesktopState = if (
        state.connectionStatus == BusinessConnectionStatus.SHUTDOWN && status != BusinessConnectionStatus.SHUTDOWN
    ) {
        state
    } else if (status == BusinessConnectionStatus.SHUTDOWN) {
        clearIdentity(state, BusinessAuthenticationStatus.SIGNED_OUT, null).copy(
            connectionStatus = BusinessConnectionStatus.SHUTDOWN,
        )
    } else {
        state.copy(connectionStatus = status)
    }

    private fun newThread(state: BusinessDesktopState, thread: BusinessThread): BusinessDesktopState = state.copy(
        currentThread = thread,
        messages = emptyList(),
        plan = null,
        applicationActions = emptyMap(),
        turnSummary = null,
        activeTurn = null,
        turnStatus = null,
        error = null,
    )

    private fun turnRequested(state: BusinessDesktopState, turn: BusinessTurn): BusinessDesktopState {
        val thread = state.currentThread ?: return stale(state)
        val epoch = state.identity?.identityEpoch ?: return stale(state)
        if (thread.id != turn.threadId) return stale(state)
        val existing = state.turnBindings[turn.id]
        if (existing != null && (existing.threadId != turn.threadId || existing.identityEpoch != epoch)) return stale(state)
        val bindings = state.turnBindings + (turn.id to (existing ?: BusinessTurnBinding(turn.threadId, epoch)))
        val terminal = state.terminalTurnStatuses[turn.id]
        if (terminal != null) {
            if (state.activeTurn?.id != null && state.activeTurn.id != turn.id) {
                return state.copy(turnBindings = bindings, unknownEventCount = state.unknownEventCount + 1)
            }
            return state.copy(turnBindings = bindings, activeTurn = null, turnStatus = terminal)
        }
        if (state.activeTurn?.id == turn.id) return state.copy(turnBindings = bindings)
        if (existing != null && state.latestObservedTurnId != null && state.latestObservedTurnId != turn.id) {
            return state.copy(turnBindings = bindings, unknownEventCount = state.unknownEventCount + 1)
        }
        return state.copy(
            turnBindings = bindings,
            activeTurn = turn,
            turnStatus = "starting",
            applicationActions = emptyMap(),
        )
    }

    private fun turnStarted(
        state: BusinessDesktopState,
        event: BusinessAgentEvent.TurnStarted,
    ): BusinessDesktopState {
        val thread = state.currentThread ?: return stale(state)
        val epoch = state.identity?.identityEpoch ?: return stale(state)
        if (thread.id != event.threadId) return stale(state)
        val existing = state.turnBindings[event.turnId]
        if (existing != null && (existing.threadId != event.threadId || existing.identityEpoch != epoch)) return stale(state)
        if (existing != null && state.activeTurn?.id != null && state.activeTurn.id != event.turnId) return stale(state)
        val bindings = state.turnBindings + (event.turnId to (existing ?: BusinessTurnBinding(event.threadId, epoch)))
        val terminal = state.terminalTurnStatuses[event.turnId]
        return if (terminal != null) {
            state.copy(turnBindings = bindings, latestObservedTurnId = event.turnId, activeTurn = null, turnStatus = terminal)
        } else {
            state.copy(
                turnBindings = bindings,
                latestObservedTurnId = event.turnId,
                activeTurn = BusinessTurn(event.turnId, event.threadId),
                turnStatus = "running",
            )
        }
    }

    private fun turnCompleted(
        state: BusinessDesktopState,
        threadId: String,
        turnId: String,
        status: String,
        error: BusinessDesktopError? = null,
    ): BusinessDesktopState {
        val currentThread = state.currentThread ?: return stale(state)
        val binding = state.turnBindings[turnId] ?: return stale(state)
        if (currentThread.id != threadId || binding.threadId != threadId) return stale(state)
        if (turnId in state.terminalTurnStatuses) return stale(state)
        val terminals = state.terminalTurnStatuses + (turnId to status)
        return if (
            state.activeTurn?.id == turnId ||
            (state.activeTurn == null && state.latestObservedTurnId == turnId)
        ) {
            state.copy(
                terminalTurnStatuses = terminals,
                activeTurn = null,
                turnStatus = status,
                error = error ?: state.error,
            )
        } else {
            state.copy(terminalTurnStatuses = terminals, unknownEventCount = state.unknownEventCount + 1)
        }
    }

    private fun correlatedItem(
        state: BusinessDesktopState,
        threadId: String,
        turnId: String,
        item: BusinessThreadItem,
    ): BusinessDesktopState {
        val binding = state.turnBindings[turnId]
        val currentTurn = state.currentThread?.id == threadId &&
            binding?.threadId == threadId &&
            binding.identityEpoch == state.identity?.identityEpoch &&
            state.activeTurn?.id == turnId
        if (item is BusinessThreadItem.ApplicationAction) {
            return reduceApplicationAction(state, item, binding, threadId, turnId, currentTurn)
        }
        if (!currentTurn) return stale(state)
        return reduceItem(state, item)
    }

    private fun stale(state: BusinessDesktopState): BusinessDesktopState =
        state.copy(unknownEventCount = state.unknownEventCount + 1)

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
