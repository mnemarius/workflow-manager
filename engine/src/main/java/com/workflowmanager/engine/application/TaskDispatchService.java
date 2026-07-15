package com.workflowmanager.engine.application;

import com.workflowmanager.engine.config.EngineProperties;
import com.workflowmanager.engine.domain.EventType;
import com.workflowmanager.engine.persistence.WorkflowRepository;
import com.workflowmanager.engine.persistence.WorkflowRepository.ClaimedTask;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Claims one ready task for a worker and leases it. One attempt of the gRPC long poll. */
@Service
public class TaskDispatchService {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatchService.class);

    private final WorkflowRepository repo;
    private final EngineProperties properties;
    private final Clock clock;

    public TaskDispatchService(WorkflowRepository repo, EngineProperties properties, Clock clock) {
        this.repo = repo;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Optional<DispatchedTask> tryClaim(String workerId, List<String> capabilities) {
        Instant now = clock.instant();
        Optional<ClaimedTask> claimedOpt = repo.claimReadyTask(capabilities, now);
        if (claimedOpt.isEmpty()) {
            return Optional.empty();
        }

        ClaimedTask claimed = claimedOpt.get();
        Instant leaseExpiresAt = now.plus(properties.leaseDuration());
        repo.markTaskRunning(claimed.taskId(), workerId, now, leaseExpiresAt);
        repo.markInstanceRunning(claimed.workflowInstanceId(), now);
        repo.insertEvent(claimed.workflowInstanceId(), claimed.taskId(), EventType.TASK_DISPATCHED, null);

        int attempt = claimed.attempts() + 1;
        MDC.put("workflow_id", claimed.workflowInstanceId().toString());
        MDC.put("task_id", claimed.taskId().toString());
        try {
            log.info("task dispatched key={} type={} worker={}", claimed.taskKey(), claimed.type(), workerId);
        } finally {
            MDC.remove("workflow_id");
            MDC.remove("task_id");
        }
        return Optional.of(
                new DispatchedTask(
                        claimed.taskId(),
                        claimed.taskKey(),
                        claimed.type(),
                        claimed.input(),
                        attempt,
                        leaseExpiresAt));
    }
}
