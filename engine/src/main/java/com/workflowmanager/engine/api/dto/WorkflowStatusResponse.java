package com.workflowmanager.engine.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

public record WorkflowStatusResponse(
        UUID instanceId, String status, JsonNode output, List<TaskStatusResponse> tasks) {}
