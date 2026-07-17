package com.wzx.babiq.server.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.context.repository.ContextSnapshotRecord;
import com.wzx.babiq.server.context.repository.ContextSnapshotRepository;
import com.wzx.babiq.server.context.repository.ContextCompactionRepository;
import com.wzx.babiq.server.context.repository.ContextWindowRecord;
import com.wzx.babiq.server.context.repository.ContextWindowRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextStatusServiceTest {

    private static final BusinessIdentityScope SCOPE = BusinessIdentityScope.scoped(
            "desktop", "session", "auth", 1, "user", "tenant", "platform");

    @Test
    void scopedStatusUsesOnlyExactScopedWindowAndSnapshotLookups() {
        ContextWindowRepository windows = mock(ContextWindowRepository.class);
        ContextSnapshotRepository snapshots = mock(ContextSnapshotRepository.class);
        Instant now = Instant.now();
        ContextWindowRecord window = new ContextWindowRecord(
                "thread", 1, null, 1000, 750, "snapshot", now, now, SCOPE);
        ContextSnapshotRecord snapshot = new ContextSnapshotRecord(
                "snapshot", "thread", "turn", "pre_model_call", "provider", "model", "cwd",
                1, 1000, 750, 250, null, 1, 0, "{}", "[]", "{}", null, 0,
                "preview", now, SCOPE);
        when(windows.findByThreadId("thread", SCOPE)).thenReturn(Optional.of(window));
        when(snapshots.findBySnapshotId("snapshot", SCOPE)).thenReturn(Optional.of(snapshot));
        ContextStatusService service = new ContextStatusService(windows, snapshots, new ObjectMapper());

        var result = service.status("thread", SCOPE);

        assertThat(result.lastSnapshotId()).isEqualTo("snapshot");
        assertThat(result.lastEstimatedTokens()).isEqualTo(250);
        verify(windows).findByThreadId("thread", SCOPE);
        verify(snapshots).findBySnapshotId("snapshot", SCOPE);
        verify(windows, never()).findByThreadId("thread");
        verify(snapshots, never()).findBySnapshotId("snapshot");
        verify(snapshots, never()).findLatestByThreadId("thread");
    }

    @Test
    void scopedStatusDoesNotReadUnscopedCompactionAudit() {
        ContextWindowRepository windows = mock(ContextWindowRepository.class);
        ContextSnapshotRepository snapshots = mock(ContextSnapshotRepository.class);
        ContextCompactionRepository compactions = mock(ContextCompactionRepository.class);
        Instant now = Instant.now();
        when(windows.findByThreadId("thread", SCOPE)).thenReturn(Optional.of(new ContextWindowRecord(
                "thread", 1, null, 1000, 750, null, now, now, SCOPE)));
        ContextStatusService service = new ContextStatusService(
                windows, snapshots, compactions, new ObjectMapper());

        var result = service.status("thread", SCOPE);

        assertThat(result.compactionCount()).isZero();
        assertThat(result.lastCompactionStatus()).isNull();
        verify(compactions, never()).countByThreadId("thread");
        verify(compactions, never()).findLatestByThreadId("thread");
    }
}
