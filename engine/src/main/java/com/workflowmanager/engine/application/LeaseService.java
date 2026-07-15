package com.workflowmanager.engine.application;

import com.workflowmanager.engine.config.EngineProperties;
import com.workflowmanager.engine.orchestrator.CompletionPolicy;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.Held;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.NotHeld;
import com.workflowmanager.engine.persistence.WorkflowRepository;
import com.workflowmanager.engine.persistence.WorkflowRepository.RunningTask;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Renews a worker's lease on a heartbeat, gated by the same ownership check as completion. */
@Service
public class LeaseService {

    public sealed interface RenewalResult permits Renewed, Lost {}

    public record Renewed(Instant leaseExpiresAt) implements RenewalResult {}

    public record Lost(String reason) implements RenewalResult {}

    private static final Logger log = LoggerFactory.getLogger(LeaseService.class);

    private final WorkflowRepository repo;
    private final CompletionPolicy policy;
    private final EngineProperties properties;
    private final Clock clock;

    public LeaseService(
            WorkflowRepository repo,
            CompletionPolicy policy,
            EngineProperties properties,
            Clock clock) {
        this.repo = repo;
        this.policy = policy;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public RenewalResult renew(UUID taskId, String workerId) {
        Instant now = clock.instant();
        RunningTask running = repo.loadRunningTask(taskId).orElse(null);
        if (running == null) {
            return new Lost("unknown task");
        }

        var check =
                policy.checkLease(
                        running.status(),
                        running.leaseWorkerId(),
                        running.leaseExpiresAt(),
                        workerId,
                        now);

        MDC.put("workflow_id", running.workflowInstanceId().toString());
        MDC.put("task_id", taskId.toString());
        try {
            return switch (check) {
                case Held ignored -> {
                    Instant newExpiry = now.plus(properties.leaseDuration());
                    repo.renewLease(taskId, newExpiry);
                    log.debug("lease renewed worker={} expiresAt={}", workerId, newExpiry);
                    yield new Renewed(newExpiry);
                }
                case NotHeld notHeld -> {
                    log.warn("lease renewal rejected worker={} reason={}", workerId, notHeld.reason());
                    yield new Lost(notHeld.reason());
                }
            };
        } finally {
            MDC.remove("workflow_id");
            MDC.remove("task_id");
        }
    }
}
