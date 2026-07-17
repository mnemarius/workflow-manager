package com.workflowmanager.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowmanager.engine.api.dto.DeadLetterResponse;
import com.workflowmanager.engine.application.DeadLetterService;
import com.workflowmanager.engine.application.DeadLetterService.NotDeadLettered;
import com.workflowmanager.engine.application.DeadLetterService.NotFound;
import com.workflowmanager.engine.application.DeadLetterService.Redriven;
import com.workflowmanager.engine.persistence.WorkflowRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/dead-letters")
public class DeadLetterController {

    private final DeadLetterService deadLetterService;
    private final WorkflowRepository repo;
    private final ObjectMapper mapper;

    public DeadLetterController(
            DeadLetterService deadLetterService, WorkflowRepository repo, ObjectMapper mapper) {
        this.deadLetterService = deadLetterService;
        this.repo = repo;
        this.mapper = mapper;
    }

    @GetMapping
    public List<DeadLetterResponse> list(@RequestParam(defaultValue = "50") int limit) {
        return repo.findDeadLetters(limit).stream()
                .map(
                        d ->
                                new DeadLetterResponse(
                                        d.taskId(),
                                        d.workflowInstanceId(),
                                        d.taskKey(),
                                        d.type(),
                                        d.attempts(),
                                        d.failureReason(),
                                        parse(d.lastError()),
                                        d.finishedAt()))
                .toList();
    }

    @PostMapping("/{taskId}/retry")
    public ResponseEntity<Void> retry(@PathVariable UUID taskId) {
        return switch (deadLetterService.redrive(taskId)) {
            case Redriven ignored -> ResponseEntity.noContent().build();
            case NotFound ignored ->
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found");
            case NotDeadLettered notDeadLettered ->
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "task is " + notDeadLettered.status() + ", not FAILED");
        };
    }

    private JsonNode parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
