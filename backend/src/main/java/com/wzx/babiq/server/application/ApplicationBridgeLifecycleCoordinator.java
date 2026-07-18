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
        this.outboundRequests = outboundRequests;
        this.connections = connections;
        this.identities = identities;
        this.catalogs = catalogs;
        this.contexts = contexts;
        this.actions = actions;
        this.conversations = conversations;
        this.approvals = approvals;
        this.agentLoop = agentLoop;
    }

    @PostConstruct
    void registerListeners() {
        connections.addCloseListener(this::onConnectionClosed);
        identities.addChangeListener(this::onIdentityChanged);
    }

    public void onConnectionClosed(TrustedDesktopConnection connection, String reason) {
        synchronized (connection) {
            runConnectionCleanup("outbound requests", () ->
                    outboundRequests.closePending(connection.webSocketSessionId(), new IOException(reason)));
            runConnectionCleanup("page context", () -> contexts.clear(connection));
            runConnectionCleanup("catalog", () -> catalogs.clear(connection));
            runConnectionCleanup("identity", () -> identities.clear(connection));
            runConnectionCleanup("pending actions", () ->
                    actions.onConnectionClosed(connection.webSocketSessionId(), reason));
        }
    }

    public void onIdentityChanged(
            TrustedDesktopConnection connection,
            TrustedBusinessIdentity oldIdentity,
            TrustedBusinessIdentity newIdentity) {
        if (oldIdentity == null) {
            return;
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
}
