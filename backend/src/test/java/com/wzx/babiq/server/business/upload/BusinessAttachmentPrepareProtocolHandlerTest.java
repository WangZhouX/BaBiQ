package com.wzx.babiq.server.business.upload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionResolver;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import com.wzx.babiq.server.business.workbench.BusinessScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessAttachmentPrepareProtocolHandlerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void prepare_returns_opaque_batch_and_ticket_without_identity_or_remote_file_ids() {
        TrustedDesktopConnection connection = connection();
        ReadyOaSessionLease lease = lease(connection, 3);
        BusinessAttachmentTicketService tickets = new BusinessAttachmentTicketService();
        BusinessAttachmentPrepareProtocolHandler handler = handler(connection, lease, tickets);

        Object result = handler.handle("business/attachments/upload/prepare", request(), mock(WebSocketSession.class));
        String json = write(result);

        assertThat(json).contains("attachmentBatchId", "ticket", "expiresAt");
        assertThat(json).doesNotContain("accessToken", "refreshToken", "fileId", "tenant-1", "user-1");
    }

    @Test
    void prepare_requires_server_checked_form_revision_scope_and_parent_relation() {
        TrustedDesktopConnection connection = connection();
        ReadyOaSessionLease lease = lease(connection, 3);
        BusinessAttachmentPrepareProtocolHandler handler = handler(connection, lease,
                new BusinessAttachmentTicketService());

        ObjectNode missingRevision = request();
        missingRevision.remove("formRevision");
        assertThatThrownBy(() -> handler.handle(BusinessAttachmentPrepareProtocolHandler.METHOD,
                        missingRevision, mock(WebSocketSession.class)))
                .isInstanceOf(com.wzx.babiq.server.api.error.JsonRpcException.class);

        ObjectNode clientActor = request();
        clientActor.put("actorUserId", "attacker");
        assertThatThrownBy(() -> handler.handle(BusinessAttachmentPrepareProtocolHandler.METHOD,
                        clientActor, mock(WebSocketSession.class)))
                .isInstanceOf(com.wzx.babiq.server.api.error.JsonRpcException.class);
    }

    @Test
    void prepare_rejects_unknown_fields_credentials_and_non_schedule_operation() {
        TrustedDesktopConnection connection = connection();
        ReadyOaSessionLease lease = lease(connection, 1);
        BusinessAttachmentPrepareProtocolHandler handler = handler(connection, lease,
                new BusinessAttachmentTicketService());

        ObjectNode unknown = request();
        unknown.put("accessToken", "secret");
        assertThatThrownBy(() -> handler.handle("business/attachments/upload/prepare", unknown, mock(WebSocketSession.class)))
                .isInstanceOf(com.wzx.babiq.server.api.error.JsonRpcException.class);

        ObjectNode invalidOperation = request();
        invalidOperation.put("operation", "AGENT_UPLOAD");
        assertThatThrownBy(() -> handler.handle("business/attachments/upload/prepare", invalidOperation, mock(WebSocketSession.class)))
                .isInstanceOf(com.wzx.babiq.server.api.error.JsonRpcException.class);
    }

    @Test
    void prepare_rejects_malformed_declarations_before_creating_ticket() {
        TrustedDesktopConnection connection = connection();
        ReadyOaSessionLease lease = lease(connection, 1);
        BusinessAttachmentTicketService tickets = new BusinessAttachmentTicketService();
        BusinessAttachmentPrepareProtocolHandler handler = handler(connection, lease, tickets);
        ObjectNode request = request();
        ArrayNode files = (ArrayNode) request.get("files");
        ((ObjectNode) files.get(0)).put("mediaType", "text/html");

        assertThatThrownBy(() -> handler.handle("business/attachments/upload/prepare", request, mock(WebSocketSession.class)))
                .isInstanceOf(com.wzx.babiq.server.api.error.JsonRpcException.class);
    }

    @Test
    void prepare_forwards_the_exact_service_record_and_leaf_to_server_authorization() {
        TrustedDesktopConnection connection = connection();
        ReadyOaSessionLease lease = lease(connection, 1);
        BusinessScheduleService schedules = mock(BusinessScheduleService.class);
        BusinessAttachmentPrepareProtocolHandler handler = handler(
                connection, lease, new BusinessAttachmentTicketService(), schedules);
        ObjectNode request = request();
        request.put("parentRelationType", "SERVICE");
        request.put("parentResourceId", "project-1");
        request.put("parentRecordId", "record-1");

        handler.handle(BusinessAttachmentPrepareProtocolHandler.METHOD, request, mock(WebSocketSession.class));

        verify(schedules).authorizeAttachmentPrepare(
                any(), any(), org.mockito.ArgumentMatchers.eq("PERSONAL"), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("type-1"), org.mockito.ArgumentMatchers.eq("SERVICE"),
                org.mockito.ArgumentMatchers.eq("project-1"), org.mockito.ArgumentMatchers.eq("record-1"),
                org.mockito.ArgumentMatchers.eq(0L));
    }

    private static BusinessAttachmentPrepareProtocolHandler handler(TrustedDesktopConnection connection,
                                                                      ReadyOaSessionLease lease,
                                                                      BusinessAttachmentTicketService tickets) {
        return handler(connection, lease, tickets, mock(BusinessScheduleService.class));
    }

    private static BusinessAttachmentPrepareProtocolHandler handler(TrustedDesktopConnection connection,
                                                                      ReadyOaSessionLease lease,
                                                                      BusinessAttachmentTicketService tickets,
                                                                      BusinessScheduleService schedules) {
        BusinessDesktopConnectionResolver resolver = mock(BusinessDesktopConnectionResolver.class);
        when(resolver.requireFinalized(any())).thenReturn(connection);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        when(sessions.captureReady(connection)).thenReturn(lease);
        ApplicationIdentityRegistry identities = mock(ApplicationIdentityRegistry.class);
        when(identities.current(connection)).thenReturn(java.util.Optional.of(identity(connection, lease)));
        return new BusinessAttachmentPrepareProtocolHandler(tickets, resolver, sessions, identities, schedules, JSON);
    }

    private static ObjectNode request() {
        ObjectNode input = JSON.createObjectNode();
        input.put("operation", "SCHEDULE_CREATE");
        input.put("clientOperationId", "client-op-1");
        input.put("parentResourceId", "case-1");
        input.put("parentRelationType", "CASE");
        input.put("scope", "PERSONAL");
        input.put("typeId", "type-1");
        input.put("formRevision", 0);
        ArrayNode files = input.putArray("files");
        files.addObject().put("fileName", "brief.pdf").put("sizeBytes", 3).put("mediaType", "application/pdf");
        return input;
    }

    private static TrustedDesktopConnection connection() {
        return new TrustedDesktopConnection("reservation-1", "instance-1", "desktop-1", "ws-1");
    }

    private static ReadyOaSessionLease lease(TrustedDesktopConnection connection, long generation) {
        return new ReadyOaSessionLease("auth-1", connection.desktopInstanceId(), connection.desktopSessionId(),
                connection.webSocketSessionId(), "user-1", "tenant-1", "2", generation,
                "credential-" + generation, 1, Instant.now());
    }

    private static TrustedBusinessIdentity identity(TrustedDesktopConnection connection, ReadyOaSessionLease lease) {
        return new TrustedBusinessIdentity(connection.reservationId(), connection.webSocketSessionId(),
                connection.desktopInstanceId(), connection.desktopSessionId(), lease.authSessionId(), 1,
                lease.userId(), lease.tenantId(), lease.platformId(), Set.of("LAWYER"), Set.of("schedule:create"));
    }

    private static String write(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
}
