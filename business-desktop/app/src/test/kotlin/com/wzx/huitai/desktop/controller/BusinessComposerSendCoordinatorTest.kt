package com.wzx.huitai.desktop.controller

import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import com.wzx.huitai.desktop.ui.agent.BusinessAttachmentSelectionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class BusinessComposerSendCoordinatorTest {
    @Test
    fun `successful submission clears captured draft and failed submission retains it`() = runTest {
        val captured = BusinessComposerDraftState("please check", listOf(attachment(1)))
        val successful = BusinessComposerSendCoordinator { _, _ -> Unit }
        val failed = BusinessComposerSendCoordinator { _, _ -> error("offline") }

        val success = successful.submit(captured)
        val failure = failed.submit(captured)

        assertTrue(success.succeeded)
        assertEquals(BusinessComposerDraftState(), success.resultingDraft)
        assertFalse(failure.succeeded)
        assertEquals(captured, failure.resultingDraft)
    }

    @Test
    fun `successful pending submission only clears captured values and preserves later edits`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = attachment(1)
        val later = attachment(2)
        val captured = BusinessComposerDraftState("first", listOf(first))
        val coordinator = BusinessComposerSendCoordinator { _, _ ->
            entered.complete(Unit)
            release.await()
        }

        val pending = async { coordinator.submit(captured) }
        entered.await()
        val current = BusinessComposerDraftState("edited while pending", listOf(first, later))
        release.complete(Unit)
        val result = pending.await()

        assertEquals(
            BusinessComposerDraftState("edited while pending", listOf(later)),
            coordinator.reconcile(current, captured, result),
        )
    }

    @Test
    fun `real in flight guard accepts only one concurrent submission`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var starts = 0
        val coordinator = BusinessComposerSendCoordinator { _, _ ->
            starts++
            entered.complete(Unit)
            release.await()
        }
        val captured = BusinessComposerDraftState("one", emptyList())

        val first = async { coordinator.submit(captured) }
        entered.await()
        val second = async { coordinator.submit(captured) }
        val duplicate = second.await()
        release.complete(Unit)
        val accepted = first.await()

        assertTrue(accepted.accepted)
        assertFalse(duplicate.accepted)
        assertEquals(1, starts)
    }

    @Test
    fun `identity scope changes clear draft and local attachment error atomically`() {
        val first = BusinessComposerIdentityScope(
            desktopInstanceId = "desktop-1",
            desktopSessionId = "session-1",
            authSessionId = "auth-1",
            identityEpoch = 1,
            userId = "user-1",
            tenantId = "tenant-1",
            platformId = "platform-1",
        )
        val second = first.copy(authSessionId = "auth-2", identityEpoch = 2, userId = "user-2")
        val session = BusinessComposerSessionState(
            identityScope = first,
            draft = BusinessComposerDraftState("private", listOf(attachment(1))),
            attachmentError = BusinessComposerAttachmentError("ATTACHMENT_DUPLICATE", "同一文件不能重复添加"),
        )

        assertEquals(session, session.forIdentity(first))
        assertEquals(
            BusinessComposerSessionState(identityScope = second),
            session.forIdentity(second),
        )
        assertEquals(
            BusinessComposerSessionState(identityScope = null),
            session.forIdentity(null),
        )
    }

    @Test
    fun `clipboard paste scheduling is single flight and ordinary text is not consumed`() {
        var imageAvailable = false
        var availabilityChecks = 0
        var schedules = 0
        var completion: (() -> Unit)? = null
        val coordinator = BusinessClipboardPasteCoordinator {
            availabilityChecks++
            imageAvailable
        }
        val schedule: ((() -> Unit) -> Unit) = {
            schedules++
            completion = it
        }

        assertFalse(coordinator.request(schedule))
        assertEquals(0, schedules)
        imageAvailable = true
        assertTrue(coordinator.request(schedule))
        assertTrue(coordinator.request(schedule))
        assertEquals(1, schedules)
        assertEquals(2, availabilityChecks)

        requireNotNull(completion).invoke()
        assertTrue(coordinator.request(schedule))
        assertEquals(2, schedules)
    }

    @Test
    fun `merged attachment policy rejects duplicate count and total size with path free errors`() {
        val existing = attachment(1, path = "C:/private/customer.pdf", sizeBytes = 1)
        val duplicate = attachment(2, path = "C:/private/./customer.pdf", sizeBytes = 1)
        val duplicateFailure = assertFailsWith<BusinessComposerAttachmentException> {
            mergeBusinessComposerAttachments(listOf(existing), listOf(duplicate))
        }
        assertEquals("ATTACHMENT_DUPLICATE", duplicateFailure.code)
        assertFalse(duplicateFailure.toString().contains("customer.pdf"))
        assertFalse(duplicateFailure.toString().contains("C:/private"))

        val firstSeven = (1..7).map { attachment(it, path = "C:/safe/$it.txt", sizeBytes = 1) }
        val finalTwo = (8..9).map { attachment(it, path = "C:/safe/$it.txt", sizeBytes = 1) }
        assertEquals(
            "ATTACHMENT_LIMIT_EXCEEDED",
            assertFailsWith<BusinessComposerAttachmentException> {
                mergeBusinessComposerAttachments(firstSeven, finalTwo)
            }.code,
        )

        assertEquals(
            "ATTACHMENT_TOTAL_TOO_LARGE",
            assertFailsWith<BusinessComposerAttachmentException> {
                mergeBusinessComposerAttachments(
                    current = listOf(attachment(1, path = "C:/safe/a.txt", sizeBytes = 30L * 1024 * 1024)),
                    additions = listOf(attachment(2, path = "C:/safe/b.txt", sizeBytes = 21L * 1024 * 1024)),
                )
            }.code,
        )
    }

    @Test
    fun `safe local attachment errors retain only stable code and message`() {
        val privatePath = "C:/private/customer/contracts/hidden.pdf"
        val error = safeComposerAttachmentError(IllegalStateException(privatePath))
        val knownCodeError = safeComposerAttachmentError("ATTACHMENT_PATH_INVALID", privatePath)

        assertEquals("ATTACHMENT_LOCAL_FAILED", error.code)
        assertFalse(error.message.contains(privatePath))
        assertFalse(error.toString().contains(privatePath))
        assertEquals("ATTACHMENT_PATH_INVALID", knownCodeError.code)
        assertFalse(knownCodeError.message.contains(privatePath))
        assertFalse(knownCodeError.toString().contains(privatePath))
    }

    @Test
    fun `picker duplicate failure maps through the Main boundary to a visible path free error`() {
        val privatePath = "C:/private/customer/contracts/hidden.pdf"
        val pickerFailure = BusinessAttachmentSelectionException("ATTACHMENT_DUPLICATE", privatePath)

        val visible = safeComposerAttachmentError(pickerFailure.code, pickerFailure.message)

        assertEquals("ATTACHMENT_DUPLICATE", visible.code)
        assertEquals("同一文件不能重复添加", visible.message)
        assertFalse(visible.toString().contains(privatePath))
    }

    private fun attachment(
        index: Int,
        path: String = "C:/private/$index.txt",
        sizeBytes: Long = index.toLong(),
    ): BusinessAttachmentDraft = BusinessAttachmentDraft(
        id = "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
        displayId = "A-${listOf("BCDEFG", "HJKLMN", "PQRSTU", "VWXYZ2", "345678", "BCDEFH", "JKLMNP", "QRSTUV", "WXYZ23")[index - 1]}",
        name = "$index.txt",
        localPath = path,
        sizeBytes = sizeBytes,
        displayType = "文本",
    )
}
