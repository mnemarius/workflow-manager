package com.workflowmanager.engine.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code cronExpression} is Spring's six-field form (seconds first); {@code timezone} defaults to
 * UTC. {@code name} identifies the schedule itself, {@code workflowName}/{@code workflowVersion}
 * the definition each fire submits.
 */
public record CreateScheduleRequest(
        String name,
        String workflowName,
        Integer workflowVersion,
        JsonNode dag,
        JsonNode input,
        String cronExpression,
        String timezone) {}
