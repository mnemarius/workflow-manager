package com.workflowmanager.engine.application;

import java.time.Instant;
import java.util.UUID;

/**
 * A task handed to a worker by {@link TaskDispatchService}. {@code inputJson} may be null;
 * {@code attemptDeadline} null means the attempt is unbounded (no per-task timeout).
 */
public record DispatchedTask(
        UUID taskId,
        String taskKey,
        String type,
        String inputJson,
        int attempt,
        Instant leaseExpiresAt,
        Instant attemptDeadline) {}
