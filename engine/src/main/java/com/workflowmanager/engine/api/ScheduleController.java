package com.workflowmanager.engine.api;

import com.workflowmanager.engine.api.dto.CreateScheduleRequest;
import com.workflowmanager.engine.api.dto.ScheduleResponse;
import com.workflowmanager.engine.orchestrator.CronPolicy;
import com.workflowmanager.engine.orchestrator.DagStructureValidator;
import com.workflowmanager.engine.persistence.ScheduleRepository;
import com.workflowmanager.engine.persistence.ScheduleRepository.ScheduleRow;
import com.workflowmanager.engine.persistence.ScheduleRepository.ScheduleRun;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/schedules")
public class ScheduleController {

    private final ScheduleRepository schedules;
    private final DagValidator dagValidator;
    private final DagStructureValidator dagStructureValidator;
    private final CronPolicy cronPolicy;
    private final Clock clock;

    public ScheduleController(
            ScheduleRepository schedules,
            DagValidator dagValidator,
            DagStructureValidator dagStructureValidator,
            CronPolicy cronPolicy,
            Clock clock) {
        this.schedules = schedules;
        this.dagValidator = dagValidator;
        this.dagStructureValidator = dagStructureValidator;
        this.cronPolicy = cronPolicy;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<ScheduleResponse> create(@RequestBody CreateScheduleRequest req) {
        require(req.name() != null && !req.name().isBlank(), "name is required");
        require(
                req.workflowName() != null && !req.workflowName().isBlank(),
                "workflowName is required");
        require(req.workflowVersion() != null, "workflowVersion is required");
        require(
                req.cronExpression() != null && !req.cronExpression().isBlank(),
                "cronExpression is required");

        String timezone = (req.timezone() == null || req.timezone().isBlank()) ? "UTC" : req.timezone();
        try {
            cronPolicy.validate(req.cronExpression(), timezone);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid schedule: " + e.getMessage());
        }

        // The DAG a schedule fires is held to the same contract as one submitted directly.
        List<String> errors = dagValidator.validate(req.dag());
        if (errors.isEmpty()) {
            errors = dagStructureValidator.validate(req.dag());
        }
        if (!errors.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid dag: " + String.join("; ", errors));
        }

        Instant now = clock.instant();
        Instant nextFireAt = cronPolicy.nextAfter(req.cronExpression(), timezone, now);
        UUID id;
        try {
            id =
                    schedules.insert(
                            req.name(),
                            req.workflowName(),
                            req.workflowVersion(),
                            req.dag().toString(),
                            (req.input() == null || req.input().isNull()) ? null : req.input().toString(),
                            req.cronExpression(),
                            timezone,
                            nextFireAt,
                            now);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "a schedule named " + req.name() + " already exists");
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(get(id)));
    }

    @GetMapping
    public List<ScheduleResponse> list() {
        return schedules.findAll().stream().map(ScheduleController::toResponse).toList();
    }

    @GetMapping("/{id}")
    public ScheduleResponse getOne(@PathVariable UUID id) {
        return toResponse(get(id));
    }

    /** The runs this schedule has started, newest fire first. */
    @GetMapping("/{id}/runs")
    public List<ScheduleRun> runs(@PathVariable UUID id, @RequestParam(defaultValue = "20") int limit) {
        get(id);
        return schedules.findRuns(id, limit);
    }

    /**
     * Resuming re-anchors {@code next_fire_at} to the next slot from now, so a long pause does not
     * come back owing a backlog — the same reasoning as the missed-fire policy (ADR 0011).
     */
    @PatchMapping("/{id}")
    public ScheduleResponse setPaused(@PathVariable UUID id, @RequestParam boolean paused) {
        ScheduleRow schedule = get(id);
        Instant now = clock.instant();
        Instant nextFireAt =
                paused ? null : cronPolicy.nextAfter(schedule.cronExpression(), schedule.timezone(), now);
        schedules.setPaused(id, paused, nextFireAt, now);
        return toResponse(get(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (schedules.delete(id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "schedule not found");
        }
        return ResponseEntity.noContent().build();
    }

    private ScheduleRow get(UUID id) {
        return schedules
                .find(id)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "schedule not found"));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private static ScheduleResponse toResponse(ScheduleRow row) {
        return new ScheduleResponse(
                row.id(),
                row.name(),
                row.workflowName(),
                row.workflowVersion(),
                row.cronExpression(),
                row.timezone(),
                row.nextFireAt(),
                row.lastFiredAt(),
                row.paused());
    }
}
