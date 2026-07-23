package com.workflowmanager.engine.orchestrator;

import java.util.List;

/**
 * A task the engine will create from a submitted definition. {@code inputJson} may be null;
 * {@code timeoutSeconds} null means the attempt is unbounded. {@code dependsOn} holds the keys of
 * the tasks this one waits on — empty when the task has no dependencies (starts READY).
 * {@code delaySeconds} null means the task is claimable as soon as it is READY (ADR 0011).
 */
public record PlannedTask(
        String key,
        String type,
        String inputJson,
        RetryPolicy retryPolicy,
        Integer timeoutSeconds,
        Integer delaySeconds,
        List<String> dependsOn) {}
