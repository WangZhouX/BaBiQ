package com.wzx.babiq.server.business.workbench;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessScheduleOperationRecovery implements ApplicationRunner {
    private final BusinessScheduleOperationRepository repository;
    private final Clock clock;

    @Autowired
    public BusinessScheduleOperationRecovery(BusinessScheduleOperationRepository repository) {
        this(repository, Clock.systemUTC());
    }

    BusinessScheduleOperationRecovery(BusinessScheduleOperationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        repository.recoverInFlight(clock.instant());
    }
}
