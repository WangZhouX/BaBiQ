package com.wzx.babiq.server.business.workbench;

import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos;
import com.wzx.babiq.server.business.oa.client.OaWorkbenchGateway;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.oa.session.OaAuthenticatedRequestExecutor;
import com.wzx.babiq.server.business.oa.session.OaRemoteRequestException;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import com.wzx.babiq.server.business.upload.BusinessAttachmentTicketService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessWorkbenchMutationTest {

    @Test
    void remote_failure_after_gateway_invocation_marks_attachment_unknown() {
        MutationFixture fixture = fixture(false, "op-failed", "batch-failed");

        assertThatThrownBy(() -> fixture.service().create(
                fixture.lease(), identity(), createRequest("op-failed", "batch-failed")))
                .isInstanceOf(OaRemoteRequestException.class)
                .hasMessage("OA_REMOTE_UNAVAILABLE");

        verify(fixture.attachments()).finishScheduleCreate(
                fixture.consumption(), BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN);
    }

    @Test
    void ambiguous_remote_failure_marks_attachment_unknown_and_blocks_retry() {
        MutationFixture fixture = fixture(true, "op-unknown", "batch-unknown");
        BusinessWorkbenchDtos.ScheduleCreateRequest request = createRequest("op-unknown", "batch-unknown");

        assertThatThrownBy(() -> fixture.service().create(fixture.lease(), identity(), request))
                .isInstanceOf(OaRemoteRequestException.class)
                .hasMessage("OA_OUTCOME_UNKNOWN");
        verify(fixture.attachments()).finishScheduleCreate(
                fixture.consumption(), BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN);

        assertThatThrownBy(() -> fixture.service().create(fixture.lease(), identity(), request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("BUSINESS_OPERATION_OUTCOME_UNKNOWN");
        verify(fixture.gateway(), times(1)).createSchedule(eq("tenant-1"), any(), any());
    }

    private static MutationFixture fixture(boolean ambiguous, String operationId, String batchId) {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        BusinessAttachmentTicketService attachments = mock(BusinessAttachmentTicketService.class);
        BusinessAttachmentTicketService.ScheduleAttachmentConsumption consumption =
                mock(BusinessAttachmentTicketService.ScheduleAttachmentConsumption.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("PERSONAL"), isNull()))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(gateway.relationOptions(eq("tenant-1"), any(), eq("CASE"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(Map.of("id", "case-1")));
        when(attachments.canConsumeForScheduleCreate(
                eq(batchId), any(), eq(lease), eq(operationId),
                eq("PERSONAL"), isNull(), eq("type-1"), eq("case-1"), isNull()))
                .thenReturn(true);
        when(attachments.beginScheduleCreate(
                eq(batchId), any(), eq(lease), eq(operationId), eq("user-1"),
                eq("PERSONAL"), isNull(), eq("type-1"), eq("CASE"), eq("case-1"),
                isNull(), eq("0")))
                .thenReturn(consumption);
        when(consumption.fileIds()).thenReturn(List.of("file-1".toCharArray()));
        when(gateway.createSchedule(eq("tenant-1"), any(), any()))
                .thenThrow(OaRemoteRequestException.networkFailure(ambiguous));
        BusinessScheduleService service =
                new BusinessScheduleService(gateway, sessions, executor(), attachments);
        return new MutationFixture(gateway, attachments, consumption, lease, service);
    }

    private static OaAuthenticatedRequestExecutor executor() {
        OaAuthenticatedRequestExecutor executor = mock(OaAuthenticatedRequestExecutor.class);
        when(executor.execute(any(), any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            OaAuthenticatedRequestExecutor.CredentialOperation<Object> operation = invocation.getArgument(2);
            return operation.execute("server-token".toCharArray());
        });
        return executor;
    }

    private static BusinessWorkbenchDtos.ScheduleCreateRequest createRequest(
            String operationId, String batchId) {
        return new BusinessWorkbenchDtos.ScheduleCreateRequest(
                operationId, "PERSONAL", null, null, "title", "type-1",
                "2026-07-27 10:00:00", false, 2, "content", List.of(),
                List.of(Map.of("relationType", "CASE", "relationId", "case-1")),
                batchId, "case-1", "CASE", 0, 0);
    }

    private static ReadyOaSessionLease lease() {
        return new ReadyOaSessionLease(
                "auth-1", "desktop-1", "session-1", "ws-1", "user-1", "tenant-1",
                "2", 3, "credential-1", 1, Instant.parse("2026-07-29T00:00:00Z"));
    }

    private static TrustedBusinessIdentity identity() {
        return new TrustedBusinessIdentity(
                "reservation-1", "ws-1", "desktop-1", "session-1", "auth-1", 7,
                "user-1", "tenant-1", "2", Set.of("lawyer"), Set.of("*"));
    }

    private record MutationFixture(
            OaWorkbenchGateway gateway,
            BusinessAttachmentTicketService attachments,
            BusinessAttachmentTicketService.ScheduleAttachmentConsumption consumption,
            ReadyOaSessionLease lease,
            BusinessScheduleService service) {
    }
}
