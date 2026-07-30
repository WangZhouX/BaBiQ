package com.wzx.babiq.server.application;

import com.wzx.babiq.server.agent.AgentLoop;
import com.wzx.babiq.server.agent.PendingApprovals;
import com.wzx.babiq.server.application.action.ApplicationOutboundRequestTracker;
import com.wzx.babiq.server.application.action.PendingApplicationAction;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.oa.session.BusinessOaAttachHandleRegistry;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import com.wzx.babiq.server.business.upload.BusinessBinaryLeaseLifecycle;
import com.wzx.babiq.server.conversation.ConversationService;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/** 统一协调业务桌面连接关闭与身份切换的跨组件失效语义。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class ApplicationBridgeLifecycleCoordinator
{
    private static final Logger log = LoggerFactory.getLogger(ApplicationBridgeLifecycleCoordinator.class);

    private final ApplicationOutboundRequestTracker outboundRequests;
    private final ApplicationIdentityRegistry identities;
    private final BusinessDesktopConnectionRegistry connections;
    private final ApplicationCatalogRegistry catalogs;
    private final ApplicationPageContextRegistry contexts;
    private final PendingApplicationActions actions;
    private final ConversationService conversations;
    private final PendingApprovals approvals;
    private final AgentLoop agentLoop;
    private final BusinessOaSessionRegistry oaSessions;
    private final BusinessOaAttachHandleRegistry attachHandles;
    private final BusinessBinaryLeaseLifecycle binaryLeaseLifecycle;

    /** Compatibility constructor retained for focused unit tests that do not exercise the business OA gate. */
    public ApplicationBridgeLifecycleCoordinator(
            ApplicationOutboundRequestTracker outboundRequests,
            BusinessDesktopConnectionRegistry connections,
            ApplicationIdentityRegistry identities,
            ApplicationCatalogRegistry catalogs,
            ApplicationPageContextRegistry contexts,
            PendingApplicationActions actions,
            ConversationService conversations,
            PendingApprovals approvals,
            AgentLoop agentLoop) {
        this(outboundRequests, connections, identities, catalogs, contexts, actions, conversations, approvals,
                agentLoop, null, null, null);
    }

    public ApplicationBridgeLifecycleCoordinator(
            ApplicationOutboundRequestTracker outboundRequests,
            BusinessDesktopConnectionRegistry connections,
            ApplicationIdentityRegistry identities,
            ApplicationCatalogRegistry catalogs,
            ApplicationPageContextRegistry contexts,
            PendingApplicationActions actions,
            ConversationService conversations,
            PendingApprovals approvals,
            AgentLoop agentLoop,
            BusinessOaSessionRegistry oaSessions) {
        this(outboundRequests, connections, identities, catalogs, contexts, actions, conversations, approvals,
                agentLoop, oaSessions, null, null);
    }

    public ApplicationBridgeLifecycleCoordinator(
            ApplicationOutboundRequestTracker outboundRequests,
            BusinessDesktopConnectionRegistry connections,
            ApplicationIdentityRegistry identities,
            ApplicationCatalogRegistry catalogs,
            ApplicationPageContextRegistry contexts,
            PendingApplicationActions actions,
            ConversationService conversations,
            PendingApprovals approvals,
            AgentLoop agentLoop,
            BusinessOaSessionRegistry oaSessions,
            BusinessOaAttachHandleRegistry attachHandles) {
        this(outboundRequests, connections, identities, catalogs, contexts, actions, conversations, approvals,
                agentLoop, oaSessions, attachHandles, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ApplicationBridgeLifecycleCoordinator(
            ApplicationOutboundRequestTracker outboundRequests,
            BusinessDesktopConnectionRegistry connections,
            ApplicationIdentityRegistry identities,
            ApplicationCatalogRegistry catalogs,
            ApplicationPageContextRegistry contexts,
            PendingApplicationActions actions,
            ConversationService conversations,
            PendingApprovals approvals,
            AgentLoop agentLoop,
            BusinessOaSessionRegistry oaSessions,
            BusinessOaAttachHandleRegistry attachHandles,
            BusinessBinaryLeaseLifecycle binaryLeaseLifecycle) {
        this.outboundRequests = outboundRequests;
        this.connections = connections;
        this.identities = identities;
        this.catalogs = catalogs;
        this.contexts = contexts;
        this.actions = actions;
        this.conversations = conversations;
        this.approvals = approvals;
        this.agentLoop = agentLoop;
        this.oaSessions = oaSessions;
        this.attachHandles = attachHandles;
        this.binaryLeaseLifecycle = binaryLeaseLifecycle;
    }

    @PostConstruct
    void registerListeners() {
        connections.addCloseListener(this::onConnectionClosed);
        identities.addChangeListener(this::onIdentityChanged);
    }

    public void onConnectionClosed(TrustedDesktopConnection connection, String reason) {
        java.util.Optional<TrustedBusinessIdentity> installedIdentity = identities.current(connection);
        ReadyOaSessionLease binaryLease = currentReady(
                connection, installedIdentity == null ? null : installedIdentity.orElse(null));
        synchronized (connection) {
            runConnectionCleanup("outbound requests", () ->
                    outboundRequests.closePending(connection.webSocketSessionId(), new IOException(reason)));
            runConnectionCleanup("page context", () -> contexts.clear(connection));
            runConnectionCleanup("catalog", () -> catalogs.clear(connection));
            if (attachHandles != null) {
                runConnectionCleanup("OA attach handles", () -> attachHandles.revoke(connection));
            }
            if (oaSessions != null) {
                runConnectionCleanup("OA session", () ->
                        oaSessions.detachBeforeCredentialCleanup(connection));
            }
            if (binaryLeaseLifecycle != null && binaryLease != null) {
                runConnectionCleanup("binary lease", () ->
                        binaryLeaseLifecycle.revoke(connection, binaryLease));
            }
            runConnectionCleanup("identity", () -> identities.clear(connection));
            runConnectionCleanup("pending actions", () ->
                    actions.onConnectionClosed(connection.webSocketSessionId(), reason));
        }
        if (oaSessions != null) {
            runConnectionCleanup("OA credential cleanup",
                    oaSessions::drainPendingCredentialCleanup);
        }
    }

    public void onIdentityChanged(
            TrustedDesktopConnection connection,
            TrustedBusinessIdentity oldIdentity,
            TrustedBusinessIdentity newIdentity) {
        if (oldIdentity == null) {
            return;
        }
        ReadyOaSessionLease binaryLease = currentReady(connection, oldIdentity);
        if (binaryLeaseLifecycle != null && binaryLease != null) {
            runCleanup("binary lease", () -> binaryLeaseLifecycle.revoke(connection, binaryLease));
        }
        BusinessIdentityScope oldScope = scope(oldIdentity);
        java.util.List<String> affectedThreads = java.util.List.of();
        try {
            affectedThreads = conversations.expirePreExecutionTurns(oldScope, "business identity changed");
        } catch (RuntimeException failure) {
            log.warn("Business identity cleanup failed: step=turns, reasonType={}",
                    failure.getClass().getSimpleName());
        }
        for (String threadId : affectedThreads) {
            runCleanup("approval", () -> approvals.remove(threadId));
            runCleanup("paused agent", () -> agentLoop.forgetPaused(threadId));
        }
        runCleanup("application action", () -> actions.expirePreExecution(
                actionScope(oldIdentity), "business identity changed"));
    }

    private void runCleanup(String step, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException failure) {
            log.warn("Business identity cleanup failed: step={}, reasonType={}",
                    step, failure.getClass().getSimpleName());
        }
    }

    /** 连接关闭时每一步独立失败，日志不包含异常消息或业务 payload。 */
    private void runConnectionCleanup(String step, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException failure) {
            log.warn("Business desktop close cleanup failed: step={}, reasonType={}",
                    step, failure.getClass().getSimpleName());
        }
    }

    private static BusinessIdentityScope scope(TrustedBusinessIdentity identity) {
        return BusinessIdentityScope.scoped(
                identity.desktopInstanceId(), identity.desktopSessionId(), identity.authSessionId(),
                identity.identityEpoch(), identity.userId(), identity.tenantId(), identity.platformId());
    }

    private static PendingApplicationAction.ConnectionContext actionScope(TrustedBusinessIdentity identity) {
        return new PendingApplicationAction.ConnectionContext(
                identity.reservationId(), identity.webSocketSessionId(),
                identity.desktopInstanceId(), identity.desktopSessionId(), identity.authSessionId(),
                identity.identityEpoch(), identity.userId(), identity.tenantId(), identity.platformId());
    }

    private ReadyOaSessionLease currentReady(
            TrustedDesktopConnection connection,
            TrustedBusinessIdentity identity) {
        if (oaSessions == null || identity == null) {
            return null;
        }
        try {
            return oaSessions.currentReady(connection, identity).orElse(null);
        } catch (RuntimeException failure) {
            log.warn("Business binary lease capture failed: reasonType={}",
                    failure.getClass().getSimpleName());
            return null;
        }
    }
}
