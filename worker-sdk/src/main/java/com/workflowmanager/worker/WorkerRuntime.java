package com.workflowmanager.worker;

import com.workflowmanager.protocol.v1.CompleteTaskRequest;
import com.workflowmanager.protocol.v1.Failure;
import com.workflowmanager.protocol.v1.FetchTaskRequest;
import com.workflowmanager.protocol.v1.FetchTaskResponse;
import com.workflowmanager.protocol.v1.Success;
import com.workflowmanager.protocol.v1.Task;
import com.workflowmanager.protocol.v1.WorkerServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connects a set of {@link TaskHandler}s to the engine over gRPC. The loop long-polls FetchTask,
 * runs the matching handler, and reports the outcome with CompleteTask.
 *
 * <p>Delivery is at-least-once: a handler may run more than once for the same task, so handlers
 * must be idempotent (see {@link TaskHandler}).
 */
public final class WorkerRuntime implements AutoCloseable {

    private static final Logger log = Logger.getLogger(WorkerRuntime.class.getName());
    private static final long FETCH_DEADLINE_SECONDS = 35; // above the engine's ~25s long-poll window

    private final String workerId;
    private final Map<String, TaskHandler> handlers;
    private final List<String> capabilities;
    private final ManagedChannel channel;
    private final WorkerServiceGrpc.WorkerServiceBlockingStub stub;

    private volatile boolean running;
    private Thread loop;

    public WorkerRuntime(String engineTarget, String workerId, Map<String, TaskHandler> handlers) {
        this.workerId = workerId;
        this.handlers = Map.copyOf(handlers);
        this.capabilities = List.copyOf(handlers.keySet());
        this.channel = ManagedChannelBuilder.forTarget(engineTarget).usePlaintext().build();
        this.stub = WorkerServiceGrpc.newBlockingStub(channel);
    }

    public void start() {
        running = true;
        loop = Thread.ofVirtual().name("worker-loop").start(this::runLoop);
        log.info(() -> "worker " + workerId + " started, capabilities=" + capabilities);
    }

    private void runLoop() {
        FetchTaskRequest request =
                FetchTaskRequest.newBuilder()
                        .setWorkerId(workerId)
                        .addAllCapabilities(capabilities)
                        .build();
        while (running) {
            try {
                FetchTaskResponse response =
                        stub.withDeadlineAfter(FETCH_DEADLINE_SECONDS, TimeUnit.SECONDS).fetchTask(request);
                if (response.hasTask()) {
                    handle(response.getTask());
                }
            } catch (StatusRuntimeException e) {
                if (running) {
                    log.log(Level.FINE, "fetch failed, retrying", e);
                    sleep();
                }
            }
        }
    }

    private void handle(Task task) {
        TaskHandler handler = handlers.get(task.getType());
        if (handler == null) {
            report(task, false, null, "no handler for type " + task.getType());
            return;
        }
        try {
            String output = handler.handle(task.getInput());
            report(task, true, output, null);
        } catch (Exception e) {
            report(task, false, null, String.valueOf(e.getMessage()));
        }
    }

    private void report(Task task, boolean success, String output, String error) {
        CompleteTaskRequest.Builder request =
                CompleteTaskRequest.newBuilder().setTaskId(task.getTaskId()).setWorkerId(workerId);
        if (success) {
            request.setSuccess(Success.newBuilder().setOutput(output == null ? "" : output));
        } else {
            request.setFailure(Failure.newBuilder().setErrorMessage(error == null ? "" : error));
        }
        try {
            stub.completeTask(request.build());
        } catch (StatusRuntimeException e) {
            // At-least-once: if the ack is lost the engine redelivers on lease expiry (M2).
            log.log(Level.WARNING, "completeTask failed for task " + task.getTaskId(), e);
        }
    }

    private void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running = false;
        if (loop != null) {
            loop.interrupt();
        }
        channel.shutdownNow();
    }
}
