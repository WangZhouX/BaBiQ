package com.wzx.babiq.server.business.upload;

import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Revokes all short-lived binary capabilities bound to one exact READY lease. */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessBinaryLeaseLifecycle {
    private static final Logger log = LoggerFactory.getLogger(BusinessBinaryLeaseLifecycle.class);
    private final BusinessAttachmentTicketService attachmentTickets;
    private final BusinessResourceHandleRegistry resourceHandles;

    public BusinessBinaryLeaseLifecycle(BusinessAttachmentTicketService attachmentTickets,
                                        BusinessResourceHandleRegistry resourceHandles) {
        this.attachmentTickets = Objects.requireNonNull(attachmentTickets, "attachmentTickets");
        this.resourceHandles = Objects.requireNonNull(resourceHandles, "resourceHandles");
    }

    public void revoke(TrustedDesktopConnection connection, ReadyOaSessionLease lease) {
        try {
            attachmentTickets.revokeForConnection(connection, lease);
        } catch (RuntimeException failure) {
            log.warn("Business binary lease cleanup failed: step=attachments, reasonType={}",
                    failure.getClass().getSimpleName());
        }
        try {
            resourceHandles.revoke(connection, lease);
        } catch (RuntimeException failure) {
            log.warn("Business binary lease cleanup failed: step=resources, reasonType={}",
                    failure.getClass().getSimpleName());
        }
    }
}
