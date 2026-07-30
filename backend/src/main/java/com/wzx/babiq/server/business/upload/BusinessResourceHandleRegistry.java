package com.wzx.babiq.server.business.upload;

import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Short-lived server-owned handles for binary data that was already obtained from a trusted OA call.
 *
 * <p>The handle contains no URL, tenant id, session id, or OA identifier. Production metadata is durable
 * in SQLite and bytes live in an owner-only runtime file, so process recreation does not revive an
 * unbound or memory-only capability.</p>
 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessResourceHandleRegistry {
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    public static final int MAX_BYTES = 20_000_000;

    private static final Set<String> SAFE_MEDIA_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "application/pdf",
            "video/mp4", "video/x-msvideo", "video/quicktime", "video/x-matroska", "video/webm");

    private final Clock clock;
    private final Duration maximumTtl;
    private final BusinessAttachmentRepository repository;
    private final BusinessResourceBlobStore blobStore;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Set<RevokedGeneration> revokedGenerations = ConcurrentHashMap.newKeySet();

    public BusinessResourceHandleRegistry() {
        this(Clock.systemUTC(), DEFAULT_TTL);
    }

    public BusinessResourceHandleRegistry(Clock clock, Duration maximumTtl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maximumTtl = requirePositive(maximumTtl, "maximumTtl");
        this.repository = null;
        this.blobStore = null;
    }

    @Autowired
    public BusinessResourceHandleRegistry(BusinessAttachmentRepository repository,
                                          BusinessResourceBlobStore blobStore) {
        this(repository, blobStore, Clock.systemUTC(), DEFAULT_TTL);
    }

    BusinessResourceHandleRegistry(BusinessAttachmentRepository repository,
                                   BusinessResourceBlobStore blobStore,
                                   Clock clock,
                                   Duration maximumTtl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maximumTtl = requirePositive(maximumTtl, "maximumTtl");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.blobStore = Objects.requireNonNull(blobStore, "blobStore");
    }

    /** Registers bytes only after the caller has completed its authenticated OA response validation. */
    public synchronized ResourceDescriptor register(TrustedDesktopConnection connection,
                                                    ReadyOaSessionLease lease,
                                                    String mediaType,
                                                    byte[] bytes,
                                                    Duration ttl) {
        requireBinding(connection, lease);
        if (revokedGenerations.contains(RevokedGeneration.from(connection, lease))) {
            throw new IllegalStateException("resource lease generation is revoked");
        }
        String normalizedMediaType = normalizeMediaType(mediaType);
        if (bytes == null || bytes.length == 0 || bytes.length >= MAX_BYTES) {
            throw new IllegalArgumentException("resource bytes exceed the allowed limit");
        }
        Duration effectiveTtl = requirePositive(ttl, "ttl");
        if (effectiveTtl.compareTo(maximumTtl) > 0) effectiveTtl = maximumTtl;
        Instant now = clock.instant();
        Instant expiresAt = now.plus(effectiveTtl);
        String handle = newHandle();
        Entry entry = new Entry(handle, connection, lease, normalizedMediaType, bytes.clone(), now, expiresAt);
        if (repository != null) {
            String storageRef = blobStore.store(bytes);
            try {
                repository.insertResource(new BusinessResourceHandleRecord(handle,
                        connection.desktopInstanceId(), connection.desktopSessionId(), lease.authSessionId(),
                        lease.tenantId(), lease.generation(), normalizedMediaType, bytes.length,
                        storageRef, "SINGLE_SESSION", now, expiresAt, null));
            } catch (RuntimeException failure) {
                blobStore.delete(storageRef);
                throw failure;
            }
            return entry.descriptor();
        }
        entries.put(handle, entry);
        return entry.descriptor();
    }

    /** Resolves only a handle bound to the same finalized connection and READY generation. */
    public synchronized Optional<StoredResource> resolve(String handle,
                                                         TrustedDesktopConnection connection,
                                                         ReadyOaSessionLease lease) {
        if (handle == null || handle.isBlank() || connection == null || lease == null) return Optional.empty();
        if (repository != null) {
            BusinessResourceHandleRecord durable = repository.findResource(handle).orElse(null);
            if (!isReadable(durable, connection, lease, clock.instant())) {
                return Optional.empty();
            }
            try {
                byte[] bytes = blobStore.load(durable.storageRef(), durable.contentLength());
                BusinessResourceHandleRecord confirmed = repository.findResource(handle).orElse(null);
                if (!durable.equals(confirmed)
                        || !isReadable(confirmed, connection, lease, clock.instant())) {
                    return Optional.empty();
                }
                return Optional.of(new StoredResource(durable.mediaType(), bytes, durable.expiresAt()));
            } catch (RuntimeException unavailable) {
                return Optional.empty();
            }
        }
        Entry entry = entries.get(handle);
        if (entry == null) return Optional.empty();
        Instant now = clock.instant();
        if (!entry.expiresAt().isAfter(now)) {
            entries.remove(handle, entry);
            return Optional.empty();
        }
        if (!sameBinding(entry.connection(), connection) || !sameLease(entry.lease(), lease)) {
            return Optional.empty();
        }
        return Optional.of(entry.storedResource());
    }

    private static boolean isReadable(
            BusinessResourceHandleRecord durable,
            TrustedDesktopConnection connection,
            ReadyOaSessionLease lease,
            Instant now) {
        return durable != null
                && durable.revokedAt() == null
                && durable.expiresAt().isAfter(now)
                && durable.desktopInstanceId().equals(connection.desktopInstanceId())
                && durable.desktopSessionId().equals(connection.desktopSessionId())
                && durable.authSessionId().equals(lease.authSessionId())
                && durable.tenantId().equals(lease.tenantId())
                && durable.identityGeneration() == lease.generation();
    }

    /** Revokes all handles tied to an exact desktop session, auth session and generation. */
    public synchronized int revoke(
            String desktopInstanceId,
            String desktopSessionId,
            String authSessionId,
            long generation) {
        revokedGenerations.add(new RevokedGeneration(
                desktopInstanceId, desktopSessionId, authSessionId, generation));
        int durable = repository == null ? 0
                : repository.revokeResources(
                        desktopInstanceId, desktopSessionId, authSessionId, generation, clock.instant());
        if (repository != null) cleanupDurableResources();
        int removed = 0;
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            Entry entry = item.getValue();
            if (entry.connection().desktopInstanceId().equals(desktopInstanceId)
                    && entry.connection().desktopSessionId().equals(desktopSessionId)
                    && entry.lease().authSessionId().equals(authSessionId)
                    && entry.lease().generation() == generation
                    && entries.remove(item.getKey(), entry)) {
                removed++;
            }
        }
        return Math.max(removed, durable);
    }

    public int revoke(TrustedDesktopConnection connection, ReadyOaSessionLease lease) {
        requireBinding(connection, lease);
        return revoke(
                connection.desktopInstanceId(),
                connection.desktopSessionId(),
                lease.authSessionId(),
                lease.generation());
    }

    /** Removes expired handles and returns the number removed. */
    public synchronized int purgeExpired() {
        int durable = repository == null ? 0 : repository.purgeExpiredResources(clock.instant());
        int durableCleanup = repository == null ? 0 : cleanupDurableResources();
        Instant now = clock.instant();
        int removed = 0;
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            if (!item.getValue().expiresAt().isAfter(now) && entries.remove(item.getKey(), item.getValue())) {
                removed++;
            }
        }
        return Math.max(removed, Math.max(durable, durableCleanup));
    }

    private int cleanupDurableResources() {
        int completed = 0;
        for (String storageRef : repository.pendingResourceCleanupRefs()) {
            if (blobStore.delete(storageRef) && repository.completeResourceCleanup(storageRef)) {
                completed++;
            }
        }
        java.util.Set<String> indexed = new java.util.HashSet<>(repository.allResourceStorageRefs());
        for (String storageRef : blobStore.storageRefs()) {
            if (!indexed.contains(storageRef) && blobStore.delete(storageRef)) {
                completed++;
            }
        }
        return completed;
    }

    private String newHandle() {
        byte[] value = new byte[32];
        String candidate;
        do {
            random.nextBytes(value);
            candidate = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        } while (entries.containsKey(candidate)
                || repository != null && repository.findResource(candidate).isPresent());
        return candidate;
    }

    private static void requireBinding(TrustedDesktopConnection connection, ReadyOaSessionLease lease) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(lease, "lease");
        if (!connection.desktopInstanceId().equals(lease.desktopInstanceId())
                || !connection.desktopSessionId().equals(lease.desktopSessionId())
                || !connection.webSocketSessionId().equals(lease.webSocketSessionId())) {
            throw new IllegalArgumentException("resource binding does not match the active desktop lease");
        }
    }

    private static boolean sameBinding(TrustedDesktopConnection left, TrustedDesktopConnection right) {
        return left.reservationId().equals(right.reservationId())
                && left.desktopInstanceId().equals(right.desktopInstanceId())
                && left.desktopSessionId().equals(right.desktopSessionId())
                && left.webSocketSessionId().equals(right.webSocketSessionId());
    }

    private static boolean sameLease(ReadyOaSessionLease left, ReadyOaSessionLease right) {
        return left.authSessionId().equals(right.authSessionId())
                && left.desktopInstanceId().equals(right.desktopInstanceId())
                && left.desktopSessionId().equals(right.desktopSessionId())
                && left.webSocketSessionId().equals(right.webSocketSessionId())
                && left.tenantId().equals(right.tenantId())
                && left.generation() == right.generation()
                && left.activeCredentialRef().equals(right.activeCredentialRef());
    }

    private static String normalizeMediaType(String mediaType) {
        String normalized = mediaType == null ? "" : mediaType.strip().toLowerCase(java.util.Locale.ROOT);
        if (!SAFE_MEDIA_TYPES.contains(normalized)) throw new IllegalArgumentException("unsupported resource media type");
        return normalized;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private record Entry(String handle, TrustedDesktopConnection connection, ReadyOaSessionLease lease,
                         String mediaType, byte[] bytes, Instant createdAt, Instant expiresAt) {
        private ResourceDescriptor descriptor() {
            return new ResourceDescriptor(handle, mediaType, bytes.length, createdAt, expiresAt);
        }

        private StoredResource storedResource() {
            return new StoredResource(mediaType, bytes.clone(), expiresAt);
        }
    }

    private record RevokedGeneration(
            String desktopInstanceId,
            String desktopSessionId,
            String authSessionId,
            long generation) {
        private static RevokedGeneration from(
                TrustedDesktopConnection connection,
                ReadyOaSessionLease lease) {
            return new RevokedGeneration(
                    connection.desktopInstanceId(),
                    connection.desktopSessionId(),
                    lease.authSessionId(),
                    lease.generation());
        }
    }

    public record ResourceDescriptor(String handle, String mediaType, int contentLength,
                                     Instant createdAt, Instant expiresAt) {
        public ResourceDescriptor {
            if (handle == null || handle.isBlank()) throw new IllegalArgumentException("handle must not be blank");
            if (mediaType == null || mediaType.isBlank() || contentLength <= 0) throw new IllegalArgumentException("invalid resource descriptor");
        }

        @Override public String toString() {
            return "ResourceDescriptor(handle=[REDACTED], mediaType=" + mediaType
                    + ", contentLength=" + contentLength + ", expiresAt=" + expiresAt + ")";
        }
    }

    public record StoredResource(String mediaType, byte[] bytes, Instant expiresAt) {
        public StoredResource {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override public byte[] bytes() { return bytes.clone(); }

        @Override public String toString() {
            return "StoredResource(mediaType=" + mediaType + ", contentLength=" + bytes.length + ", expiresAt=" + expiresAt + ")";
        }
    }
}
