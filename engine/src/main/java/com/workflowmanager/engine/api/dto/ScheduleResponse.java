package com.workflowmanager.engine.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ScheduleResponse(
        UUID id,
        String name,
        String workflowName,
        int workflowVersion,
        String cronExpression,
        String timezone,
        Instant nextFireAt,
        Instant lastFiredAt,
        boolean paused) {}
