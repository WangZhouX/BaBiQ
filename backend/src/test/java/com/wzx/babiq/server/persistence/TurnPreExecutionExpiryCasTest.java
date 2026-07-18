package com.wzx.babiq.server.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.repository.TurnRecord;
import com.wzx.babiq.server.persistence.entity.TurnEntity;
import com.wzx.babiq.server.persistence.mapper.TurnMapper;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TurnPreExecutionExpiryCasTest {

    private static final Path DB = Path.of("target", "test-db",
            "turn-expiry-cas-" + UUID.randomUUID() + ".db").toAbsolutePath();
    private static final BusinessIdentityScope SCOPE = BusinessIdentityScope.scoped(
            "desktop", "desktop-session", "auth", 3, "user", "tenant", "platform");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", DB::toString);
    }

    @Autowired private TurnPersistenceService turns;
    @Autowired private TurnMapper mapper;
    @Autowired private ConversationRepository conversations;
    @Autowired private ConversationService conversationService;

    @Test
    void identityExpiryIncludesSQLiteOnlyPreExecutionTurnsWithinTheExactScope() {
        BusinessIdentityScope otherScope = BusinessIdentityScope.scoped(
                "desktop", "desktop-session", "auth", 4, "user", "tenant", "platform");
        String createdThread = "thread-sqlite-created-" + UUID.randomUUID();
        String waitingThread = "thread-sqlite-waiting-" + UUID.randomUUID();
        String runningThread = "thread-sqlite-running-" + UUID.randomUUID();
        String otherThread = "thread-sqlite-other-" + UUID.randomUUID();
        String createdTurn = saveSQLiteOnlyTurn(createdThread, "CREATED", SCOPE);
        String waitingTurn = saveSQLiteOnlyTurn(waitingThread, "WAITING_APPROVAL", SCOPE);
        String runningTurn = saveSQLiteOnlyTurn(runningThread, "RUNNING", SCOPE);
        String otherTurn = saveSQLiteOnlyTurn(otherThread, "CREATED", otherScope);

        var affected = conversationService.expirePreExecutionTurns(SCOPE, "identity changed");

        assertThat(affected).containsExactlyInAnyOrder(createdThread, waitingThread);
        assertThat(status(createdTurn)).isEqualTo("EXPIRED");
        assertThat(status(waitingTurn)).isEqualTo("EXPIRED");
        assertThat(status(runningTurn)).isEqualTo("RUNNING");
        assertThat(status(otherTurn)).isEqualTo("CREATED");
    }

    @Test
    void conditionalExpiryDoesNotOverwriteATurnThatAlreadyBecameRunning() {
        String turnId = "turn-expiry-cas-" + UUID.randomUUID();
        conversations.createThread("thread-cas", "CAS", "C:/tmp", "provider", "model",
                "READ_ONLY", "NEVER", Instant.now(), SCOPE);
        turns.saveTurn(TurnRecord.started(
                turnId, "thread-cas", "CREATED", "input", "C:/tmp",
                "provider", "model", "READ_ONLY", "NEVER", Instant.now(), SCOPE));
        TurnEntity running = mapper.selectOne(Wrappers.<TurnEntity>lambdaQuery()
                .eq(TurnEntity::getTurnId, turnId));
        running.setStatus("RUNNING");
        mapper.updateById(running);

        boolean expired = turns.expirePreExecutionTurn(turnId, SCOPE, "identity changed");

        assertThat(expired).isFalse();
        assertThat(mapper.selectById(running.getId()).getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void conditionalStartRequiresExactScopeAndExpectedPreExecutionStatus() {
        String turnId = "turn-start-cas-" + UUID.randomUUID();
        String threadId = "thread-start-cas-" + UUID.randomUUID();
        conversations.createThread(threadId, "Start CAS", "C:/tmp", "provider", "model",
                "READ_ONLY", "NEVER", Instant.now(), SCOPE);
        turns.saveTurn(TurnRecord.started(
                turnId, threadId, "CREATED", "input", "C:/tmp",
                "provider", "model", "READ_ONLY", "NEVER", Instant.now(), SCOPE));

        assertThat(turns.transitionPreExecutionToRunning(turnId,
                BusinessIdentityScope.scoped(
                        "desktop", "desktop-session", "auth", 4,
                        "user", "tenant", "platform"), "CREATED")).isFalse();
        assertThat(turns.transitionPreExecutionToRunning(turnId, SCOPE, "WAITING_APPROVAL")).isFalse();
        assertThat(turns.transitionPreExecutionToRunning(turnId, SCOPE, "CREATED")).isTrue();
        assertThat(turns.transitionPreExecutionToRunning(turnId, SCOPE, "CREATED")).isFalse();
        assertThat(mapper.selectOne(Wrappers.<TurnEntity>lambdaQuery()
                .eq(TurnEntity::getTurnId, turnId)).getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void conditionalSubmissionFailureOnlyTerminatesTheExactRunningTurn() {
        String turnId = "turn-fail-cas-" + UUID.randomUUID();
        String threadId = "thread-fail-cas-" + UUID.randomUUID();
        conversations.createThread(threadId, "Failure CAS", "C:/tmp", "provider", "model",
                "READ_ONLY", "NEVER", Instant.now(), SCOPE);
        turns.saveTurn(TurnRecord.started(
                turnId, threadId, "CREATED", "input", "C:/tmp",
                "provider", "model", "READ_ONLY", "NEVER", Instant.now(), SCOPE));

        assertThat(turns.failRunningTurn(turnId, SCOPE, "resume_submission_failed")).isFalse();
        assertThat(turns.transitionPreExecutionToRunning(turnId, SCOPE, "CREATED")).isTrue();
        assertThat(turns.failRunningTurn(turnId,
                BusinessIdentityScope.scoped(
                        "desktop", "desktop-session", "auth", 4,
                        "user", "tenant", "platform"), "resume_submission_failed")).isFalse();
        assertThat(turns.failRunningTurn(turnId, SCOPE, "resume_submission_failed")).isTrue();
        assertThat(turns.failRunningTurn(turnId, SCOPE, "resume_submission_failed")).isFalse();

        TurnEntity failed = mapper.selectOne(Wrappers.<TurnEntity>lambdaQuery()
                .eq(TurnEntity::getTurnId, turnId));
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getFailureReason()).isEqualTo("resume_submission_failed");
        assertThat(failed.getCompletedAt()).isNotBlank();
    }

    @Test
    void conditionalCompletionOnlyCompletesTheExactRunningTurn() {
        String turnId = "turn-complete-cas-" + UUID.randomUUID();
        String threadId = "thread-complete-cas-" + UUID.randomUUID();
        conversations.createThread(threadId, "Completion CAS", "C:/tmp", "provider", "model",
                "READ_ONLY", "NEVER", Instant.now(), SCOPE);
        turns.saveTurn(TurnRecord.started(
                turnId, threadId, "CREATED", "input", "C:/tmp",
                "provider", "model", "READ_ONLY", "NEVER", Instant.now(), SCOPE));

        assertThat(turns.completeRunningTurn(turnId, SCOPE)).isFalse();
        assertThat(turns.transitionPreExecutionToRunning(turnId, SCOPE, "CREATED")).isTrue();
        assertThat(turns.completeRunningTurn(turnId,
                BusinessIdentityScope.scoped(
                        "desktop", "desktop-session", "auth", 4,
                        "user", "tenant", "platform"))).isFalse();
        assertThat(turns.completeRunningTurn(turnId, SCOPE)).isTrue();
        assertThat(turns.completeRunningTurn(turnId, SCOPE)).isFalse();

        TurnEntity completed = mapper.selectOne(Wrappers.<TurnEntity>lambdaQuery()
                .eq(TurnEntity::getTurnId, turnId));
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getCompletedAt()).isNotBlank();
    }

    private String saveSQLiteOnlyTurn(String threadId, String status, BusinessIdentityScope scope) {
        String turnId = "turn-sqlite-only-" + UUID.randomUUID();
        conversations.createThread(threadId, "SQLite only", "C:/tmp", "provider", "model",
                "READ_ONLY", "NEVER", Instant.now(), scope);
        turns.saveTurn(TurnRecord.started(
                turnId, threadId, status, "input", "C:/tmp",
                "provider", "model", "READ_ONLY", "NEVER", Instant.now(), scope));
        return turnId;
    }

    private String status(String turnId) {
        return mapper.selectOne(Wrappers.<TurnEntity>lambdaQuery()
                .eq(TurnEntity::getTurnId, turnId)).getStatus();
    }
}
