package com.wzx.babiq.server.agent;

import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.attachment.AttachmentMetadata;
import com.wzx.babiq.server.attachment.AttachmentReservationRegistry;
import com.wzx.babiq.server.attachment.AttachmentSource;
import com.wzx.babiq.server.attachment.PreparedAttachment;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 TurnExecutor 对自有和外部线程池采用不同的关闭责任。 */
class TurnExecutorLifecycleTest {

    @Test
    void defaultExecutorIsOwnedAndClosedWithTheComponent() throws Exception {
        TurnExecutor executor = new TurnExecutor(mock(AgentLoop.class));
        ExecutorService owned = executorField(executor);

        executor.close();

        assertThat(owned.isShutdown()).isTrue();
    }

    @Test
    void closeCancelsRunningTasksButDoesNotShutdownExternalExecutor() {
        AgentLoop loop = mock(AgentLoop.class);
        ExecutorService external = mock(ExecutorService.class);
        Future<?> future = mock(Future.class);
        when(external.submit(any(Runnable.class))).thenAnswer(ignored -> future);
        TurnExecutor executor = new TurnExecutor(loop, external);
        Turn turn = new Turn("turn-a", "thread-a");

        executor.submit(turn, "input", null, ".", mock(ItemEmitter.class));
        executor.close();

        verify(future).cancel(true);
        verify(external, never()).shutdownNow();
        assertThat(executor.interrupt(turn.id())).isFalse();
    }

    @Test
    void close_releases_attachment_reservation_for_a_queued_turn() {
        AgentLoop loop = mock(AgentLoop.class);
        ExecutorService external = mock(ExecutorService.class);
        Future<?> future = mock(Future.class);
        when(external.submit(any(Runnable.class))).thenAnswer(ignored -> future);
        AttachmentReservationRegistry registry = new AttachmentReservationRegistry();
        PreparedAttachment attachment = attachment();
        AttachmentReservationRegistry.Reservation reservation = registry.reserve(
                "thread-a", BusinessIdentityScope.UNSCOPED, List.of(attachment));
        reservation.bindToTurn("turn-a");
        TurnExecutor executor = new TurnExecutor(loop, external, registry);
        Turn turn = new Turn("turn-a", "thread-a");

        executor.submit(turn, "input", null, ".", mock(ItemEmitter.class));
        executor.close();

        try (AttachmentReservationRegistry.Reservation next = registry.reserve(
                "thread-a", BusinessIdentityScope.UNSCOPED, List.of(attachment))) {
            assertThat(next.active()).isTrue();
        }
    }

    private static ExecutorService executorField(TurnExecutor executor) throws Exception {
        Field field = TurnExecutor.class.getDeclaredField("executor");
        field.setAccessible(true);
        return (ExecutorService) field.get(executor);
    }

    private static PreparedAttachment attachment() {
        AttachmentMetadata metadata = new AttachmentMetadata(
                "00000000-0000-0000-0000-000000000001",
                "A-234562",
                "contract.pdf",
                "C:\\business\\contract.pdf",
                "application/pdf",
                42,
                "a".repeat(64),
                AttachmentSource.SELECTED_FILE);
        return new PreparedAttachment(
                metadata,
                Path.of(metadata.localPath()),
                new PreparedAttachment.FileIdentity(
                        metadata.sizeBytes(), FileTime.from(Instant.EPOCH), "file-key"));
    }
}
