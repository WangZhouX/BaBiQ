package com.wzx.huitai.desktop.integration

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.conversation.BusinessAgentEvent
import com.wzx.huitai.agent.conversation.BusinessAgentIngressEvent
import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import com.wzx.huitai.agent.conversation.BusinessConversationGateway
import com.wzx.huitai.agent.conversation.BusinessMessageAttachment
import com.wzx.huitai.agent.conversation.BusinessProvider
import com.wzx.huitai.agent.conversation.BusinessProviderSelection
import com.wzx.huitai.agent.conversation.BusinessThread
import com.wzx.huitai.agent.conversation.BusinessThreadItem
import com.wzx.huitai.agent.conversation.BusinessTurn
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.desktop.controller.BusinessClipboardPasteCoordinator
import com.wzx.huitai.desktop.controller.BusinessComposerDraftState
import com.wzx.huitai.desktop.controller.BusinessComposerSendCoordinator
import com.wzx.huitai.desktop.controller.BusinessConversationController
import com.wzx.huitai.desktop.controller.mergeBusinessComposerAttachments
import com.wzx.huitai.desktop.auth.BusinessIdentityRegistry
import com.wzx.huitai.desktop.auth.ReadyAgentUsageGate
import com.wzx.huitai.desktop.runtime.BusinessAttachmentIdFactory
import com.wzx.huitai.desktop.runtime.ClipboardImageAttachmentStore
import com.wzx.huitai.desktop.runtime.ClipboardImageSource
import com.wzx.huitai.desktop.state.BusinessAuthenticationStatus
import com.wzx.huitai.desktop.state.BusinessConnectionStatus
import com.wzx.huitai.desktop.state.BusinessDesktopReducer
import com.wzx.huitai.desktop.state.BusinessDesktopState
import com.wzx.huitai.desktop.state.BusinessDesktopStore
import com.wzx.huitai.desktop.state.BusinessIdentity
import com.wzx.huitai.desktop.ui.agent.BusinessAttachmentChooser
import com.wzx.huitai.desktop.ui.agent.BusinessAttachmentPicker
import com.wzx.huitai.desktop.ui.shell.BusinessDesktopShell
import com.wzx.huitai.desktop.ui.theme.HuitaiBusinessTheme
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Exercises the same callback transaction that Main wires, without opening a native dialog. */
class BusinessAgentAttachmentWorkflowIT {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var tempRoot: Path
    private lateinit var documentOne: Path
    private lateinit var documentTwo: Path
    private lateinit var gateway: RecordingGateway
    private lateinit var controllerScope: CoroutineScope
    private lateinit var controller: BusinessConversationController
    private lateinit var store: BusinessDesktopStore

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("business-agent-attachment-workflow")
        documentOne = Files.writeString(tempRoot.resolve("private-contract-one.txt"), "one")
        documentTwo = Files.writeString(tempRoot.resolve("private-contract-two.txt"), "two")
        gateway = RecordingGateway()
        controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = BusinessDesktopStore(BusinessDesktopReducer(), authenticatedState())
        val identityRegistry = BusinessIdentityRegistry().also {
            check(it.publishReady(requireNotNull(store.state.value.identity), 0))
        }
        controller = BusinessConversationController(gateway, store, ReadyAgentUsageGate(identityRegistry), controllerScope)
    }

    @After
    fun tearDown() {
        controller.close()
        controllerScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `shell wires picker removal attachment send screenshot paste safe chips and retained draft`() {
        val chooser = RecordingChooser(listOf(documentOne, documentTwo))
        val pickerIds = ArrayDeque(
            listOf(
                UUID.fromString("00000000-0000-4000-8000-000000000801"),
                UUID.fromString("00000000-0000-4000-8000-000000000802"),
            ),
        )
        val picker = BusinessAttachmentPicker(
            chooser = chooser,
            idFactory = BusinessAttachmentIdFactory(
                uuidSource = pickerIds::removeFirst,
                displayIdEncoder = { uuid ->
                    if (uuid.toString().endsWith("801")) "A-BCDEFG" else "A-HJKLMN"
                },
            ),
        )
        val clipboard = ClipboardImageAttachmentStore(
            controlledRoot = tempRoot.resolve("clipboard"),
            imageSource = object : ClipboardImageSource {
                override fun hasImage(): Boolean = true
                override fun readImage(): BufferedImage = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
            },
            idFactory = BusinessAttachmentIdFactory(
                uuidSource = { UUID.fromString("00000000-0000-4000-8000-000000000803") },
                displayIdEncoder = { "A-PQRSTU" },
            ),
        )

        rule.setContent {
            val desktopState by controller.state.collectAsState()
            var draft by remember { mutableStateOf(BusinessComposerDraftState()) }
            var submitting by remember { mutableStateOf(false) }
            val uiScope = rememberCoroutineScope()
            val sender = remember {
                BusinessComposerSendCoordinator { text, attachments ->
                    controller.startTurn(text, attachments, providerId = null)
                }
            }
            val paste = remember { BusinessClipboardPasteCoordinator(clipboard::hasImage) }
            CompositionLocalProvider(LocalDensity provides Density(0.7f)) {
                HuitaiBusinessTheme {
                    BusinessDesktopShell(
                        state = desktopState,
                        formState = DemoFormState(),
                        composerText = draft.text,
                        composerAttachments = draft.attachments,
                        composerSubmitting = submitting,
                        agentPanelExpanded = true,
                        onChooseFiles = {
                            draft = draft.copy(
                                attachments = mergeBusinessComposerAttachments(
                                    draft.attachments,
                                    picker.choose(currentDrafts = draft.attachments),
                                ),
                            )
                        },
                        onRemoveAttachment = { id ->
                            draft = draft.copy(attachments = draft.attachments.filterNot { it.id == id })
                        },
                        onPasteImage = {
                            paste.request { complete ->
                                uiScope.launch {
                                    try {
                                        clipboard.capture(
                                            existingIds = draft.attachments.mapTo(hashSetOf()) { it.id },
                                            existingDisplayIds = draft.attachments.mapTo(hashSetOf()) { it.displayId },
                                        )?.let { screenshot ->
                                            draft = draft.copy(
                                                attachments = mergeBusinessComposerAttachments(
                                                    draft.attachments,
                                                    listOf(screenshot),
                                                ),
                                            )
                                        }
                                    } finally {
                                        complete()
                                    }
                                }
                            }
                        },
                        onSend = {
                            val captured = draft
                            submitting = true
                            uiScope.launch {
                                try {
                                    val result = sender.submit(captured)
                                    draft = sender.reconcile(draft, captured, result)
                                } finally {
                                    submitting = false
                                }
                            }
                        },
                        modifier = Modifier.requiredWidth(1400.dp),
                    )
                }
            }
        }

        assertComposerActionsInsideViewport()
        rule.onNodeWithTag("agent-composer-attach").performClick()
        assertEquals(1, chooser.calls)
        listOf("A-BCDEFG", "private-contract-one.txt", "A-HJKLMN", "private-contract-two.txt").forEach {
            rule.onNodeWithText(it, substring = true).assertExists()
        }
        assertPathVariantsNotDisplayed(documentOne)
        assertPathVariantsNotDisplayed(documentTwo)

        rule.onNodeWithContentDescription("移除附件 A-BCDEFG").performClick()
        rule.onNodeWithText("private-contract-one.txt", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("agent-composer-send").assertIsEnabled()

        gateway.failNext = true
        rule.onNodeWithTag("agent-composer-send").performClick()
        rule.waitUntil(5_000) { gateway.startCalls == 1 }
        rule.onNodeWithText("A-HJKLMN", substring = true).assertExists()
        assertEquals("", gateway.starts.first().text)
        assertEquals(listOf("A-HJKLMN"), gateway.starts.first().attachments.map { it.displayId })

        waitForComposerSendEnabled()
        rule.onNodeWithTag("agent-composer-send").performClick()
        rule.waitUntil(5_000) { gateway.startCalls == 2 }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("agent-attachment-A-HJKLMN").fetchSemanticsNodes().isEmpty()
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("agent-message-attachment-A-HJKLMN").fetchSemanticsNodes().size == 1
        }
        rule.onNodeWithTag("agent-message-attachment-A-HJKLMN").assertExists()
        assertPathVariantsNotDisplayed(documentTwo)

        rule.onNodeWithTag("agent-composer-input").performClick().performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.V)
            keyUp(Key.CtrlLeft)
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("agent-attachment-A-PQRSTU").fetchSemanticsNodes().size == 1
        }
        rule.onNodeWithText("A-PQRSTU", substring = true).assertExists()
        val screenshot = clipboard.controlledRoot.toFile().listFiles().orEmpty().single { it.extension == "png" }
        assertPathVariantsNotDisplayed(screenshot.toPath())

        assertTrue(gateway.starts[1].attachments.single().localPath == documentTwo.toString())
        assertFalse(gateway.starts[1].toString().contains(documentTwo.toString()))
    }

    private fun waitForComposerSendEnabled() {
        rule.waitUntil(5_000) {
            runCatching {
                rule.onNodeWithTag("agent-composer-send").assertIsEnabled()
                true
            }.getOrDefault(false)
        }
        rule.onNodeWithTag("agent-composer-send").assertIsEnabled()
    }

    private fun assertComposerActionsInsideViewport() {
        val viewport = rule.onRoot().fetchSemanticsNode().boundsInRoot
        listOf("agent-composer-attach", "agent-composer-send").forEach { tag ->
            val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(
                bounds.left >= viewport.left &&
                    bounds.top >= viewport.top &&
                    bounds.right <= viewport.right &&
                    bounds.bottom <= viewport.bottom,
                "$tag bounds=$bounds must be fully inside root viewport=$viewport",
            )
        }
    }

    private fun assertPathVariantsNotDisplayed(path: Path) {
        pathVariants(path).forEach { sensitive ->
            rule.onAllNodesWithText(sensitive, substring = true).assertCountEquals(0)
        }
    }

    private fun pathVariants(path: Path): Set<String> {
        val raw = path.toAbsolutePath().normalize().toString()
        return linkedSetOf(raw, raw.replace('\\', '/'), path.toAbsolutePath().normalize().toUri().toString())
    }

    private fun authenticatedState(): BusinessDesktopState = BusinessDesktopState(
        connectionStatus = BusinessConnectionStatus.CONNECTED,
        authenticationStatus = BusinessAuthenticationStatus.AUTHENTICATED,
        identity = BusinessIdentity(
            desktopInstanceId = "desktop-attachment-it",
            desktopSessionId = "session-attachment-it",
            authSessionId = "auth-attachment-it",
            identityEpoch = 1,
            userId = "user-attachment-it",
            tenantId = "tenant-attachment-it",
            platformId = "platform-attachment-it",
            roles = emptySet(),
            permissions = emptySet(),
        ),
        currentThread = BusinessThread("thread-attachment-it", "attachments", tempRoot.toString()),
    )

    private class RecordingChooser(private val selected: List<Path>) : BusinessAttachmentChooser {
        var calls: Int = 0
        override fun chooseFiles(): List<Path> {
            calls++
            return selected
        }
    }

    private inner class RecordingGateway : BusinessConversationGateway {
        private val mutableEvents = MutableSharedFlow<BusinessAgentEvent>(extraBufferCapacity = 16)
        override val events: Flow<BusinessAgentEvent> = mutableEvents
        override val ingressEvents: Flow<BusinessAgentIngressEvent> = mutableEvents.map {
            BusinessAgentIngressEvent(it, authSessionId = "auth-attachment-it", identityEpoch = 1)
        }
        val starts = CopyOnWriteArrayList<Start>()
        @Volatile
        var failNext: Boolean = false
        val startCalls: Int get() = starts.size

        override suspend fun listProviders(): List<BusinessProvider> = emptyList()
        override suspend fun setActiveProvider(providerId: String, modelId: String?): BusinessProviderSelection =
            BusinessProviderSelection(providerId, modelId ?: "model")

        override suspend fun createThread(cwd: String): BusinessThread = error("unused")

        override suspend fun startTurn(
            threadId: String,
            text: String,
            attachments: List<BusinessAttachmentDraft>,
            providerId: String?,
        ): BusinessTurn {
            starts += Start(text, attachments.toList())
            if (failNext) {
                failNext = false
                delay(100)
                error("offline")
            }
            val turn = BusinessTurn("turn-attachment-$startCalls", threadId)
            controllerScope.launch {
                delay(30)
                mutableEvents.emit(BusinessAgentEvent.TurnStarted(threadId, turn.id))
                mutableEvents.emit(
                    BusinessAgentEvent.ItemAdded(
                        threadId,
                        turn.id,
                        BusinessThreadItem.UserMessage(
                            id = "message-${turn.id}",
                            text = text,
                            attachments = attachments.map { draft ->
                                BusinessMessageAttachment(
                                    id = draft.id,
                                    displayId = draft.displayId,
                                    name = draft.name,
                                    mediaType = if (draft.displayType == "图片") "image/png" else "text/plain",
                                    sizeBytes = draft.sizeBytes,
                                    sha256 = "a".repeat(64),
                                    source = if (draft.displayType == "图片") "CLIPBOARD_IMAGE" else "SELECTED_FILE",
                                    localPath = draft.localPath,
                                )
                            },
                        ),
                    ),
                )
            }
            return turn
        }

        override suspend fun cancelTurn(turnId: String): Boolean = false
        override fun close() = Unit
    }

    private data class Start(
        val text: String,
        val attachments: List<BusinessAttachmentDraft>,
    )
}
