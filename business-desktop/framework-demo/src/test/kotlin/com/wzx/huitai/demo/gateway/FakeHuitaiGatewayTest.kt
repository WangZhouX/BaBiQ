package com.wzx.huitai.demo.gateway

import com.wzx.huitai.demo.model.DemoFormState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class FakeHuitaiGatewayTest {
    @Test
    fun `草稿和提交按执行标识保存并支持幂等查询`() {
        val gateway = FakeHuitaiGateway()
        val state = DemoFormState()

        val firstDraft = gateway.saveDraft("draft-1", state)
        val repeatedDraft = gateway.saveDraft("draft-1", state.copy(revision = 7))
        val firstSubmission = gateway.submit("submit-1", state)
        val repeatedSubmission = gateway.submit("submit-1", state.copy(revision = 8))

        assertIs<FakeGatewayResult.Confirmed>(firstDraft)
        assertIs<FakeGatewayResult.Confirmed>(repeatedDraft)
        assertIs<FakeGatewayResult.Confirmed>(firstSubmission)
        assertIs<FakeGatewayResult.Confirmed>(repeatedSubmission)
        assertEquals(2, gateway.draftRequestCount)
        assertEquals(2, gateway.submissionRequestCount)
        assertEquals(1, gateway.draftWriteCount)
        assertEquals(1, gateway.submissionWriteCount)
        assertEquals(state, gateway.queryDraft("draft-1")?.state)
        assertEquals(state, gateway.querySubmission("submit-1")?.state)
    }

    @Test
    fun `确定性响应丢失模式先提交记录再返回发送后丢失`() {
        val gateway = FakeHuitaiGateway(
            submitMode = FakeGatewayMode.RESPONSE_LOST_AFTER_WRITE,
        )

        val result = gateway.submit("submit-lost", DemoFormState())

        assertIs<FakeGatewayResult.SentButResponseLost>(result)
        assertNotNull(gateway.querySubmission("submit-lost"))
        assertEquals(1, gateway.submissionWriteCount)
        assertEquals(1, gateway.submissionQueryCount)
    }
}
