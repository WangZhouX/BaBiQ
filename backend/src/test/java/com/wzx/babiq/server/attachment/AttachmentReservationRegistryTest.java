package com.wzx.babiq.server.attachment;

import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentReservationRegistryTest {

    @Test
    void exactly_one_concurrent_turn_can_reserve_the_same_attachment_identity() throws Exception {
        AttachmentReservationRegistry registry = new AttachmentReservationRegistry();
        PreparedAttachment attachment = attachment();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> first = workers.submit(() -> reserve(registry, attachment, ready, start));
            Future<Attempt> second = workers.submit(() -> reserve(registry, attachment, ready, start));
            ready.await();
            start.countDown();

            List<Attempt> attempts = List.of(first.get(), second.get());
            assertThat(attempts).filteredOn(attempt -> attempt.reservation() != null).hasSize(1);
            assertThat(attempts).filteredOn(attempt -> attempt.failure() != null)
                    .singleElement()
                    .extracting(attempt -> attempt.failure().code())
                    .isEqualTo(AttachmentErrorCode.ATTACHMENT_REFERENCE_AMBIGUOUS);

            attempts.stream()
                    .map(Attempt::reservation)
                    .filter(java.util.Objects::nonNull)
                    .forEach(AttachmentReservationRegistry.Reservation::close);

            try (AttachmentReservationRegistry.Reservation ignored = registry.reserve(
                    "thread-a", BusinessIdentityScope.UNSCOPED, List.of(attachment))) {
                assertThat(ignored.active()).isTrue();
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void release_by_turn_makes_the_identity_available_after_persistence_or_failure() {
        AttachmentReservationRegistry registry = new AttachmentReservationRegistry();
        PreparedAttachment attachment = attachment();
        AttachmentReservationRegistry.Reservation reservation = registry.reserve(
                "thread-a", BusinessIdentityScope.UNSCOPED, List.of(attachment));
        reservation.bindToTurn("turn-a");

        registry.releaseTurn("turn-a");

        try (AttachmentReservationRegistry.Reservation next = registry.reserve(
                "thread-a", BusinessIdentityScope.UNSCOPED, List.of(attachment))) {
            assertThat(next.active()).isTrue();
        }
    }

    @Test
    void a_turn_bound_reservation_does_not_expire_while_legitimately_queued() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        AttachmentReservationRegistry registry = new AttachmentReservationRegistry(clock);
        PreparedAttachment attachment = attachment();
        AttachmentReservationRegistry.Reservation reservation = registry.reserve(
                "thread-a", BusinessIdentityScope.UNSCOPED, List.of(attachment));
        reservation.bindToTurn("turn-a");
        clock.advance(Duration.ofHours(1));

        assertThatThrownBy(() -> registry.reserve(
                "thread-a", BusinessIdentityScope.UNSCOPED, List.of(attachment)))
                .isInstanceOfSatisfying(AttachmentException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(AttachmentErrorCode.ATTACHMENT_REFERENCE_AMBIGUOUS));

        registry.releaseTurn("turn-a");
    }

    private static Attempt reserve(
            AttachmentReservationRegistry registry,
            PreparedAttachment attachment,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return new Attempt(
                    registry.reserve(
                            "thread-a", BusinessIdentityScope.UNSCOPED, List.of(attachment)),
                    null);
        } catch (AttachmentException failure) {
            return new Attempt(null, failure);
        }
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

    private record Attempt(
            AttachmentReservationRegistry.Reservation reservation,
            AttachmentException failure
    ) {
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
