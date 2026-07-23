package com.workflowmanager.engine.orchestrator;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * Cron arithmetic and the missed-fire rule (ADR 0011). Pure — no DB, no clock of its own — so the
 * catch-up-versus-skip decision stays a single testable place. Expressions are Spring's six-field
 * form (seconds first) and are evaluated in the schedule's own zone, so a 09:00 daily schedule
 * stays at 09:00 local across a DST boundary.
 */
@Component
public class CronPolicy {

    /**
     * What a due schedule should do right now. A schedule that fell behind fires exactly once for
     * the most recent missed slot and then resumes on the normal grid: an engine down for six hours
     * must not submit six hours' worth of backlog when it comes back.
     *
     * @param nextFireAt the slot the schedule was waiting on
     * @param skipped how many further slots elapsed and will not be fired
     */
    public record Fire(Instant firedFor, Instant nextFireAt, int skipped) {}

    public Instant nextAfter(String expression, String timezone, Instant after) {
        ZoneId zone = ZoneId.of(timezone);
        ZonedDateTime next = CronExpression.parse(expression).next(ZonedDateTime.ofInstant(after, zone));
        if (next == null) {
            throw new IllegalArgumentException("cron expression never fires again: " + expression);
        }
        return next.toInstant();
    }

    /** @throws IllegalArgumentException if the expression or zone is not parseable. */
    public void validate(String expression, String timezone) {
        ZoneId.of(timezone);
        CronExpression.parse(expression);
    }

    /**
     * Resolves a due schedule to the single fire it should perform. Walks the grid forward from the
     * slot it was waiting on, counting what it skipped, and fires for the last slot that is not in
     * the future.
     */
    public Fire resolveDue(String expression, String timezone, Instant nextFireAt, Instant now) {
        Instant firedFor = nextFireAt;
        int skipped = 0;
        Instant candidate = nextAfter(expression, timezone, firedFor);
        while (!candidate.isAfter(now)) {
            firedFor = candidate;
            skipped++;
            candidate = nextAfter(expression, timezone, firedFor);
        }
        return new Fire(firedFor, candidate, skipped);
    }
}
