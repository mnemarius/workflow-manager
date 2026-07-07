package com.workflowmanager.engine.grpc;

import com.workflowmanager.engine.application.DispatchedTask;
import com.workflowmanager.engine.application.TaskCompletionService;
import com.workflowmanager.engine.application.TaskDispatchService;
import com.workflowmanager.protocol.v1.CompleteTaskRequest;
import com.workflowmanager.protocol.v1.CompleteTaskResponse;
import com.workflowmanager.protocol.v1.FetchTaskRequest;
import com.workflowmanager.protocol.v1.FetchTaskResponse;
import com.workflowmanager.protocol.v1.NoTask;
import com.workflowmanager.protocol.v1.Task;
import com.workflowmanager.protocol.v1.WorkerServiceGrpc;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The worker-facing gRPC endpoint (ADR 0005). FetchTask long-polls: the call is held open on a
 * virtual thread, claiming work every {@link #POLL_INTERVAL} until a task appears or the
 * {@link #POLL_WINDOW} deadline passes (returning NoTask, below the client's ~30s deadline).
 */
@Component
public class WorkerServiceImpl extends WorkerServiceGrpc.WorkerServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(WorkerServiceImpl.class);
    private static final Duration POLL_WINDOW = Duration.ofSeconds(25);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private final TaskDispatchService dispatchService;
    private final TaskCompletionService completionService;
    private final Clock clock;

    public WorkerServiceImpl(
            TaskDispatchService dispatchService,
            TaskCompletionService completionService,
            Clock clock) {
        this.dispatchService = dispatchService;
        this.completionService = completionService;
        this.clock = clock;
    }

    @Override
    public void fetchTask(FetchTaskRequest request, StreamObserver<FetchTaskResponse> observer) {
        List<String> capabilities = request.getCapabilitiesList();
        Instant deadline = clock.instant().plus(POLL_WINDOW);
        try {
            while (!Context.current().isCancelled()) {
                Optional<DispatchedTask> claimed =
                        dispatchService.tryClaim(request.getWorkerId(), capabilities);
                if (claimed.isPresent()) {
                    observer.onNext(taskResponse(claimed.get()));
                    observer.onCompleted();
                    return;
                }
                if (!clock.instant().isBefore(deadline)) {
                    observer.onNext(
                            FetchTaskResponse.newBuilder().setNoTask(NoTask.getDefaultInstance()).build());
                    observer.onCompleted();
                    return;
                }
                Thread.sleep(POLL_INTERVAL.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("fetchTask failed worker={}", request.getWorkerId(), e);
            observer.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void completeTask(
            CompleteTaskRequest request, StreamObserver<CompleteTaskResponse> observer) {
        UUID taskId;
        try {
            taskId = UUID.fromString(request.getTaskId());
        } catch (IllegalArgumentException e) {
            observer.onError(
                    Status.INVALID_ARGUMENT.withDescription("invalid task_id").asRuntimeException());
            return;
        }

        boolean success = request.hasSuccess();
        String output = success ? emptyToNull(request.getSuccess().getOutput()) : null;
        String error = request.hasFailure() ? request.getFailure().getErrorMessage() : null;

        boolean accepted =
                completionService.complete(taskId, request.getWorkerId(), success, output, error);
        observer.onNext(CompleteTaskResponse.newBuilder().setAccepted(accepted).build());
        observer.onCompleted();
    }

    private static FetchTaskResponse taskResponse(DispatchedTask task) {
        Task.Builder builder =
                Task.newBuilder()
                        .setTaskId(task.taskId().toString())
                        .setTaskKey(task.taskKey())
                        .setType(task.type() == null ? "" : task.type())
                        .setInput(task.inputJson() == null ? "" : task.inputJson())
                        .setAttempt(task.attempt())
                        .setLeaseExpiresAt(task.leaseExpiresAt().toString());
        return FetchTaskResponse.newBuilder().setTask(builder).build();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
