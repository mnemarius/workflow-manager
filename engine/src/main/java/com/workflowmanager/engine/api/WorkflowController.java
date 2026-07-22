package com.workflowmanager.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowmanager.engine.api.dto.SubmitWorkflowRequest;
import com.workflowmanager.engine.api.dto.SubmitWorkflowResponse;
import com.workflowmanager.engine.api.dto.TaskStatusResponse;
import com.workflowmanager.engine.api.dto.WorkflowStatusResponse;
import com.workflowmanager.engine.application.WorkflowSubmissionService;
import com.workflowmanager.engine.orchestrator.DagStructureValidator;
import com.workflowmanager.engine.persistence.WorkflowRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/workflows")
public class WorkflowController {

    private final WorkflowSubmissionService submissionService;
    private final WorkflowRepository repo;
    private final DagValidator dagValidator;
    private final DagStructureValidator dagStructureValidator;
    private final ObjectMapper mapper;

    public WorkflowController(
            WorkflowSubmissionService submissionService,
            WorkflowRepository repo,
            DagValidator dagValidator,
            DagStructureValidator dagStructureValidator,
            ObjectMapper mapper) {
        this.submissionService = submissionService;
        this.repo = repo;
        this.dagValidator = dagValidator;
        this.dagStructureValidator = dagStructureValidator;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<SubmitWorkflowResponse> submit(@RequestBody SubmitWorkflowRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (req.version() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "version is required");
        }
        List<String> dagErrors = dagValidator.validate(req.dag());
        if (!dagErrors.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid dag: " + String.join("; ", dagErrors));
        }
        List<String> structureErrors = dagStructureValidator.validate(req.dag());
        if (!structureErrors.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid dag: " + String.join("; ", structureErrors));
        }

        UUID instanceId = submissionService.submit(req.name(), req.version(), req.dag(), req.input());
        return ResponseEntity.status(HttpStatus.CREATED).body(new SubmitWorkflowResponse(instanceId));
    }

    @GetMapping("/{id}")
    public WorkflowStatusResponse get(@PathVariable UUID id) {
        WorkflowRepository.InstanceRow instance =
                repo.findInstance(id)
                        .orElseThrow(
                                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "workflow not found"));
        List<TaskStatusResponse> tasks =
                repo.findTasks(id).stream()
                        .map(
                                t ->
                                        new TaskStatusResponse(
                                                t.taskKey(), t.type(), t.status().name(), t.attempts(), parse(t.output())))
                        .toList();
        return new WorkflowStatusResponse(id, instance.status().name(), parse(instance.output()), tasks);
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
