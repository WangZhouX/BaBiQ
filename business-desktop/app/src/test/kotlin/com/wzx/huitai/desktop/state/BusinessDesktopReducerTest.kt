package com.wzx.huitai.desktop.state

import com.wzx.huitai.agent.conversation.BusinessAgentEvent
import com.wzx.huitai.agent.conversation.BusinessPlanStep
import com.wzx.huitai.agent.conversation.BusinessThreadItem
import com.wzx.huitai.agent.conversation.BusinessThread
import com.wzx.huitai.agent.conversation.BusinessTurn
import com.wzx.huitai.presentation.context.PageContextSnapshot
import com.wzx.huitai.presentation.context.PageMode
import com.wzx.huitai.presentation.context.ValidationSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

class BusinessDesktopReducerTest {
    private val reducer = BusinessDesktopReducer()

    @Test
    fun `initial state is disconnected and page suggestions chat plan actions summary and errors reduce immutably`() {
        val initial = BusinessDesktopState()
        assertEquals(BusinessConnectionStatus.DISCONNECTED, initial.connectionStatus)

        val identity = identity(epoch = 1)
        var state = reducer.reduce(initial, BusinessDesktopEvent.IdentityAuthenticated(identity))
        state = reducer.reduce(state, BusinessDesktopEvent.ThreadChanged(BusinessThread("thread-1", "demo", "C:/demo")))
        state = reducer.reduce(state, BusinessDesktopEvent.TurnRequested(BusinessTurn("turn-1", "thread-1")))
        state = reducer.reduce(state, BusinessDesktopEvent.PageChanged(page(revision = 3)))
        state = reducer.reduce(state, BusinessDesktopEvent.SuggestionsChanged(listOf(
            BusinessFieldSuggestion("contact", JsonPrimitive("Alex"), "agent", 0.88),
        )))
        state = reducer.reduce(state, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.ItemAdded("thread-1", "turn-1", BusinessThreadItem.AgentMessage("msg-1", textDelta = "A")),
        ))
        state = reducer.reduce(state, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.ItemUpdated("thread-1", "turn-1", BusinessThreadItem.AgentMessage("msg-1", text = "AB")),
        ))
        state = reducer.reduce(state, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.ItemAdded("thread-1", "turn-1", BusinessThreadItem.Plan(
                "plan-1", steps = listOf(BusinessPlanStep(1, "first", "pending")),
            )),
        ))
        state = reducer.reduce(state, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.ItemUpdated("thread-1", "turn-1", BusinessThreadItem.Plan(
                "plan-1", steps = listOf(BusinessPlanStep(1, "first", "completed"), BusinessPlanStep(2, "second", "pending")),
            )),
        ))
        state = reducer.reduce(state, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.ItemAdded("thread-1", "turn-1", action("exec-1", "executing")),
        ))
        state = reducer.reduce(state, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.ItemAdded("thread-1", "turn-1", summary()),
        ))
        state = reducer.reduce(state, BusinessDesktopEvent.Failed("NETWORK", "connection unavailable"))

        assertNull(initial.page)
        assertEquals(3, state.page?.revision)
        assertEquals("agent", state.suggestions.getValue("contact").source)
        assertEquals("AB", (state.messages.single() as BusinessThreadItem.AgentMessage).text)
        assertEquals(2, state.plan?.steps?.size)
        assertEquals("executing", state.applicationActions.getValue("exec-1").status)
        assertEquals(14, state.turnSummary?.totalTokens)
        assertEquals(BusinessDesktopError("NETWORK", "connection unavailable"), state.error)
    }

    @Test
    fun `identity switch clears suggestions and old actions and late old scope results are audit only`() {
        var state = reducer.reduce(BusinessDesktopState(), BusinessDesktopEvent.IdentityAuthenticated(identity(1)))
        state = reducer.reduce(state, BusinessDesktopEvent.ThreadChanged(BusinessThread("thread-1", "demo", "C:/demo")))
        state = reducer.reduce(state, BusinessDesktopEvent.TurnRequested(BusinessTurn("turn-1", "thread-1")))
        state = reducer.reduce(state, BusinessDesktopEvent.SuggestionsChanged(listOf(
            BusinessFieldSuggestion("name", JsonPrimitive("old"), "agent", 0.9),
        )))
        state = reducer.reduce(state, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.ItemAdded("thread-1", "turn-1", action("old-exec", "approval_required")),
        ))

        state = reducer.reduce(state, BusinessDesktopEvent.IdentityAuthenticated(identity(2)))
        assertTrue(state.suggestions.isEmpty())
        assertTrue(state.applicationActions.isEmpty())

        state = reducer.reduce(state, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.ItemUpdated("thread-1", "turn-1", action("old-exec", "succeeded")),
        ))
        assertTrue(state.applicationActions.isEmpty())
        assertEquals("old-exec", state.auditOnlyActions.single().executionId)
        assertEquals(1, state.auditOnlyActions.single().identityEpoch)
    }

    @Test
    fun `membership and authentication expiry clear active business state`() {
        var state = reducer.reduce(BusinessDesktopState(), BusinessDesktopEvent.IdentityAuthenticated(identity(1)))
        state = reducer.reduce(state, BusinessDesktopEvent.PageChanged(page(1)))
        state = reducer.reduce(state, BusinessDesktopEvent.MembershipExpired)
        assertEquals(BusinessAuthenticationStatus.MEMBERSHIP_EXPIRED, state.authenticationStatus)
        assertNull(state.identity)
        assertNull(state.page)

        state = reducer.reduce(state, BusinessDesktopEvent.AuthenticationExpired)
        assertEquals(BusinessAuthenticationStatus.EXPIRED, state.authenticationStatus)
        assertEquals("AUTH_EXPIRED", state.error?.code)
    }

    @Test
    fun `turn response arriving after running or terminal events never regresses status`() {
        var running = conversationState()
        running = reducer.reduce(running, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.TurnStarted("thread-1", "turn-1"),
        ))
        running = reducer.reduce(running, BusinessDesktopEvent.TurnRequested(BusinessTurn("turn-1", "thread-1")))
        assertEquals("running", running.turnStatus)
        assertEquals("turn-1", running.activeTurn?.id)

        var completed = conversationState()
        completed = reducer.reduce(completed, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.TurnStarted("thread-1", "turn-2"),
        ))
        completed = reducer.reduce(completed, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.TurnCompleted("thread-1", "turn-2", "completed"),
        ))
        completed = reducer.reduce(completed, BusinessDesktopEvent.TurnRequested(BusinessTurn("turn-2", "thread-1")))
        assertEquals("completed", completed.turnStatus)
        assertNull(completed.activeTurn)

        completed = reducer.reduce(completed, BusinessDesktopEvent.TurnRequested(BusinessTurn("turn-3", "thread-1")))
        completed = reducer.reduce(completed, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.TurnStarted("thread-1", "turn-3"),
        ))
        completed = reducer.reduce(completed, BusinessDesktopEvent.TurnRequested(BusinessTurn("turn-2", "thread-1")))
        assertEquals("running", completed.turnStatus)
        assertEquals("turn-3", completed.activeTurn?.id)
    }

    @Test
    fun `thread and turn correlation rejects stale events and unbound actions`() {
        var state = conversationState()
        state = reducer.reduce(state, BusinessDesktopEvent.TurnRequested(BusinessTurn("turn-old", "thread-1")))
        state = reducer.reduce(state, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.TurnStarted("thread-1", "turn-old"),
        ))
        state = reducer.reduce(state, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.ItemAdded("thread-1", "turn-old", action("reused-exec", "executing")),
        ))
        state = reducer.reduce(state, BusinessDesktopEvent.TurnRequested(BusinessTurn("turn-new", "thread-1")))
        state = reducer.reduce(state, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.TurnCompleted("thread-1", "turn-old", "completed"),
        ))
        assertEquals("turn-new", state.activeTurn?.id)
        assertEquals("starting", state.turnStatus)
        state = reducer.reduce(state, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.TurnStarted("thread-1", "turn-new"),
        ))
        state = reducer.reduce(state, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.TurnCompleted("thread-1", "turn-old", "completed"),
        ))
        assertEquals("turn-new", state.activeTurn?.id)
        assertEquals("running", state.turnStatus)

        state = reducer.reduce(state, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.ItemUpdated("thread-1", "turn-new", action("reused-exec", "succeeded")),
        ))
        assertTrue("reused-exec" !in state.applicationActions)
        assertEquals("reused-exec", state.auditOnlyActions.last().executionId)

        state = reducer.reduce(state, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.ItemAdded("other-thread", "turn-x", BusinessThreadItem.AgentMessage("foreign", text = "secret")),
        ))
        assertTrue(state.messages.none { it.id == "foreign" })

        state = reducer.reduce(state, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.ItemAdded("thread-1", "unbound-turn", action("unbound-exec", "executing")),
        ))
        assertTrue("unbound-exec" !in state.applicationActions)
        assertEquals("unbound-exec", state.auditOnlyActions.last().executionId)
        assertNull(state.auditOnlyActions.last().identityEpoch)
    }

    @Test
    fun `identity new thread and shutdown clear all user scoped desktop state`() {
        var state = conversationState()
        state = reducer.reduce(state, BusinessDesktopEvent.PageChanged(page(4)))
        state = reducer.reduce(state, BusinessDesktopEvent.SuggestionsChanged(listOf(
            BusinessFieldSuggestion("name", JsonPrimitive("value"), "agent", 0.8),
        )))
        state = reducer.reduce(state, BusinessDesktopEvent.TurnRequested(BusinessTurn("turn-1", "thread-1")))
        state = reducer.reduce(state, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.ItemAdded("thread-1", "turn-1", BusinessThreadItem.AgentMessage("msg", text = "hello")),
        ))
        state = reducer.reduce(state, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.ItemAdded("thread-1", "turn-1", BusinessThreadItem.Plan("plan")),
        ))
        state = reducer.reduce(state, BusinessDesktopEvent.AgentEventReceived(
            BusinessAgentEvent.ItemAdded("thread-1", "turn-1", summary()),
        ))

        state = reducer.reduce(state, BusinessDesktopEvent.ThreadChanged(BusinessThread("thread-2", "new", "C:/demo")))
        assertTrue(state.messages.isEmpty())
        assertNull(state.plan)
        assertNull(state.turnSummary)
        assertNull(state.activeTurn)

        state = reducer.reduce(state, BusinessDesktopEvent.ConnectionChanged(BusinessConnectionStatus.SHUTDOWN))
        assertEquals(BusinessConnectionStatus.SHUTDOWN, state.connectionStatus)
        assertNull(state.identity)
        assertNull(state.currentThread)
        assertNull(state.page)
        assertTrue(state.suggestions.isEmpty())
        assertTrue(state.applicationActions.isEmpty())
        assertTrue(state.messages.isEmpty())
        state = reducer.reduce(state, BusinessDesktopEvent.ConnectionChanged(BusinessConnectionStatus.CONNECTED))
        assertEquals(BusinessConnectionStatus.SHUTDOWN, state.connectionStatus)
    }

    private fun conversationState(): BusinessDesktopState {
        var state = reducer.reduce(BusinessDesktopState(), BusinessDesktopEvent.IdentityAuthenticated(identity(1)))
        state = reducer.reduce(state, BusinessDesktopEvent.ThreadChanged(BusinessThread("thread-1", "demo", "C:/demo")))
        return state
    }

    private fun identity(epoch: Long) = BusinessIdentity(
        desktopInstanceId = "desktop-1",
        desktopSessionId = "desktop-session-1",
        authSessionId = "auth-$epoch",
        identityEpoch = epoch,
        userId = "user-$epoch",
        tenantId = "tenant-$epoch",
        platformId = "platform-1",
        roles = setOf("user"),
        permissions = setOf("framework:read"),
    )

    private fun page(revision: Long) = PageContextSnapshot(
        snapshotId = "page-$revision",
        pageId = "demo-form",
        pageTitle = "Demo",
        route = "/demo",
        revision = revision,
        mode = PageMode.EDIT,
        validationSummary = ValidationSummary(true),
    )

    private fun action(executionId: String, status: String) = BusinessThreadItem.ApplicationAction(
        id = "item-$executionId",
        executionId = executionId,
        actionId = "demo.save_draft",
        title = "Save",
        risk = "SAFE",
        status = status,
    )

    private fun summary() = BusinessThreadItem.TurnSummary(
        id = "summary-1",
        status = "completed",
        model = "model-1",
        promptTokens = 10,
        completionTokens = 4,
        totalTokens = 14,
        toolCalls = 1,
        durationMs = 20,
    )
}
