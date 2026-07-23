package com.workflowmanager.engine.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CronPolicyTest {

    private final CronPolicy policy = new CronPolicy();

    private static final String EVERY_TEN_MINUTES = "0 */10 * * * *";
    private static final String DAILY_9AM = "0 0 9 * * *";

    @Test
    void nextAfter_returnsNextSlotOnTheGrid() {
        Instant next = policy.nextAfter(EVERY_TEN_MINUTES, "UTC", Instant.parse("2026-07-23T10:03:00Z"));
        assertThat(next).isEqualTo(Instant.parse("2026-07-23T10:10:00Z"));
    }

    @Test
    void nextAfter_isExclusiveOfTheGivenInstant() {
        Instant next = policy.nextAfter(EVERY_TEN_MINUTES, "UTC", Instant.parse("2026-07-23T10:10:00Z"));
        assertThat(next).isEqualTo(Instant.parse("2026-07-23T10:20:00Z"));
    }

    @Test
    void nextAfter_evaluatesInTheScheduleZone() {
        // 09:00 in Oslo (UTC+2 in July) is 07:00Z.
        Instant next = policy.nextAfter(DAILY_9AM, "Europe/Oslo", Instant.parse("2026-07-23T00:00:00Z"));
        assertThat(next).isEqualTo(Instant.parse("2026-07-23T07:00:00Z"));
    }

    @Test
    void nextAfter_keepsLocalTimeAcrossDstBoundary() {
        // Oslo leaves DST on 2026-10-25. 09:00 stays 09:00 local, so its UTC instant shifts by an hour.
        Instant beforeDst = policy.nextAfter(DAILY_9AM, "Europe/Oslo", Instant.parse("2026-10-20T12:00:00Z"));
        Instant afterDst = policy.nextAfter(DAILY_9AM, "Europe/Oslo", Instant.parse("2026-10-27T12:00:00Z"));
        assertThat(beforeDst).isEqualTo(Instant.parse("2026-10-21T07:00:00Z"));
        assertThat(afterDst).isEqualTo(Instant.parse("2026-10-28T08:00:00Z"));
    }

    @Test
    void resolveDue_onTime_firesForThatSlotAndSkipsNothing() {
        var fire =
                policy.resolveDue(
                        EVERY_TEN_MINUTES,
                        "UTC",
                        Instant.parse("2026-07-23T10:10:00Z"),
                        Instant.parse("2026-07-23T10:10:02Z"));

        assertThat(fire.firedFor()).isEqualTo(Instant.parse("2026-07-23T10:10:00Z"));
        assertThat(fire.nextFireAt()).isEqualTo(Instant.parse("2026-07-23T10:20:00Z"));
        assertThat(fire.skipped()).isZero();
    }

    @Test
    void resolveDue_afterOutage_firesOnceForLatestSlotAndReportsTheBacklogItDropped() {
        // Due at 10:10, engine back at 12:05 — six hours of grid, one fire (ADR 0011).
        var fire =
                policy.resolveDue(
                        EVERY_TEN_MINUTES,
                        "UTC",
                        Instant.parse("2026-07-23T10:10:00Z"),
                        Instant.parse("2026-07-23T12:05:00Z"));

        assertThat(fire.firedFor()).as("fires for the most recent missed slot").isEqualTo(Instant.parse("2026-07-23T12:00:00Z"));
        assertThat(fire.nextFireAt()).as("resumes on the normal grid").isEqualTo(Instant.parse("2026-07-23T12:10:00Z"));
        assertThat(fire.skipped()).isEqualTo(11);
    }

    @Test
    void resolveDue_slotExactlyAtNow_countsAsDueNotSkipped() {
        var fire =
                policy.resolveDue(
                        EVERY_TEN_MINUTES,
                        "UTC",
                        Instant.parse("2026-07-23T10:10:00Z"),
                        Instant.parse("2026-07-23T10:20:00Z"));

        assertThat(fire.firedFor()).isEqualTo(Instant.parse("2026-07-23T10:20:00Z"));
        assertThat(fire.skipped()).isEqualTo(1);
    }

    @Test
    void validate_rejectsMalformedExpression() {
        assertThatThrownBy(() -> policy.validate("not a cron", "UTC"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_rejectsUnknownZone() {
        assertThatThrownBy(() -> policy.validate(DAILY_9AM, "Mars/Olympus"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void validate_acceptsSixFieldExpression() {
        policy.validate(DAILY_9AM, "Europe/Oslo");
    }
}
