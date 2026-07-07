package com.workflowmanager.engine.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record TaskStatusResponse(
        String taskKey, String type, String status, int attempts, JsonNode output) {}
