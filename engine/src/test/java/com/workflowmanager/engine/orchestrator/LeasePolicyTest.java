package com.workflowmanager.engine.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LeasePolicyTest {

    private final LeasePolicy policy = new LeasePolicy();
    private final Instant attemptStart = Instant.parse("2026-07-07T12:00:00Z");
    private final Duration lease = Duration.ofSeconds(30);

    @Test
    void attemptDeadline_noTimeout_returnsNull() {
        assertThat(policy.attemptDeadline(attemptStart, null)).isNull();
    }

    @Test
    void attemptDeadline_withTimeout_returnsStartPlusTimeout() {
        assertThat(policy.attemptDeadline(attemptStart, 120))
                .isEqualTo(attemptStart.plusSeconds(120));
    }

    @Test
    void cappedExpiry_noDeadline_returnsNowPlusLeaseDuration() {
        assertThat(policy.cappedExpiry(attemptStart, lease, null))
                .isEqualTo(attemptStart.plus(lease));
    }

    @Test
    void cappedExpiry_deadlineBeyondLease_returnsNowPlusLeaseDuration() {
        Instant deadline = attemptStart.plusSeconds(300);
        assertThat(policy.cappedExpiry(attemptStart, lease, deadline))
                .isEqualTo(attemptStart.plus(lease));
    }

    @Test
    void cappedExpiry_deadlineWithinLease_capsAtDeadline() {
        Instant deadline = attemptStart.plusSeconds(10);
        assertThat(policy.cappedExpiry(attemptStart, lease, deadline)).isEqualTo(deadline);
    }

    @Test
    void cappedExpiry_renewalsNeverExceedDeadline() {
        Instant deadline = policy.attemptDeadline(attemptStart, 60);
        for (Instant now = attemptStart; now.isBefore(deadline); now = now.plusSeconds(7)) {
            assertThat(policy.cappedExpiry(now, lease, deadline)).isBeforeOrEqualTo(deadline);
        }
    }

    @Test
    void pastDeadline_beforeDeadline_false() {
        Instant deadline = attemptStart.plusSeconds(60);
        assertThat(policy.pastDeadline(deadline, deadline.minusMillis(1))).isFalse();
    }

    @Test
    void pastDeadline_atDeadline_true() {
        Instant deadline = attemptStart.plusSeconds(60);
        assertThat(policy.pastDeadline(deadline, deadline)).isTrue();
    }

    @Test
    void pastDeadline_afterDeadline_true() {
        Instant deadline = attemptStart.plusSeconds(60);
        assertThat(policy.pastDeadline(deadline, deadline.plusSeconds(1))).isTrue();
    }

    @Test
    void pastDeadline_noDeadline_neverTrue() {
        assertThat(policy.pastDeadline(null, attemptStart.plusSeconds(999999))).isFalse();
    }
}
