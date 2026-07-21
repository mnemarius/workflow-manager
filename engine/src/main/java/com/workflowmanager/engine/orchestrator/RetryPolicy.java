package com.workflowmanager.engine.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Locale;

/** Per-task retry configuration (ARCHITECTURE.md decision C). Missing fields take the defaults. */
public record RetryPolicy(
        int maxAttempts, BackoffStrategy backoffStrategy, int initialDelaySeconds, int maxDelaySeconds) {

    public enum BackoffStrategy {
        FIXED,
        EXPONENTIAL
    }

    public static final RetryPolicy DEFAULT = new RetryPolicy(3, BackoffStrategy.FIXED, 5, 300);

    public static RetryPolicy from(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return DEFAULT;
        }
        return new RetryPolicy(
                node.hasNonNull("maxAttempts")
                        ? node.get("maxAttempts").asInt()
                        : DEFAULT.maxAttempts,
                node.hasNonNull("backoffStrategy")
                        ? BackoffStrategy.valueOf(
                                node.get("backoffStrategy").asText().toUpperCase(Locale.ROOT))
                        : DEFAULT.backoffStrategy,
                node.hasNonNull("initialDelaySeconds")
                        ? node.get("initialDelaySeconds").asInt()
                        : DEFAULT.initialDelaySeconds,
                node.hasNonNull("maxDelaySeconds")
                        ? node.get("maxDelaySeconds").asInt()
                        : DEFAULT.maxDelaySeconds);
    }

    public Duration backoffFor(int failedAttempts) {
        long seconds =
                switch (backoffStrategy) {
                    case FIXED -> initialDelaySeconds;
                    case EXPONENTIAL ->
                            Math.min(
                                    (long) initialDelaySeconds << Math.min(failedAttempts - 1, 32),
                                    maxDelaySeconds);
                };
        return Duration.ofSeconds(seconds);
    }

    public String toJson() {
        return "{\"maxAttempts\":"
                + maxAttempts
                + ",\"backoffStrategy\":\""
                + backoffStrategy.name().toLowerCase(Locale.ROOT)
                + "\",\"initialDelaySeconds\":"
                + initialDelaySeconds
                + ",\"maxDelaySeconds\":"
                + maxDelaySeconds
                + "}";
    }
}
