package com.workflowmanager.engine.domain;

public enum TaskStatus {
    PENDING,
    READY,
    RUNNING,
    SUCCEEDED,
    FAILED,
    RETRY_SCHEDULED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
