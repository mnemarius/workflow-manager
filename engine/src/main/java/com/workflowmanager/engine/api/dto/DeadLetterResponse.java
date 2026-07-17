package com.workflowmanager.engine.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record DeadLetterResponse(
        UUID taskId,
        UUID workflowInstanceId,
        String taskKey,
        String type,
        int attempts,
        String failureReason,
        JsonNode lastError,
        Instant finishedAt) {}
