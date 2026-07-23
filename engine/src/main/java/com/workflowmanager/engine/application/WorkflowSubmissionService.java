package com.workflowmanager.engine.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.workflowmanager.engine.domain.EventType;
import com.workflowmanager.engine.domain.TaskStatus;
import com.workflowmanager.engine.orchestrator.DagPlanner;
import com.workflowmanager.engine.orchestrator.PlannedTask;
import com.workflowmanager.engine.persistence.WorkflowRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        return submit(name, version, dag, input, null, null);
    }

    /**
     * {@code scheduleId} and {@code firedFor} mark a cron-triggered run (ADR 0011); the unique
     * index over the pair means a duplicate fire fails here rather than starting a second run.
     */
    @Transactional
    public UUID submit(
            String name, int version, JsonNode dag, JsonNode input, UUID scheduleId, Instant firedFor) {
        Instant now = clock.instant();
        String inputJson = (input == null || input.isNull()) ? null : input.toString();

        UUID definitionId = repo.upsertDefinition(name, version, dag.toString());
        UUID instanceId = repo.insertInstance(definitionId, inputJson, now, scheduleId, firedFor);

        MDC.put("workflow_id", instanceId.toString());
        try {
            List<PlannedTask> plannedTasks = planner.plan(dag, inputJson);

            // Pass 1: insert every task (READY when it has no deps, else PENDING) and remember its id.
            Map<String, UUID> taskIdsByKey = new HashMap<>();
            for (PlannedTask task : plannedTasks) {
                boolean isRoot = task.dependsOn().isEmpty();
                TaskStatus status = isRoot ? TaskStatus.READY : TaskStatus.PENDING;
                // A root task is READY at submit, so its delay runs from now; a dependent task's
                // delay runs from promotion instead and is applied there (ADR 0011).
                Instant scheduledAt =
                        (isRoot && task.delaySeconds() != null)
                                ? now.plusSeconds(task.delaySeconds())
                                : now;
                UUID taskId =
                        repo.insertTask(
                                status,
                                instanceId,
                                task.key(),
                                task.type(),
                                task.inputJson(),
                                task.retryPolicy().maxAttempts(),
                                task.retryPolicy().toJson(),
                                task.timeoutSeconds(),
                                task.delaySeconds(),
                                scheduledAt,
                                now);
                taskIdsByKey.put(task.key(), taskId);
            }

            // Pass 2: wire dependency edges now that every task key has an id.
            for (PlannedTask task : plannedTasks) {
                UUID taskId = taskIdsByKey.get(task.key());
                for (String dependsOnKey : task.dependsOn()) {
                    repo.insertDependency(taskId, taskIdsByKey.get(dependsOnKey));
                }
            }

            repo.insertEvent(instanceId, null, EventType.WORKFLOW_SUBMITTED, null);
            log.info("workflow submitted name={} version={}", name, version);
            return instanceId;
        } finally {
            MDC.remove("workflow_id");
        }
    }
}
