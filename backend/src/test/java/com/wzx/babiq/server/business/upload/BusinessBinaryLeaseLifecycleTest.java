package com.wzx.babiq.server.business.upload;

import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BusinessBinaryLeaseLifecycleTest {
    @Test
    void revocation_attempts_attachment_and_resource_cleanup_independently() {
        BusinessAttachmentTicketService tickets = mock(BusinessAttachmentTicketService.class);
        BusinessResourceHandleRegistry resources = mock(BusinessResourceHandleRegistry.class);
        TrustedDesktopConnection connection =
                new TrustedDesktopConnection("reservation", "instance", "desktop-session", "ws");
        ReadyOaSessionLease lease = new ReadyOaSessionLease(
                "auth", "instance", "desktop-session", "ws", "user", "tenant", "2",
                7, "credential-ref", 1, Instant.now());
        doThrow(new IllegalStateException("ticket cleanup failed"))
                .when(tickets).revokeForConnection(connection, lease);

        BusinessBinaryLeaseLifecycle lifecycle = new BusinessBinaryLeaseLifecycle(tickets, resources);

        assertThatCode(() -> lifecycle.revoke(connection, lease)).doesNotThrowAnyException();
        verify(tickets).revokeForConnection(connection, lease);
        verify(resources).revoke(connection, lease);
    }
}
