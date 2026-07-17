package com.wzx.babiq.server.application.action;

import com.wzx.babiq.server.api.JsonRpcMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证服务端主动 JSON-RPC 请求的关联、超时和连接关闭清理。 */
class ApplicationOutboundRequestTrackerTest {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ApplicationOutboundRequestTracker tracker = new ApplicationOutboundRequestTracker(scheduler);

    @AfterEach
    void shutDownScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    void successCompletesExactlyOnceAndRemovesPendingEntry() {
        var future = tracker.register(1L, Duration.ofSeconds(1));
        JsonRpcMessage.Response response = JsonRpcMessage.Response.ok(1L, Map.of("accepted", true));

        assertThat(tracker.complete(response)).isTrue();

        assertThat(future.join()).isEqualTo(response);
        assertThat(tracker.pendingCount()).isZero();
        assertThat(tracker.complete(response)).isFalse();
    }

    @Test
    void errorCompletesCorrelationAndRejectsLateDuplicateResponse() {
        var future = tracker.register(2L, Duration.ofSeconds(1));
        JsonRpcMessage.ErrorResponse error = JsonRpcMessage.ErrorResponse.of(
                2L,
                com.wzx.babiq.server.api.error.JsonRpcErrorCode.INVALID_PARAMS,
                "Invalid application request",
                null);

        assertThat(tracker.complete(error)).isTrue();

        assertThat(future.join()).isEqualTo(error);
        assertThat(tracker.pendingCount()).isZero();
        assertThat(tracker.complete(JsonRpcMessage.Response.ok(2L, Map.of()))).isFalse();
    }

    @Test
    void timeoutRemovesPendingAndCompletesExceptionally() {
        var future = tracker.register(3L, Duration.ofMillis(20));

        assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(TimeoutException.class);
        assertThat(tracker.pendingCount()).isZero();
        assertThat(tracker.complete(JsonRpcMessage.Response.ok(3L, Map.of()))).isFalse();
    }

    @Test
    void connectionCloseFailsAllPendingAndClearsTracker() {
        var first = tracker.register(4L, Duration.ofSeconds(5));
        var second = tracker.register(5L, Duration.ofSeconds(5));

        tracker.closePending(new IOException("business desktop disconnected"));

        assertThatThrownBy(first::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IOException.class);
        assertThatThrownBy(second::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IOException.class);
        assertThat(tracker.pendingCount()).isZero();
    }

    @Test
    void explicitFailureCompletesExceptionallyExactlyOnceAndRemovesPendingEntry() {
        var future = tracker.register(8L, Duration.ofSeconds(1));
        IOException failure = new IOException("send failed");

        assertThat(tracker.fail(8L, failure)).isTrue();

        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCause(failure);
        assertThat(tracker.pendingCount()).isZero();
        assertThat(tracker.fail(8L, new IOException("late failure"))).isFalse();
    }

    @Test
    void duplicateRequestIdAndInvalidCorrelationAreRejected() {
        tracker.register(6L, Duration.ofSeconds(1));

        assertThatThrownBy(() -> tracker.register(6L, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tracker.register(7L, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tracker.complete(new JsonRpcMessage.ErrorResponse(
                "2.0", null, new JsonRpcMessage.ErrorResponse.Error(-32000, "missing id", null))))
                .isFalse();
        assertThat(tracker.complete(new JsonRpcMessage.Response("2.0", null, Map.of())))
                .isFalse();
        assertThat(tracker.complete(new JsonRpcMessage.Response("1.0", 6L, Map.of())))
                .isFalse();
        assertThat(tracker.complete(new JsonRpcMessage.Response("2.0", 0L, Map.of())))
                .isFalse();
    }

    @Test
    void requestIdsAndConnectionCloseAreScopedPerWebSocketSession() {
        var first = tracker.register("ws-a", 9L, Duration.ofSeconds(1));
        var second = tracker.register("ws-b", 9L, Duration.ofSeconds(1));

        assertThat(tracker.complete("ws-a", JsonRpcMessage.Response.ok(9L, Map.of("owner", "a"))))
                .isTrue();
        tracker.closePending("ws-a", new IOException("ws-a closed"));

        assertThat(first.join()).isEqualTo(JsonRpcMessage.Response.ok(9L, Map.of("owner", "a")));
        assertThat(second).isNotDone();
        assertThat(tracker.complete("ws-a", JsonRpcMessage.Response.ok(9L, Map.of()))).isFalse();
        assertThat(tracker.complete("ws-b", JsonRpcMessage.Response.ok(9L, Map.of("owner", "b"))))
                .isTrue();
        assertThat(second.join()).isEqualTo(JsonRpcMessage.Response.ok(9L, Map.of("owner", "b")));
    }

    @Test
    void scopedResponseNeverCompletesLegacyOrDifferentConnectionCorrelation() {
        var legacy = tracker.register(10L, Duration.ofSeconds(1));
        var other = tracker.register("ws-b", 10L, Duration.ofSeconds(1));

        assertThat(tracker.complete("ws-a", JsonRpcMessage.Response.ok(10L, Map.of())))
                .isFalse();
        tracker.closePending("ws-a", new IOException("ws-a closed"));

        assertThat(legacy).isNotDone();
        assertThat(other).isNotDone();
        assertThat(tracker.complete(JsonRpcMessage.Response.ok(10L, Map.of("legacy", true)))).isTrue();
        assertThat(tracker.complete("ws-b", JsonRpcMessage.Response.ok(10L, Map.of("other", true)))).isTrue();
    }

    @Test
    void closeShutsDownOnlyOwnedSchedulerAndFailsEveryPendingRequest() {
        CompletableFuture<JsonRpcMessage> ownedPending;
        ScheduledExecutorService ownedScheduler;
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context, "babiq.business.enabled=true");
            context.registerBean(ApplicationOutboundRequestTracker.class);
            context.refresh();
            ApplicationOutboundRequestTracker owned = context.getBean(ApplicationOutboundRequestTracker.class);
            ownedScheduler = (ScheduledExecutorService) java.util.Objects.requireNonNull(
                    org.springframework.test.util.ReflectionTestUtils.getField(owned, "scheduler"));
            ownedPending = owned.register(11L, Duration.ofSeconds(30));
        }

        assertThat(ownedScheduler.isShutdown()).isTrue();
        assertThatThrownBy(ownedPending::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IOException.class);

        ScheduledExecutorService externalScheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            ApplicationOutboundRequestTracker external = new ApplicationOutboundRequestTracker(externalScheduler);
            var externalPending = external.register(12L, Duration.ofSeconds(30));

            external.close();

            assertThat(externalScheduler.isShutdown()).isFalse();
            assertThatThrownBy(externalPending::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IOException.class);
            assertThat(external.pendingCount()).isZero();
        } finally {
            externalScheduler.shutdownNow();
        }
    }
}
