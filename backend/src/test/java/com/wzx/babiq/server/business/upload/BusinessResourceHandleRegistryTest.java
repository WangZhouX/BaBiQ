package com.wzx.babiq.server.business.upload;

import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessResourceHandleRegistryTest {

    @Test
    void handle_is_opaque_and_bound_to_connection_and_generation() {
        Instant now = Instant.parse("2026-07-27T00:00:00Z");
        BusinessResourceHandleRegistry registry = registry(now);
        TrustedDesktopConnection connection = connection("instance-1", "desktop-1", "ws-1");
        ReadyOaSessionLease lease = lease(connection, 7);

        BusinessResourceHandleRegistry.ResourceDescriptor descriptor = registry.register(
                connection, lease, "image/png", new byte[]{1, 2, 3}, Duration.ofMinutes(1));

        assertThat(descriptor.handle()).doesNotContain("oa.example", "https", "tenant-1");
        assertThat(registry.resolve(descriptor.handle(), connection, lease).orElseThrow().bytes())
                .containsExactly(1, 2, 3);
        assertThat(registry.resolve(descriptor.handle(), connection("instance-1", "desktop-1", "ws-2"), lease))
                .isEmpty();
        assertThat(registry.resolve(descriptor.handle(), connection, lease(connection, 8))).isEmpty();
    }

    @Test
    void expired_or_revoked_handle_is_unavailable() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-27T00:00:00Z"));
        BusinessResourceHandleRegistry registry = new BusinessResourceHandleRegistry(clock, Duration.ofMinutes(5));
        TrustedDesktopConnection connection = connection("instance-1", "desktop-1", "ws-1");
        ReadyOaSessionLease lease = lease(connection, 1);
        BusinessResourceHandleRegistry.ResourceDescriptor descriptor = registry.register(
                connection, lease, "application/pdf", new byte[]{9}, Duration.ofSeconds(2));

        clock.advance(Duration.ofSeconds(3));
        assertThat(registry.resolve(descriptor.handle(), connection, lease)).isEmpty();

        BusinessResourceHandleRegistry.ResourceDescriptor second = registry.register(
                connection, lease, "image/jpeg", new byte[]{8}, Duration.ofMinutes(1));
        registry.revoke(
                connection.desktopInstanceId(),
                connection.desktopSessionId(),
                lease.authSessionId(),
                lease.generation());
        assertThat(registry.resolve(second.handle(), connection, lease)).isEmpty();
    }

    @Test
    void rejects_unsafe_content_types_and_cross_session_registration() {
        BusinessResourceHandleRegistry registry = registry(Instant.now());
        TrustedDesktopConnection connection = connection("instance-1", "desktop-1", "ws-1");
        ReadyOaSessionLease lease = lease(connection, 1);

        assertThatThrownBy(() -> registry.register(connection, lease, "text/html", new byte[]{1}, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register(
                connection("instance-2", "desktop-2", "ws-2"), lease, "image/png", new byte[]{1}, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cleanup_waits_for_blob_registration_to_become_durably_indexed() throws Exception {
        BusinessAttachmentRepository repository = mock(BusinessAttachmentRepository.class);
        BusinessResourceBlobStore blobs = mock(BusinessResourceBlobStore.class);
        String storageRef = "upload-registering.part";
        CountDownLatch insertEntered = new CountDownLatch(1);
        CountDownLatch allowInsert = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        AtomicBoolean inserted = new AtomicBoolean();
        when(blobs.store(any(byte[].class))).thenReturn(storageRef);
        when(blobs.storageRefs()).thenReturn(Set.of(storageRef));
        when(repository.pendingResourceCleanupRefs()).thenReturn(List.of());
        when(repository.allResourceStorageRefs()).thenAnswer(
                ignored -> inserted.get() ? List.of(storageRef) : List.of());
        when(repository.purgeExpiredResources(any())).thenAnswer(ignored -> {
            cleanupEntered.countDown();
            return 0;
        });
        doAnswer(ignored -> {
            insertEntered.countDown();
            assertThat(allowInsert.await(5, TimeUnit.SECONDS)).isTrue();
            inserted.set(true);
            return null;
        }).when(repository).insertResource(any());
        BusinessResourceHandleRegistry registry = new BusinessResourceHandleRegistry(
                repository, blobs, Clock.systemUTC(), Duration.ofMinutes(5));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var registration = executor.submit(() -> registry.register(
                    connection("instance-1", "desktop-1", "ws-1"),
                    lease(connection("instance-1", "desktop-1", "ws-1"), 1),
                    "image/png", new byte[]{1}, Duration.ofMinutes(1)));
            assertThat(insertEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var cleanup = executor.submit(registry::purgeExpired);

            assertThat(cleanupEntered.await(250, TimeUnit.MILLISECONDS))
                    .as("cleanup must not observe the store-before-index window")
                    .isFalse();
            allowInsert.countDown();
            registration.get(5, TimeUnit.SECONDS);
            cleanup.get(5, TimeUnit.SECONDS);
        }

        verify(blobs, never()).delete(storageRef);
    }

    @Test
    void revoked_generation_cannot_register_a_late_active_blob() {
        BusinessAttachmentRepository repository = mock(BusinessAttachmentRepository.class);
        BusinessResourceBlobStore blobs = mock(BusinessResourceBlobStore.class);
        when(repository.revokeResources(
                "instance-1", "desktop-1", "auth-1", 4,
                Instant.parse("2026-07-27T00:00:00Z")))
                .thenReturn(1);
        BusinessResourceHandleRegistry registry = new BusinessResourceHandleRegistry(
                repository, blobs,
                Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5));
        TrustedDesktopConnection connection = connection("instance-1", "desktop-1", "ws-1");
        ReadyOaSessionLease revoked = lease(connection, 4);

        registry.revoke(
                connection.desktopInstanceId(),
                connection.desktopSessionId(),
                revoked.authSessionId(),
                revoked.generation());

        assertThatThrownBy(() -> registry.register(
                connection, revoked, "image/png", new byte[]{1}, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("resource lease generation is revoked");
        verify(blobs, never()).store(any(byte[].class));
    }

    @Test
    void revoked_auth_session_does_not_poison_same_generation_of_a_new_auth_session() {
        BusinessResourceHandleRegistry registry = registry(Instant.parse("2026-07-27T00:00:00Z"));
        TrustedDesktopConnection connection = connection("instance-1", "desktop-1", "ws-1");
        ReadyOaSessionLease revoked = lease(connection, "auth-old", 1);
        ReadyOaSessionLease replacement = lease(connection, "auth-new", 1);

        registry.revoke(connection, revoked);

        assertThat(registry.register(
                connection, replacement, "image/png", new byte[]{1}, Duration.ofMinutes(1)))
                .isNotNull();
    }

    @Test
    void durable_resolve_rechecks_row_after_loading_blob_bytes() {
        Instant now = Instant.parse("2026-07-27T00:00:00Z");
        BusinessAttachmentRepository repository = mock(BusinessAttachmentRepository.class);
        BusinessResourceBlobStore blobs = mock(BusinessResourceBlobStore.class);
        TrustedDesktopConnection connection = connection("instance-1", "desktop-1", "ws-1");
        ReadyOaSessionLease lease = lease(connection, 9);
        BusinessResourceHandleRecord active = new BusinessResourceHandleRecord(
                "opaque-handle", "instance-1", "desktop-1", "auth-1", "tenant-1", 9,
                "image/png", 1, "upload-resource.part", "SINGLE_SESSION",
                now, now.plusSeconds(60), null);
        BusinessResourceHandleRecord revoked = new BusinessResourceHandleRecord(
                "opaque-handle", "instance-1", "desktop-1", "auth-1", "tenant-1", 9,
                "image/png", 1, "upload-resource.part", "SINGLE_SESSION",
                now, now.plusSeconds(60), now.plusSeconds(1));
        when(repository.findResource("opaque-handle"))
                .thenReturn(Optional.of(active), Optional.of(revoked));
        when(blobs.load("upload-resource.part", 1)).thenReturn(new byte[]{1});
        BusinessResourceHandleRegistry registry = new BusinessResourceHandleRegistry(
                repository, blobs, Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5));

        assertThat(registry.resolve("opaque-handle", connection, lease)).isEmpty();
        verify(repository, times(2)).findResource("opaque-handle");
    }

    private static BusinessResourceHandleRegistry registry(Instant now) {
        return new BusinessResourceHandleRegistry(Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5));
    }

    private static TrustedDesktopConnection connection(String instance, String session, String ws) {
        return new TrustedDesktopConnection("reservation-" + instance, instance, session, ws);
    }

    private static ReadyOaSessionLease lease(TrustedDesktopConnection connection, long generation) {
        return lease(connection, "auth-1", generation);
    }

    private static ReadyOaSessionLease lease(
            TrustedDesktopConnection connection,
            String authSessionId,
            long generation) {
        return new ReadyOaSessionLease(authSessionId, connection.desktopInstanceId(), connection.desktopSessionId(),
                connection.webSocketSessionId(), "user-1", "tenant-1", "2", generation,
                "credential-" + generation, 1, Instant.parse("2026-07-27T00:00:00Z"));
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) { this.current = current; }
        private void advance(Duration amount) { current = current.plus(amount); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
