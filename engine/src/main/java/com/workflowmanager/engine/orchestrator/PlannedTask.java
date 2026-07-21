package com.workflowmanager.engine.orchestrator;

/**
 * A task the engine will create from a submitted definition. {@code inputJson} may be null;
 * {@code timeoutSeconds} null means the attempt is unbounded.
 */
public record PlannedTask(
        String key, String type, String inputJson, RetryPolicy retryPolicy, Integer timeoutSeconds) {}
