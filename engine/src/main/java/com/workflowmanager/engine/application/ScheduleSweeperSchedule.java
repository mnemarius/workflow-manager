package com.workflowmanager.engine.application;

import java.time.Clock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link ScheduleSweeper} on the clock, mirroring {@code LeaseReaperSchedule}: a startup
 * pass so schedules that came due while the engine was down are resolved immediately, plus a
 * periodic one. Separate from the core so the @Transactional call goes through the proxy and tests
 * can invoke {@code sweep} directly with a fast-forwarded Instant.
 */
@Component
class ScheduleSweeperSchedule {

    private final ScheduleSweeper sweeper;
    private final Clock clock;

    ScheduleSweeperSchedule(ScheduleSweeper sweeper, Clock clock) {
        this.sweeper = sweeper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${engine.schedule-sweep-interval:5s}")
    void sweepOnInterval() {
        sweeper.sweep(clock.instant());
    }

    @EventListener(ApplicationReadyEvent.class)
    void sweepOnStartup() {
        sweeper.sweep(clock.instant());
    }
}
