package com.wzx.babiq.server.business.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-db/schedule-operations-${random.uuid}.db",
        "spring.flyway.clean-disabled=false"
})
class BusinessScheduleOperationRepositoryIT {
    @Autowired
    private BusinessScheduleOperationRepository repository;

    @Test
    void durable_claim_has_one_winner_and_binds_the_exact_request() {
        String id = "operation-" + UUID.randomUUID();
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        BusinessScheduleOperationRepository.Request request = request(id, "fingerprint-a");

        assertThat(repository.claim(request, now).decision())
                .isEqualTo(BusinessScheduleOperationRepository.Decision.WON);
        assertThat(repository.claim(request, now.plusSeconds(1)).decision())
                .isEqualTo(BusinessScheduleOperationRepository.Decision.IN_FLIGHT);
        assertThat(repository.claim(request(id, "fingerprint-b"), now.plusSeconds(2)).decision())
                .isEqualTo(BusinessScheduleOperationRepository.Decision.CONFLICT);

        assertThat(repository.complete(id, "fingerprint-a", 7, now.plusSeconds(3))).isTrue();
        BusinessScheduleOperationRepository.Claim completed =
                repository.claim(request, now.plusSeconds(4));
        assertThat(completed.decision()).isEqualTo(BusinessScheduleOperationRepository.Decision.COMPLETED);
        assertThat(completed.record().resultRevision()).isEqualTo(7);
    }

    @Test
    void recovery_makes_abandoned_in_flight_unknown_and_definite_failure_can_be_reclaimed() {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        String abandoned = "operation-" + UUID.randomUUID();
        BusinessScheduleOperationRepository.Request abandonedRequest = request(abandoned, "fingerprint-c");
        assertThat(repository.claim(abandonedRequest, now).decision())
                .isEqualTo(BusinessScheduleOperationRepository.Decision.WON);
        assertThat(repository.recoverInFlight(now.plusSeconds(1))).isPositive();
        assertThat(repository.claim(abandonedRequest, now.plusSeconds(2)).decision())
                .isEqualTo(BusinessScheduleOperationRepository.Decision.OUTCOME_UNKNOWN);

        String failed = "operation-" + UUID.randomUUID();
        BusinessScheduleOperationRepository.Request failedRequest = request(failed, "fingerprint-d");
        assertThat(repository.claim(failedRequest, now).decision())
                .isEqualTo(BusinessScheduleOperationRepository.Decision.WON);
        assertThat(repository.markFailed(failed, "fingerprint-d", now.plusSeconds(1))).isTrue();
        assertThat(repository.claim(failedRequest, now.plusSeconds(2)).decision())
                .isEqualTo(BusinessScheduleOperationRepository.Decision.WON);
    }

    private static BusinessScheduleOperationRepository.Request request(String id, String fingerprint) {
        return new BusinessScheduleOperationRepository.Request(
                id, "desktop-1", "session-1", "auth-1", "tenant-1", 3,
                "client-" + id, "user-1", 0, "batch-" + id, fingerprint);
    }
}
