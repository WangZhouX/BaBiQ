package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Issues short-lived, connection-bound capabilities for ordinary WebSocket reconnect. */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessOaAttachHandleRegistry {
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OaSessionRepository repository;
    private final Clock clock;
    private final Duration ttl;
    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<Target, String> claimedTargets = new HashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public BusinessOaAttachHandleRegistry(OaSessionRepository repository) {
        this(repository, Clock.systemUTC(), DEFAULT_TTL);
    }

    BusinessOaAttachHandleRegistry(OaSessionRepository repository, Clock clock, Duration ttl) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("attach handle ttl must be positive");
        }
    }

    public synchronized String issue(TrustedDesktopConnection connection, OaSessionRecord observed) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(observed, "observed");
        if (observed.phase() != OaSessionPhase.DETACHED
                || !observed.desktopInstanceId().equals(connection.desktopInstanceId())
                || !observed.desktopSessionId().equals(connection.desktopSessionId())) {
            throw stale();
        }
        OaSessionRecord current = repository.findByAuthSessionId(observed.authSessionId()).orElseThrow(
                BusinessOaAttachHandleRegistry::stale);
        if (current.phase() != OaSessionPhase.DETACHED
                || current.generation() != observed.generation()
                || !sameTarget(current, observed)) {
            throw stale();
        }
        Instant now = clock.instant();
        cleanup(now);
        for (Map.Entry<String, Entry> existing : entries.entrySet()) {
            Entry entry = existing.getValue();
            if (entry.status() == Status.ISSUED
                    && entry.matches(connection)
                    && entry.observedGeneration() == observed.generation()
                    && entry.matchesTarget(observed)) {
                return existing.getKey();
            }
        }
        String handle;
        do {
            byte[] entropy = new byte[32];
            RANDOM.nextBytes(entropy);
            handle = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        } while (entries.containsKey(handle));
        entries.put(handle, new Entry(
                connection.reservationId(), connection.webSocketSessionId(),
                connection.desktopInstanceId(), connection.desktopSessionId(),
                observed.authSessionId(), observed.generation(), now.plus(ttl),
                Status.ISSUED, -1L));
        return handle;
    }

    public synchronized AttachClaim claim(String handle, TrustedDesktopConnection connection) {
        if (handle == null || handle.isBlank() || connection == null) {
            throw notAttachable();
        }
        Instant now = clock.instant();
        cleanup(now);
        Entry entry = entries.get(handle);
        if (entry == null || !entry.matches(connection)) {
            throw notAttachable();
        }
        if (entry.status() == Status.SUCCEEDED) {
            OaSessionRecord ready = repository.findByAuthSessionId(entry.authSessionId())
                    .orElseThrow(BusinessOaAttachHandleRegistry::stale);
            if (ready.phase() != OaSessionPhase.READY
                    || ready.generation() != entry.readyGeneration()
                    || !entry.matchesTarget(ready)) {
                throw stale();
            }
            return new AttachClaim(entry.authSessionId(), entry.observedGeneration(),
                    ClaimDisposition.READY, entry.readyGeneration(), entry.expiresAt());
        }
        if (entry.status() == Status.CLAIMED) {
            OaSessionRecord current = repository.findByAuthSessionId(entry.authSessionId())
                    .orElseThrow(BusinessOaAttachHandleRegistry::stale);
            if (!entry.matchesTarget(current)) {
                throw stale();
            }
            if ((current.phase() == OaSessionPhase.RESTORING
                    || current.phase() == OaSessionPhase.INSTALLING)
                    && current.generation() == entry.observedGeneration() + 1) {
                return new AttachClaim(entry.authSessionId(), entry.observedGeneration(),
                        ClaimDisposition.IN_FLIGHT, -1L, entry.expiresAt());
            }
            if (current.phase() == OaSessionPhase.READY
                    && current.generation() == entry.observedGeneration() + 2) {
                return new AttachClaim(entry.authSessionId(), entry.observedGeneration(),
                        ClaimDisposition.READY, current.generation(), entry.expiresAt());
            }
            if (current.phase() == OaSessionPhase.DETACHED
                    && current.generation() == entry.observedGeneration()) {
                throw notAttachable();
            }
            throw stale();
        }
        if (entry.status() != Status.ISSUED) {
            throw notAttachable();
        }
        OaSessionRecord current = repository.findByAuthSessionId(entry.authSessionId())
                .orElseThrow(BusinessOaAttachHandleRegistry::stale);
        if (current.phase() != OaSessionPhase.DETACHED
                || current.generation() != entry.observedGeneration()
                || !entry.matchesTarget(current)) {
            throw stale();
        }
        Target target = new Target(entry.authSessionId(), entry.observedGeneration());
        String winner = claimedTargets.get(target);
        if (winner != null && !winner.equals(handle)) {
            throw notAttachable();
        }
        claimedTargets.put(target, handle);
        entries.put(handle, entry.withStatus(Status.CLAIMED, -1L));
        return new AttachClaim(entry.authSessionId(), entry.observedGeneration(),
                ClaimDisposition.START_RESTORE, -1L, entry.expiresAt());
    }

    /** Revalidates the exact claimed connection after remote IO and before local installation. */
    public synchronized void validateClaim(String handle, TrustedDesktopConnection connection) {
        cleanup(clock.instant());
        Entry entry = entries.get(handle);
        if (entry == null || !entry.matches(connection) || entry.status() != Status.CLAIMED) {
            throw stale();
        }
    }

    public synchronized void complete(String handle, TrustedDesktopConnection connection, long readyGeneration) {
        cleanup(clock.instant());
        Entry entry = requiredClaim(handle, connection);
        OaSessionRecord ready = repository.findByAuthSessionId(entry.authSessionId())
                .orElseThrow(BusinessOaAttachHandleRegistry::stale);
        if (ready.phase() != OaSessionPhase.READY
                || ready.generation() != readyGeneration
                || !entry.matchesTarget(ready)) {
            throw stale();
        }
        entries.put(handle, entry.withStatus(Status.SUCCEEDED, readyGeneration));
    }

    public synchronized boolean fail(String handle, TrustedDesktopConnection connection) {
        cleanup(clock.instant());
        Entry entry = entries.get(handle);
        if (entry != null && entry.matches(connection) && entry.status() == Status.CLAIMED) {
            remove(handle, entry);
            return true;
        }
        return false;
    }

    /** Invalidates capabilities issued to one concrete finalized WebSocket connection. */
    public synchronized void revoke(TrustedDesktopConnection connection) {
        if (connection == null) return;
        cleanup(clock.instant());
        java.util.Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Entry> candidate = iterator.next();
            if (candidate.getValue().matches(connection)) {
                releaseTarget(candidate.getKey(), candidate.getValue());
                iterator.remove();
            }
        }
    }

    private void cleanup(Instant now) {
        java.util.Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Entry> candidate = iterator.next();
            Entry entry = candidate.getValue();
            if (!now.isBefore(entry.expiresAt())) {
                releaseTarget(candidate.getKey(), entry);
                iterator.remove();
            }
        }
    }

    private void remove(String handle, Entry entry) {
        releaseTarget(handle, entry);
        entries.remove(handle);
    }

    private void releaseTarget(String handle, Entry entry) {
        claimedTargets.remove(new Target(entry.authSessionId(), entry.observedGeneration()), handle);
    }

    private Entry requiredClaim(String handle, TrustedDesktopConnection connection) {
        Entry entry = entries.get(handle);
        if (entry == null || !entry.matches(connection) || entry.status() != Status.CLAIMED) {
            throw notAttachable();
        }
        return entry;
    }

    private static boolean sameTarget(OaSessionRecord left, OaSessionRecord right) {
        return left.authSessionId().equals(right.authSessionId())
                && left.desktopInstanceId().equals(right.desktopInstanceId())
                && left.desktopSessionId().equals(right.desktopSessionId());
    }

    private static IllegalStateException stale() {
        return new IllegalStateException("BUSINESS_SESSION_STALE");
    }

    private static IllegalStateException notAttachable() {
        return new IllegalStateException("BUSINESS_SESSION_NOT_ATTACHABLE");
    }

    public record AttachClaim(String authSessionId, long observedGeneration,
                              ClaimDisposition disposition, long readyGeneration, Instant expiresAt) {
        public boolean alreadyAttached() {
            return disposition == ClaimDisposition.READY;
        }

        public boolean inFlight() {
            return disposition == ClaimDisposition.IN_FLIGHT;
        }

        public boolean startsRestore() {
            return disposition == ClaimDisposition.START_RESTORE;
        }

        @Override
        public String toString() {
            return "AttachClaim[authSessionId=[REDACTED], observedGeneration=" + observedGeneration
                    + ", disposition=" + disposition + ", readyGeneration=" + readyGeneration
                    + ", expiresAt=" + expiresAt + "]";
        }
    }

    public enum ClaimDisposition { START_RESTORE, IN_FLIGHT, READY }

    private record Target(String authSessionId, long generation) { }

    private record Entry(String reservationId, String webSocketSessionId,
                         String desktopInstanceId, String desktopSessionId,
                         String authSessionId, long observedGeneration, Instant expiresAt,
                         Status status, long readyGeneration) {
        boolean matches(TrustedDesktopConnection connection) {
            return reservationId.equals(connection.reservationId())
                    && webSocketSessionId.equals(connection.webSocketSessionId())
                    && desktopInstanceId.equals(connection.desktopInstanceId())
                    && desktopSessionId.equals(connection.desktopSessionId());
        }

        boolean matchesTarget(OaSessionRecord record) {
            return authSessionId.equals(record.authSessionId())
                    && desktopInstanceId.equals(record.desktopInstanceId())
                    && desktopSessionId.equals(record.desktopSessionId());
        }

        Entry withStatus(Status nextStatus, long nextReadyGeneration) {
            return withStatus(nextStatus, nextReadyGeneration, expiresAt);
        }

        Entry withStatus(Status nextStatus, long nextReadyGeneration, Instant nextExpiresAt) {
            return new Entry(reservationId, webSocketSessionId, desktopInstanceId, desktopSessionId,
                    authSessionId, observedGeneration, nextExpiresAt, nextStatus, nextReadyGeneration);
        }

        @Override
        public String toString() {
            return "Entry[connection=[REDACTED], target=[REDACTED], observedGeneration="
                    + observedGeneration + ", expiresAt=" + expiresAt + ", status=" + status + "]";
        }
    }

    private enum Status { ISSUED, CLAIMED, SUCCEEDED }
}
