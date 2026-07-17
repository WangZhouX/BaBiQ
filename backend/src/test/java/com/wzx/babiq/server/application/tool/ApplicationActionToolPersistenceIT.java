package com.wzx.babiq.server.application.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wzx.babiq.server.application.action.ApplicationActionTimeoutProperties;
import com.wzx.babiq.server.application.action.PendingApplicationAction;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import com.wzx.babiq.server.application.action.SQLiteApplicationActionTerminalStore;
import com.wzx.babiq.server.application.api.ApplicationActionProtocolHandler;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.application.scope.BusinessIdentityScopeService;
import com.wzx.babiq.server.persistence.entity.ApplicationActionEntity;
import com.wzx.babiq.server.persistence.mapper.ApplicationActionMapper;
import com.wzx.babiq.server.persistence.service.ToolCallPersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
class ApplicationActionToolPersistenceIT {

    private static final Path TEST_DB = Path.of("target", "test-db",
            "application-action-tool-" + UUID.randomUUID() + ".db").toAbsolutePath();
    private static final BusinessIdentityScope SCOPE = BusinessIdentityScope.scoped(
            "desktop-a", "desktop-session-a", "auth-a", 7,
            "user-a", "tenant-a", "platform-a");
    private static final TrustedDesktopConnection CONNECTION = new TrustedDesktopConnection(
            "reservation-a", "desktop-a", "desktop-session-a", "websocket-a");
    private static final TrustedBusinessIdentity IDENTITY = new TrustedBusinessIdentity(
            "reservation-a", "websocket-a", "desktop-a", "desktop-session-a", "auth-a", 7,
            "user-a", "tenant-a", "platform-a", Set.of("lawyer"), Set.of("framework:read"));

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SQLiteApplicationActionTerminalStore store;
    @Autowired private ApplicationActionMapper actionMapper;
    @Autowired private ToolCallPersistenceService toolCalls;

    @Test
    void realToolRegistrationPersistsValidatedMetadataAndCanonicalInputFingerprint() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String threadId = "thread-tool-" + suffix;
        String turnId = "turn-tool-" + suffix;
        String firstToolCallId = "tool-first-" + suffix;
        String secondToolCallId = "tool-second-" + suffix;
        insertReferences(threadId, turnId, firstToolCallId, secondToolCallId);

        BusinessIdentityScopeService scopes = mock(BusinessIdentityScopeService.class);
        ApplicationCatalogRegistry catalogs = mock(ApplicationCatalogRegistry.class);
        ApplicationPageContextRegistry contexts = mock(ApplicationPageContextRegistry.class);
        ApplicationActionProtocolHandler protocol = mock(ApplicationActionProtocolHandler.class);
        when(scopes.withActiveConnectionScope(eq(SCOPE), any())).thenAnswer(invocation -> {
            Function<BusinessIdentityScopeService.ActiveBusinessIdentity, Object> reader = invocation.getArgument(1);
            return Optional.ofNullable(reader.apply(
                    new BusinessIdentityScopeService.ActiveBusinessIdentity(CONNECTION, IDENTITY)));
        });
        when(catalogs.current(CONNECTION)).thenReturn(Optional.of(
                new ApplicationCatalogRegistry.CatalogSnapshot(CONNECTION, 1, catalogPayload(), true)));
        when(contexts.current(CONNECTION)).thenReturn(Optional.of(
                new ApplicationPageContextRegistry.PageContextSnapshot(
                        CONNECTION, 1, 1, contextPayload(), true)));

        ApplicationActionTimeoutProperties timeouts = new ApplicationActionTimeoutProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        Iterator<String> executionIds = java.util.List.of(
                "execution-first-" + suffix, "execution-second-" + suffix).iterator();
        try (PendingApplicationActions pending = new PendingApplicationActions(
                timeouts, store, action -> CompletableFuture.completedFuture(
                PendingApplicationActions.RemoteStatus.running()),
                Executors.newSingleThreadScheduledExecutor(), Clock.systemUTC())) {
            when(protocol.sendActionRequest(any(), any())).thenAnswer(invocation -> {
                PendingApplicationAction action = invocation.getArgument(0);
                PendingApplicationAction.ConnectionContext scope = action.connectionContext();
                pending.acceptedAuthorized(action.executionId(), action.correlation(), scope);
                pending.runningAuthorized(action.executionId(), action.correlation(), scope);
                pending.terminalAuthorized(action.executionId(), action.correlation(), scope,
                        PendingApplicationAction.State.COMPLETED, null);
                return CompletableFuture.completedFuture(null);
            });
            ApplicationActionTool tool = new ApplicationActionTool(
                    json, scopes, catalogs, contexts, pending, protocol,
                    executionIds::next, () -> 0L, new com.wzx.babiq.server.memory.redaction.MemorySecretRedactor(),
                    toolCalls);

            invoke(tool, threadId, turnId, firstToolCallId,
                    json.createObjectNode().put("b", 2).put("secret", "do-not-store").put("a", 1));
            invoke(tool, threadId, turnId, secondToolCallId,
                    json.createObjectNode().put("a", 1).put("b", 2).put("secret", "do-not-store"));
        }

