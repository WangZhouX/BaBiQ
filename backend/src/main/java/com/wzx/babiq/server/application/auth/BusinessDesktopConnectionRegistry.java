package com.wzx.babiq.server.application.auth;

import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/** 管理业务桌面握手预留与已建立 WebSocket 的一对一绑定。 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessDesktopConnectionRegistry {
    private static final Logger log = LoggerFactory.getLogger(BusinessDesktopConnectionRegistry.class);

    private final Map<String, ConnectionSlot> slotsByDesktopSessionId = new HashMap<>();
    private final Clock clock;
    private final Duration pendingTtl;
    private final CopyOnWriteArrayList<ConnectionCloseListener> closeListeners = new CopyOnWriteArrayList<>();

    @Autowired
    public BusinessDesktopConnectionRegistry(BusinessDesktopModeProperties properties) {
        this(Clock.systemUTC(), properties.acceptTimeout());
    }

    BusinessDesktopConnectionRegistry() {
        this(Clock.systemUTC(), Duration.ofSeconds(10));
    }

    BusinessDesktopConnectionRegistry(Clock clock, Duration pendingTtl) {
        this.clock = clock;
        this.pendingTtl = pendingTtl;
    }

    /** coordinator 构造完成后显式注册，避免 Registry 构造期反向解析生命周期 bean。 */
    public synchronized void addCloseListener(ConnectionCloseListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        closeListeners.add(listener);
    }

    public synchronized String reserve(String desktopInstanceId, String desktopSessionId) {
        requireText(desktopInstanceId, "desktopInstanceId");
        requireText(desktopSessionId, "desktopSessionId");
        removeExpiredPending();
        if (slotsByDesktopSessionId.containsKey(desktopSessionId)) {
            throw new IllegalStateException("business desktop session is already active");
        }
        String reservationId = UUID.randomUUID().toString();
        slotsByDesktopSessionId.put(
                desktopSessionId,
                new ConnectionSlot(reservationId, desktopInstanceId, desktopSessionId, clock.instant(), null));
        return reservationId;
    }

    public synchronized TrustedDesktopConnection finalizeReservation(
            String reservationId,
            String desktopInstanceId,
            String desktopSessionId,
            String webSocketSessionId) {
        removeExpiredPending();
        ConnectionSlot slot = slotsByDesktopSessionId.get(desktopSessionId);
        if (slot == null
                || slot.connection() != null
                || !slot.reservationId().equals(reservationId)
                || !slot.desktopInstanceId().equals(desktopInstanceId)) {
            throw new IllegalStateException("business desktop reservation does not match the connection");
        }
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                reservationId, desktopInstanceId, desktopSessionId, webSocketSessionId);
        slotsByDesktopSessionId.put(
                desktopSessionId,
                new ConnectionSlot(reservationId, desktopInstanceId, desktopSessionId, slot.createdAt(), connection));
        return connection;
    }

    public synchronized boolean cancelReservation(String reservationId) {
        removeExpiredPending();
        Optional<Map.Entry<String, ConnectionSlot>> pending = slotsByDesktopSessionId.entrySet().stream()
                .filter(entry -> entry.getValue().connection() == null)
                .filter(entry -> entry.getValue().reservationId().equals(reservationId))
                .findFirst();
        pending.ifPresent(entry -> slotsByDesktopSessionId.remove(entry.getKey(), entry.getValue()));
        return pending.isPresent();
    }

    /**
     * 当握手属性丢失 reservationId 时，按完整桌面身份取消仍未绑定的预留。
     *
     * <p>必须同时匹配 instance/session，且槽位仍是 pending，避免按 sessionId 单独误删
     * 已建立连接或身份漂移后的并发预留。</p>
     */
    public synchronized boolean cancelPending(String desktopInstanceId, String desktopSessionId) {
        removeExpiredPending();
        if (desktopInstanceId == null || desktopInstanceId.isBlank()
                || desktopSessionId == null || desktopSessionId.isBlank()) {
            return false;
        }
        ConnectionSlot pending = slotsByDesktopSessionId.get(desktopSessionId);
        if (pending == null
                || pending.connection() != null
                || !pending.desktopInstanceId().equals(desktopInstanceId)) {
            return false;
        }
        return slotsByDesktopSessionId.remove(desktopSessionId, pending);
    }

    public boolean release(String reservationId, String webSocketSessionId) {
        TrustedDesktopConnection released;
        synchronized (this) {
            removeExpiredPending();
            Optional<Map.Entry<String, ConnectionSlot>> active = slotsByDesktopSessionId.entrySet().stream()
                    .filter(entry -> entry.getValue().connection() != null)
                    .filter(entry -> entry.getValue().reservationId().equals(reservationId))
                    .filter(entry -> entry.getValue().connection().webSocketSessionId().equals(webSocketSessionId))
                    .findFirst();
            if (active.isEmpty()) {
                return false;
            }
            Map.Entry<String, ConnectionSlot> entry = active.orElseThrow();
            if (!slotsByDesktopSessionId.remove(entry.getKey(), entry.getValue())) {
                return false;
            }
            released = entry.getValue().connection();
        }
        for (ConnectionCloseListener listener : List.copyOf(closeListeners)) {
            try {
                listener.onConnectionClosed(released, "business desktop WebSocket closed");
            } catch (RuntimeException failure) {
                log.warn("Business desktop close listener failed: listenerType={}, reasonType={}",
                        listener.getClass().getName(), failure.getClass().getSimpleName());
            }
        }
        return true;
    }

    public synchronized Optional<TrustedDesktopConnection> findByDesktopSessionId(String desktopSessionId) {
        removeExpiredPending();
        ConnectionSlot slot = slotsByDesktopSessionId.get(desktopSessionId);
        return slot == null ? Optional.empty() : Optional.ofNullable(slot.connection());
    }

    /** Returns the finalized connection bound to the concrete WebSocket session id. */
    public synchronized Optional<TrustedDesktopConnection> findByWebSocketSessionId(String webSocketSessionId) {
        removeExpiredPending();
        if (webSocketSessionId == null || webSocketSessionId.isBlank()) {
            return Optional.empty();
        }
        return slotsByDesktopSessionId.values().stream()
                .map(ConnectionSlot::connection)
                .filter(java.util.Objects::nonNull)
                .filter(connection -> connection.webSocketSessionId().equals(webSocketSessionId))
                .findFirst();
    }

    public synchronized boolean isFinalized(String webSocketSessionId) {
        return findByWebSocketSessionId(webSocketSessionId).isPresent();
    }

    /** Verifies that the complete reservation/WebSocket/instance/session tuple is still finalized. */
    public boolean isFinalized(TrustedDesktopConnection connection) {
        synchronized (this) {
            removeExpiredPending();
            return matchesFinalized(connection);
        }
    }

    /**
     * Runs one bounded local publication while the exact finalized tuple cannot be released.
     *
     * <p>The operation must not perform network or database IO and must not re-enter this
     * registry. Connection close listeners remain outside this registry's monitor.</p>
     */
    public <T> T withFinalized(TrustedDesktopConnection connection, Supplier<T> operation) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(operation, "operation");
        synchronized (this) {
            removeExpiredPending();
            if (!matchesFinalized(connection)) {
                throw new IllegalStateException("BUSINESS_SESSION_STALE");
            }
            return operation.get();
        }
    }

    private boolean matchesFinalized(TrustedDesktopConnection connection) {
        if (connection == null) {
            return false;
        }
        ConnectionSlot slot = slotsByDesktopSessionId.get(connection.desktopSessionId());
        return slot != null
                && slot.connection() != null
                && slot.reservationId().equals(connection.reservationId())
                && slot.desktopInstanceId().equals(connection.desktopInstanceId())
                && slot.desktopSessionId().equals(connection.desktopSessionId())
                && slot.connection().equals(connection);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private void removeExpiredPending() {
        Instant expiryBoundary = clock.instant().minus(pendingTtl);
        slotsByDesktopSessionId.entrySet().removeIf(entry -> {
            ConnectionSlot slot = entry.getValue();
            return slot.connection() == null && !slot.createdAt().isAfter(expiryBoundary);
        });
    }

    private record ConnectionSlot(
            String reservationId,
            String desktopInstanceId,
            String desktopSessionId,
            Instant createdAt,
            TrustedDesktopConnection connection
    ) {
    }

    @FunctionalInterface
    public interface ConnectionCloseListener {
        void onConnectionClosed(TrustedDesktopConnection connection, String reason);
    }
}
