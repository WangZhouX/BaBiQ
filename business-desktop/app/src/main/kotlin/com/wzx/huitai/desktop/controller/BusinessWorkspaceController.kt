package com.wzx.huitai.desktop.controller

import com.wzx.huitai.action.ActionBusResult
import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.ApplicationActionBus
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.desktop.state.BusinessDesktopEvent
import com.wzx.huitai.desktop.state.BusinessDesktopState
import com.wzx.huitai.desktop.state.BusinessDesktopStore
import com.wzx.huitai.desktop.state.BusinessFieldSuggestion
import com.wzx.huitai.desktop.state.BusinessIdentity
import com.wzx.huitai.presentation.context.PageContextSnapshot
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

fun interface BusinessContextPublicationPort {
    suspend fun publish(
        identity: BusinessIdentity,
        catalogEpoch: Long,
        contextSequence: Long,
        snapshot: PageContextSnapshot,
    )
}

fun interface UserApplicationActionPort {
    suspend fun execute(command: ActionCommand, context: ActionContext): ActionBusResult
}

class DirectUserApplicationActionPort(private val bus: ApplicationActionBus) : UserApplicationActionPort {
    override suspend fun execute(command: ActionCommand, context: ActionContext): ActionBusResult =
        bus.execute(command, context)
}

/** 页面/context publication 和 USER 动作的唯一拥有者。 */
class BusinessWorkspaceController(
    private val store: BusinessDesktopStore,
    private val contextPublication: BusinessContextPublicationPort,
    private val actionPort: UserApplicationActionPort,
) {
    private val mutex = Mutex()
    private var activeIdentity: BusinessIdentity? = null
    private var catalogEpoch: Long = 0
    private var contextSequence: Long = 0
    private var lastPublished: PublicationKey? = null

    val state: StateFlow<BusinessDesktopState> = store.state
    val hasActiveIdentity: Boolean get() = activeIdentity != null

    suspend fun activateIdentity(
        identity: BusinessIdentity,
        catalogEpoch: Long,
        initialPage: PageContextSnapshot,
    ) {
        require(catalogEpoch > 0) { "catalogEpoch must be positive" }
        mutex.withLock {
            activeIdentity = identity
            this.catalogEpoch = catalogEpoch
            contextSequence = 0
            lastPublished = null
        }
        publishPage(initialPage)
    }

    suspend fun publishPage(snapshot: PageContextSnapshot): Boolean = mutex.withLock {
        val identity = activeIdentity ?: return@withLock false
        val key = PublicationKey(identity.identityEpoch, snapshot.pageId, snapshot.revision)
        if (key == lastPublished) return@withLock false
        val nextSequence = contextSequence + 1
        contextPublication.publish(identity, catalogEpoch, nextSequence, snapshot)
        contextSequence = nextSequence
        lastPublished = key
        store.dispatch(BusinessDesktopEvent.PageChanged(snapshot))
        true
    }

    fun updateSuggestions(suggestions: List<BusinessFieldSuggestion>) {
        store.dispatch(BusinessDesktopEvent.SuggestionsChanged(suggestions))
    }

    suspend fun executeUserAction(
        executionId: String,
        actionId: String,
        actionVersion: Int,
        input: JsonObject,
    ): ActionBusResult {
        val binding = mutex.withLock {
            val identity = requireNotNull(activeIdentity) { "No active business identity" }
            val page = requireNotNull(state.value.page) { "No active business page" }
            identity to page
        }
        val identity = binding.first
        val page = binding.second
        val command = ActionCommand(
            executionId = executionId,
            actionId = actionId,
            actionVersion = actionVersion,
            input = input,
            origin = ActionOrigin.USER,
            identityScope = identity.actionScope(),
            pageId = page.pageId,
            contextRevision = page.revision,
        )
        val context = ActionContext(
            identityScope = command.identityScope,
            pageId = command.pageId,
            contextRevision = command.contextRevision,
            permissions = identity.permissions,
        )
        return actionPort.execute(command, context)
    }

    suspend fun clearIdentity() {
        mutex.withLock {
            activeIdentity = null
            catalogEpoch = 0
            contextSequence = 0
            lastPublished = null
        }
    }

    private data class PublicationKey(
        val identityEpoch: Long,
        val pageId: String,
        val revision: Long,
    )
}
