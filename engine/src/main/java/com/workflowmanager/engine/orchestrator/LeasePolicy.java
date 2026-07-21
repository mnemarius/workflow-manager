package com.workflowmanager.engine.orchestrator;

import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The lease-grant math (ADR 0007): a lease is liveness, the per-task timeout is the attempt's
 * absolute budget. Every grant and renewal is capped at the attempt deadline, so a
 * hung-but-heartbeating worker cannot hold a task past its budget.
 */
@Component
public class LeasePolicy {

    /** @return null when the task has no timeout (unbounded attempt). */
    public Instant attemptDeadline(Instant attemptStart, Integer timeoutSeconds) {
        return timeoutSeconds == null ? null : attemptStart.plusSeconds(timeoutSeconds);
    }

    public Instant cappedExpiry(Instant now, Duration leaseDuration, Instant attemptDeadline) {
        Instant expiry = now.plus(leaseDuration);
        return attemptDeadline != null && attemptDeadline.isBefore(expiry) ? attemptDeadline : expiry;
    }

    public boolean pastDeadline(Instant attemptDeadline, Instant now) {
        return attemptDeadline != null && !now.isBefore(attemptDeadline);
    }
}
