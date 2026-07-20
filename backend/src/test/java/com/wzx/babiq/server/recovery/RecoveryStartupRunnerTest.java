package com.wzx.babiq.server.recovery;

import com.wzx.babiq.server.application.action.ApplicationActionRecoveryService;
import com.wzx.babiq.server.attachment.ClipboardAttachmentRetentionService;
import com.wzx.babiq.server.context.compaction.ContextCompactionRecoveryService;
import com.wzx.babiq.server.workunit.WorkUnitService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class RecoveryStartupRunnerTest {

    @Test
    void startupRunsClipboardCleanupAfterDatabaseRecoveryBeforeOpeningRecoveryGate() {
        TurnRecoveryService recovery = mock(TurnRecoveryService.class);
        ClipboardAttachmentRetentionService retention =
                mock(ClipboardAttachmentRetentionService.class);
        StartupRecoveryCoordinator coordinator = mock(StartupRecoveryCoordinator.class);
        ObjectProvider<ClipboardAttachmentRetentionService> retentionProvider =
                providerThatInvokes(retention);

        RecoveryStartupRunner runner = new RecoveryStartupRunner(
                recovery,
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                retentionProvider,
                coordinator);

        runner.run(null);

        InOrder ordered = inOrder(recovery, retention, coordinator);
        ordered.verify(recovery).recoverAbandonedState();
        ordered.verify(retention).cleanup();
        ordered.verify(coordinator).markRecoveryComplete();
    }

    @Test
    void cleanupMethodUsesConfiguredSixHourFixedDelay() throws Exception {
        Method cleanup = ClipboardAttachmentRetentionService.class.getMethod("scheduledCleanup");

        Scheduled scheduled = cleanup.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${babiq.business.attachment-cleanup-interval-millis:21600000}");
        assertThat(scheduled.initialDelayString())
                .isEqualTo("${babiq.business.attachment-cleanup-interval-millis:21600000}");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return mock(ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerThatInvokes(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        doAnswer(invocation -> {
            Consumer<T> consumer = invocation.getArgument(0);
            consumer.accept(value);
            return null;
        }).when(provider).ifAvailable(any());
        return provider;
    }
}
