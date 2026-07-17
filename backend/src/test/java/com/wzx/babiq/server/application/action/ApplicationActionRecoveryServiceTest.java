package com.wzx.babiq.server.application.action;

import com.wzx.babiq.server.persistence.entity.ApplicationActionEntity;
import com.wzx.babiq.server.persistence.mapper.ApplicationActionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ApplicationActionRecoveryServiceTest {

    private static final Path TEST_DB = Path.of("target", "test-db",
            "application-action-recovery-" + UUID.randomUUID() + ".db").toAbsolutePath();
    private static final PendingApplicationAction.ConnectionContext SCOPE =
            new PendingApplicationAction.ConnectionContext(
                    "reservation", "websocket", "desktop", "session", "auth", 3,
                    "user", "tenant", "platform");

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired private SQLiteApplicationActionTerminalStore store;
    @Autowired private ApplicationActionMapper actionMapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void prepareReferences() {
        jdbc.update("DELETE FROM bq_application_action_events");
        jdbc.update("DELETE FROM bq_application_actions");
        jdbc.update("DELETE FROM bq_tool_calls");
        jdbc.update("DELETE FROM bq_turns");
        jdbc.update("DELETE FROM bq_threads");
        jdbc.update("""
                INSERT INTO bq_threads(thread_id,title,cwd,provider_id,model,sandbox_mode,approval_policy,status,created_at,updated_at)
                VALUES('thread-recovery','t','C:/tmp','p','m','workspace_write','on_request','active',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """);
        jdbc.update("""
                INSERT INTO bq_turns(turn_id,thread_id,status,input_text,cwd,provider_id,model,sandbox_mode,approval_policy,started_at)
                VALUES('turn-recovery','thread-recovery','RUNNING','x','C:/tmp','p','m','workspace_write','on_request',CURRENT_TIMESTAMP)
                """);
    }

    @Test
    @DisplayName("启动恢复过期执行前动作并把执行中动作收口为结果未知")
    void recoveryExpiresPreExecutionAndOrphansExecutingWithoutResumingTurns() {
        register("execution-requested", "tool-requested", PendingApplicationAction.State.REQUESTED);
        register("execution-preview", "tool-preview", PendingApplicationAction.State.PREVIEWED);
        register("execution-running", "tool-running", PendingApplicationAction.State.RUNNING);
        register("execution-completed", "tool-completed", PendingApplicationAction.State.COMPLETED);

        ApplicationActionRecoveryService recovery = new ApplicationActionRecoveryService(
                store, Clock.fixed(Instant.parse("2026-07-17T01:00:00Z"), ZoneOffset.UTC));
        ApplicationActionRecoveryService.RecoveryReport report = recovery.recoverAbandonedActions();
        int eventsAfterFirstRecovery = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bq_application_action_events", Integer.class);
        ApplicationActionRecoveryService.RecoveryReport second = recovery.recoverAbandonedActions();

        assertThat(report.expiredPreExecution()).isEqualTo(2);
        assertThat(report.outcomeUnknownExecuting()).isEqualTo(1);
        assertThat(second.expiredPreExecution()).isZero();
        assertThat(second.outcomeUnknownExecuting()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM bq_application_action_events", Integer.class))
                .isEqualTo(eventsAfterFirstRecovery);
        assertThat(status("execution-requested")).isEqualTo("EXPIRED");
        assertThat(status("execution-preview")).isEqualTo("EXPIRED");
        assertThat(status("execution-running")).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(status("execution-completed")).isEqualTo("COMPLETED");
        assertThat(store.events("execution-running", SCOPE).getLast().getEventType())
                .isEqualTo("recovery_orphaned");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM bq_turns WHERE turn_id='turn-recovery'", String.class))
                .isEqualTo("RUNNING");
    }

    private void register(String executionId, String toolCallId, PendingApplicationAction.State finalState) {
        jdbc.update("""
                INSERT INTO bq_tool_calls(tool_call_id,thread_id,turn_id,tool_name,args_json,status,started_at)
                VALUES(?, 'thread-recovery','turn-recovery','application_action','{}','running',CURRENT_TIMESTAMP)
                """, toolCallId);
        PendingApplicationAction.Correlation correlation =
                new PendingApplicationAction.Correlation("thread-recovery", "turn-recovery", toolCallId);
        PendingApplicationAction requested = new PendingApplicationAction(
                executionId, correlation, PendingApplicationAction.Path.REVERSIBLE_WRITE,
                PendingApplicationAction.State.REQUESTED, null, null, Instant.now(), SCOPE);
        store.recordRegistered(requested, "case.update", 1, "sha256:" + executionId);
        if (finalState != PendingApplicationAction.State.REQUESTED) {
            PendingApplicationAction next = requested.transition(finalState, null, Instant.now());
            if (finalState.isTerminal()) {
                store.recordTerminal(next, false);
            } else {
                store.recordTransition(requested, next, false);
            }
        }
    }

    private String status(String executionId) {
        ApplicationActionEntity entity = actionMapper.selectById(executionId);
        return entity.getStatus();
    }
}
