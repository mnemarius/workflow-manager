package com.workflowmanager.engine.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowmanager.engine.orchestrator.RetryPolicy.BackoffStrategy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void from_nullNode_returnsAllDefaults() {
        assertThat(RetryPolicy.from(null)).isEqualTo(RetryPolicy.DEFAULT);
        assertThat(RetryPolicy.DEFAULT)
                .isEqualTo(new RetryPolicy(3, BackoffStrategy.FIXED, 5, 300));
    }

    @Test
    void from_missingNode_returnsAllDefaults() {
        var task = parse("{\"key\":\"step1\"}");
        assertThat(RetryPolicy.from(task.get("retryPolicy"))).isEqualTo(RetryPolicy.DEFAULT);
    }

    @Test
    void from_partialJson_fillsRemainingDefaults() {
        var policy = RetryPolicy.from(parse("{\"maxAttempts\":5}"));
        assertThat(policy).isEqualTo(new RetryPolicy(5, BackoffStrategy.FIXED, 5, 300));
    }

    @Test
    void from_fullJson_parsesAllFields() {
        var policy =
                RetryPolicy.from(
                        parse(
                                "{\"maxAttempts\":2,\"backoffStrategy\":\"exponential\","
                                        + "\"initialDelaySeconds\":10,\"maxDelaySeconds\":60}"));
        assertThat(policy).isEqualTo(new RetryPolicy(2, BackoffStrategy.EXPONENTIAL, 10, 60));
    }

    @Test
    void backoffFor_fixedStrategy_returnsInitialDelayForEveryAttempt() {
        var policy = new RetryPolicy(5, BackoffStrategy.FIXED, 7, 300);
        assertThat(policy.backoffFor(1)).isEqualTo(Duration.ofSeconds(7));
        assertThat(policy.backoffFor(4)).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void backoffFor_exponentialStrategy_doublesPerFailedAttempt() {
        var policy = new RetryPolicy(5, BackoffStrategy.EXPONENTIAL, 5, 300);
        assertThat(policy.backoffFor(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.backoffFor(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.backoffFor(3)).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void backoffFor_exponentialStrategy_capsAtMaxDelay() {
        var policy = new RetryPolicy(20, BackoffStrategy.EXPONENTIAL, 5, 60);
        assertThat(policy.backoffFor(5)).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.backoffFor(15)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void toJson_roundTripsThroughFrom() {
        var policy = new RetryPolicy(4, BackoffStrategy.EXPONENTIAL, 2, 30);
        assertThat(RetryPolicy.from(parse(policy.toJson()))).isEqualTo(policy);
    }
}