        ApplicationActionEntity first = actionMapper.selectById("execution-first-" + suffix);
        ApplicationActionEntity second = actionMapper.selectById("execution-second-" + suffix);
        String canonical = "{\"actionId\":\"framework.demo\",\"actionVersion\":2,"
                + "\"contextRevision\":7,\"input\":{\"a\":1,\"b\":2,\"secret\":\"do-not-store\"},"
                + "\"pageId\":\"page-1\"}";
        String expectedFingerprint = "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));

        assertThat(first.getActionId()).isEqualTo("framework.demo");
        assertThat(first.getActionVersion()).isEqualTo(2);
        assertThat(first.getRequestFingerprint()).isEqualTo(expectedFingerprint);
        assertThat(second.getRequestFingerprint()).isEqualTo(expectedFingerprint);
        assertThat(first.getRequestFingerprint())
                .hasSize(71)
                .doesNotContain("do-not-store", "execution-first");
        assertThat(jdbc.queryForObject(
                "SELECT CAST(action_id AS TEXT) || '|' || CAST(action_version AS TEXT) || '|' || request_fingerprint "
                        + "FROM bq_application_actions WHERE execution_id = ?",
                String.class, first.getExecutionId()))
                .doesNotContain("do-not-store");
        assertThat(jdbc.queryForObject(
                "SELECT GROUP_CONCAT(COALESCE(payload_summary_redacted, '')) "
                        + "FROM bq_application_action_events WHERE execution_id IN (?, ?)",
                String.class, first.getExecutionId(), second.getExecutionId()))
                .doesNotContain("do-not-store");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM bq_application_action_events WHERE execution_id IN (?, ?)",
                Integer.class, first.getExecutionId(), second.getExecutionId())).isEqualTo(8);
        assertThat(jdbc.queryForList(
                "SELECT execution_id FROM bq_tool_calls WHERE tool_call_id IN (?, ?) ORDER BY tool_call_id",
                String.class, firstToolCallId, secondToolCallId))
                .containsExactly("execution-first-" + suffix, "execution-second-" + suffix);
    }

    private void invoke(
            ApplicationActionTool tool,
            String threadId,
            String turnId,
            String toolCallId,
            JsonNode input) {
        try (ApplicationToolInvocationContext.Scope ignored = ApplicationToolInvocationContext.install(
                new ApplicationToolInvocationContext.Invocation(toolCallId, threadId, turnId, SCOPE))) {
            ApplicationActionToolResult result = tool.applicationAction(
                    "framework.demo", 2, input, "page-1", 7L, new ToolContext(Map.of()));
            assertThat(result.status()).isEqualTo("completed");
        }
    }

    private ObjectNode catalogPayload() {
        ObjectNode action = json.createObjectNode()
                .put("id", "framework.demo")
                .put("version", 2)
                .put("title", "Demo action")
                .put("risk", "read_only")
                .put("enabled", true);
        action.putArray("requiredPermissions").add("framework:read");
        return json.createObjectNode().set(
                "actions", json.createObjectNode().set("framework.demo", action));
    }

    private ObjectNode contextPayload() {
        return json.createObjectNode().put("pageId", "page-1").put("contextRevision", 7);
    }

    private void insertReferences(
            String threadId,
            String turnId,
            String firstToolCallId,
            String secondToolCallId) {
        String now = Instant.now().toString();
        jdbc.update("""
                INSERT INTO bq_threads(thread_id,title,cwd,provider_id,model,sandbox_mode,approval_policy,
                    status,desktop_instance_id,desktop_session_id,auth_session_id,identity_epoch,
                    user_id,tenant_id,platform_id,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, threadId, "tool persistence", "C:/tmp", "p", "m", "read_only", "never",
                "active", SCOPE.desktopInstanceId(), SCOPE.desktopSessionId(), SCOPE.authSessionId(),
                SCOPE.identityEpoch(), SCOPE.userId(), SCOPE.tenantId(), SCOPE.platformId(), now, now);
        jdbc.update("""
                INSERT INTO bq_turns(turn_id,thread_id,status,input_text,cwd,provider_id,model,sandbox_mode,
                    approval_policy,started_at,desktop_instance_id,desktop_session_id,auth_session_id,
                    identity_epoch,user_id,tenant_id,platform_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, turnId, threadId, "RUNNING", "input", "C:/tmp", "p", "m", "read_only", "never", now,
                SCOPE.desktopInstanceId(), SCOPE.desktopSessionId(), SCOPE.authSessionId(), SCOPE.identityEpoch(),
                SCOPE.userId(), SCOPE.tenantId(), SCOPE.platformId());
        insertToolCall(firstToolCallId, threadId, turnId, now);
        insertToolCall(secondToolCallId, threadId, turnId, now);
    }

    private void insertToolCall(String toolCallId, String threadId, String turnId, String now) {
        jdbc.update("""
                INSERT INTO bq_tool_calls(tool_call_id,thread_id,turn_id,tool_name,args_json,status,started_at,
                    desktop_instance_id,desktop_session_id,auth_session_id,identity_epoch,user_id,tenant_id,platform_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, toolCallId, threadId, turnId, "application_action", "{}", "running", now,
                SCOPE.desktopInstanceId(), SCOPE.desktopSessionId(), SCOPE.authSessionId(), SCOPE.identityEpoch(),
                SCOPE.userId(), SCOPE.tenantId(), SCOPE.platformId());
    }
}
