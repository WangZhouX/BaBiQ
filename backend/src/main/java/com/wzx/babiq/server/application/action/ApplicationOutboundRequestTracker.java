package com.wzx.babiq.server.application.action;

import com.wzx.babiq.server.api.JsonRpcMessage;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 关联服务端主动 JSON-RPC request 与桌面端返回的 response/error response。
 *
 * <p>pending 表仅保存 request id、future 和 timeout 句柄，不保存动作 payload，
 * 从而避免 correlation 生命周期和日志意外保留敏感业务输入。</p>
 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class ApplicationOutboundRequestTracker implements AutoCloseable {

    private final ScheduledExecutorService scheduler;
    private final boolean ownedScheduler;
    private final Object lifecycleLock = new Object();
    private static final String LEGACY_CONNECTION = "<legacy>";
    private final Map<RequestKey, PendingRequest> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /** Spring 运行时使用守护线程调度 timeout，不阻止后端进程退出。 */
    public ApplicationOutboundRequestTracker() {
        this(Executors.newSingleThreadScheduledExecutor(daemonThreadFactory()), true);
    }

    /** 测试可注入可控 scheduler。 */
    public ApplicationOutboundRequestTracker(ScheduledExecutorService scheduler) {
        this(scheduler, false);
    }

    private ApplicationOutboundRequestTracker(ScheduledExecutorService scheduler, boolean ownedScheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownedScheduler = ownedScheduler;
    }

    /** 注册一个唯一 request id，并在 timeout 到期时异常完成和移除。 */
    public CompletableFuture<JsonRpcMessage> register(long requestId, Duration timeout) {
        return register(LEGACY_CONNECTION, requestId, timeout);
    }

    public CompletableFuture<JsonRpcMessage> register(String connectionId, long requestId, Duration timeout) {
        requireConnectionId(connectionId);
        if (requestId <= 0) {
            throw new IllegalArgumentException("requestId must be positive");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        CompletableFuture<JsonRpcMessage> future = new CompletableFuture<>();
        PendingRequest request = new PendingRequest(future);
        RequestKey key = new RequestKey(connectionId, requestId);
        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("Application outbound request tracker is closed");
            }
            if (pending.putIfAbsent(key, request) != null) {
                throw new IllegalStateException("Outbound request id is already pending");
            }
            try {
                ScheduledFuture<?> timeoutTask = scheduler.schedule(
                        () -> timeout(key, request), timeout.toNanos(), TimeUnit.NANOSECONDS);
                request.timeoutTask(timeoutTask);
                return future;
            } catch (RuntimeException exception) {
                pending.remove(key, request);
                future.completeExceptionally(exception);
                throw exception;
            }
        }
    }

    @Override
    @PreDestroy
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            closePending(new IOException("application outbound request tracker closed"));
            if (ownedScheduler) {
                scheduler.shutdownNow();
            }
        }
    }

    /** 用成功响应完成匹配 request；duplicate/late response 返回 false。 */
    public boolean complete(JsonRpcMessage.Response response) {
        return complete(LEGACY_CONNECTION, response);
    }

    public boolean complete(String connectionId, JsonRpcMessage.Response response) {
        return response != null && isValidCorrelation(response.jsonrpc(), response.id())
                && completeWithLegacyFallback(connectionId, response.id(), response);
    }

    /** 用错误响应完成匹配 request；无法关联的 null id 返回 false。 */
    public boolean complete(JsonRpcMessage.ErrorResponse response) {
        return complete(LEGACY_CONNECTION, response);
    }

    public boolean complete(String connectionId, JsonRpcMessage.ErrorResponse response) {
        return response != null && isValidCorrelation(response.jsonrpc(), response.id())
                && completeWithLegacyFallback(connectionId, response.id(), response);
    }

    /** 发送失败等本地故障原子移除 correlation，并异常完成 future。 */
    public boolean fail(long requestId, Throwable failure) {
        return fail(LEGACY_CONNECTION, requestId, failure);
    }

    public boolean fail(String connectionId, long requestId, Throwable failure) {
        requireConnectionId(connectionId);
        if (requestId <= 0 || failure == null) {
            return false;
        }
        PendingRequest request = pending.remove(new RequestKey(connectionId, requestId));
        if (request == null) {
            return false;
        }
        request.cancelTimeout();
        return request.future().completeExceptionally(failure);
    }

    /** 连接关闭时异常完成并移除全部 pending correlation。 */
    public void closePending(Throwable reason) {
        Throwable failure = reason == null ? new IOException("business desktop connection closed") : reason;
        pending.forEach((key, request) -> removeAndFail(key, request, failure));
    }

    public void closePending(String connectionId, Throwable reason) {
        requireConnectionId(connectionId);
        Throwable failure = reason == null ? new IOException("business desktop connection closed") : reason;
        pending.forEach((key, request) -> {
            if (key.connectionId().equals(connectionId)) {
                removeAndFail(key, request, failure);
            }
        });
    }

    /** 仅用于观测和测试 pending 数量，不暴露 payload 或 future 明细。 */
    public int pendingCount() {
        return pending.size();
    }

    private boolean completeWithLegacyFallback(String connectionId, long requestId, JsonRpcMessage response) {
        requireConnectionId(connectionId);
        return complete(new RequestKey(connectionId, requestId), response);
    }

    private boolean complete(RequestKey key, JsonRpcMessage response) {
        PendingRequest request = pending.remove(key);
        if (request == null) {
            return false;
        }
        request.cancelTimeout();
        return request.future().complete(response);
    }

    private static boolean isValidCorrelation(String jsonrpc, Long requestId) {
        return "2.0".equals(jsonrpc) && requestId != null && requestId > 0;
    }

    private void timeout(RequestKey key, PendingRequest request) {
        if (pending.remove(key, request)) {
            request.future().completeExceptionally(
                    new TimeoutException("Outbound application request timed out"));
        }
    }

    private void removeAndFail(RequestKey key, PendingRequest request, Throwable failure) {
        if (pending.remove(key, request)) {
            request.cancelTimeout();
            request.future().completeExceptionally(failure);
        }
    }

    private static void requireConnectionId(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("connectionId must not be blank");
        }
    }

    private record RequestKey(String connectionId, long requestId) {
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "application-outbound-timeout");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class PendingRequest {
        private final CompletableFuture<JsonRpcMessage> future;
        private volatile ScheduledFuture<?> timeoutTask;

        private PendingRequest(CompletableFuture<JsonRpcMessage> future) {
            this.future = future;
        }

        private CompletableFuture<JsonRpcMessage> future() {
            return future;
        }

        private void timeoutTask(ScheduledFuture<?> timeoutTask) {
            this.timeoutTask = timeoutTask;
            if (future.isDone()) {
                timeoutTask.cancel(false);
            }
        }

        private void cancelTimeout() {
            ScheduledFuture<?> task = timeoutTask;
            if (task != null) {
                task.cancel(false);
            }
        }
    }
}
