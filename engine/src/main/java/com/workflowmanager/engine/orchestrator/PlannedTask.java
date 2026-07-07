package com.workflowmanager.engine.orchestrator;

/** A task the engine will create from a submitted definition. {@code inputJson} may be null. */
public record PlannedTask(String key, String type, String inputJson, int maxAttempts) {}
