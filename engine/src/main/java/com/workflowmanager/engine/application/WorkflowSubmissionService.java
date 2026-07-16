package com.workflowmanager.engine.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.workflowmanager.engine.domain.EventType;
import com.workflowmanager.engine.orchestrator.DagPlanner;
import com.workflowmanager.engine.orchestrator.PlannedTask;
import com.workflowmanager.engine.persistence.WorkflowRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowSubmissionService.class);

    private final WorkflowRepository repo;
    private final DagPlanner planner;
    private final Clock clock;

    public WorkflowSubmissionService(WorkflowRepository repo, DagPlanner planner, Clock clock) {
        this.repo = repo;
        this.planner = planner;
        this.clock = clock;
    }

    @Transactional
    public UUID submit(String name, int version, JsonNode dag, JsonNode input) {
        Instant now = clock.instant();
        String inputJson = (input == null || input.isNull()) ? null : input.toString();

        UUID definitionId = repo.upsertDefinition(name, version, dag.toString());
        UUID instanceId = repo.insertInstance(definitionId, inputJson, now);

        MDC.put("workflow_id", instanceId.toString());
        try {
            for (PlannedTask task : planner.plan(dag, inputJson)) {
                repo.insertReadyTask(
                        instanceId,
                        task.key(),
                        task.type(),
                        task.inputJson(),
                        task.retryPolicy().maxAttempts(),
                        task.retryPolicy().toJson(),
                        task.timeoutSeconds(),
                        now,
                        now);
            }
            repo.insertEvent(instanceId, null, EventType.WORKFLOW_SUBMITTED, null);
            log.info("workflow submitted name={} version={}", name, version);
            return instanceId;
        } finally {
            MDC.remove("workflow_id");
        }
    }
}
