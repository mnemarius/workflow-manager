package com.workflowmanager.engine.application;

import java.time.Clock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link LeaseReaper} on the clock: a startup pass (ARCHITECTURE decision G) plus a
 * periodic one. Separate from the core so the @Transactional call goes through the proxy and
 * tests can invoke {@code reapExpired} directly with a fast-forwarded Instant.
 */
@Component
class LeaseReaperSchedule {

    private final LeaseReaper reaper;
    private final Clock clock;

    LeaseReaperSchedule(LeaseReaper reaper, Clock clock) {
        this.reaper = reaper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${engine.reaper-interval:5s}")
    void reapOnInterval() {
        reaper.reapExpired(clock.instant());
    }

    @EventListener(ApplicationReadyEvent.class)
    void reapOnStartup() {
        reaper.reapExpired(clock.instant());
    }
}
