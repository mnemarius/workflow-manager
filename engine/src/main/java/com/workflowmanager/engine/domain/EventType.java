package com.workflowmanager.engine.domain;

public enum EventType {
    WORKFLOW_SUBMITTED,
    TASK_DISPATCHED,
    TASK_SUCCEEDED,
    TASK_RETRY_SCHEDULED,
    TASK_FAILED,
    WORKFLOW_SUCCEEDED,
    WORKFLOW_FAILED
}
