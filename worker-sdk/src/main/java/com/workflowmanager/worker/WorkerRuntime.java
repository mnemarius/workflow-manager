package com.workflowmanager.worker;

import com.workflowmanager.protocol.v1.CompleteTaskRequest;
import com.workflowmanager.protocol.v1.Failure;
import com.workflowmanager.protocol.v1.FetchTaskRequest;
import com.workflowmanager.protocol.v1.FetchTaskResponse;
import com.workflowmanager.protocol.v1.HeartbeatRequest;
import com.workflowmanager.protocol.v1.HeartbeatResponse;
import com.workflowmanager.protocol.v1.Success;
import com.workflowmanager.protocol.v1.Task;
import com.workflowmanager.protocol.v1.WorkerServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connects a set of {@link TaskHandler}s to the engine over gRPC. The loop long-polls FetchTask,
 * runs the matching handler on its own virtual thread while heartbeating the lease, and reports
 * the outcome with CompleteTask. If the engine says the lease is lost, the handler is interrupted
 * and no completion is reported.
 *
 * <p>Delivery is at-least-once: a handler may run more than once for the same task, so handlers
 * must be idempotent (see {@link TaskHandler}).
 */
public final class WorkerRuntime implements AutoCloseable {

    private static final Logger log = Logger.getLogger(WorkerRuntime.class.getName());
    private static final long FETCH_DEADLINE_SECONDS = 35; // above the engine's ~25s long-poll window
    private static final Duration MIN_HEARTBEAT_WAIT = Duration.ofMillis(100);

    private final String workerId;
    private final Map<String, TaskHandler> handlers;
    private final List<String> capabilities;
    private final ManagedChannel channel;
    private final WorkerServiceGrpc.WorkerServiceBlockingStub stub;

    private volatile boolean running;
    private Thread loop;

    public WorkerRuntime(String engineTarget, String workerId, Map<String, TaskHandler> handlers) {
        this(
                ManagedChannelBuilder.forTarget(engineTarget).usePlaintext().build(),
                workerId,
                handlers);
    }

    public WorkerRuntime(ManagedChannel channel, String workerId, Map<String, TaskHandler> handlers) {
        this.workerId = workerId;
        this.handlers = Map.copyOf(handlers);
        this.capabilities = List.copyOf(handlers.keySet());
        this.channel = channel;
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

        var done = new CountDownLatch(1);
        var output = new AtomicReference<String>();
        var failure = new AtomicReference<Exception>();
        Thread handlerThread =
                Thread.ofVirtual()
                        .name("worker-handler")
                        .start(
                                () -> {
                                    try {
                                        output.set(handler.handle(task.getInput()));
                                    } catch (Exception e) {
                                        failure.set(e);
                                    } finally {
                                        done.countDown();
                                    }
                                });

        if (!heartbeatUntilDone(task, done, handlerThread)) {
            return; // lease lost: handler interrupted, completion suppressed (another worker owns it)
        }

        if (failure.get() != null) {
            report(task, false, null, String.valueOf(failure.get().getMessage()));
        } else {
            report(task, true, output.get(), null);
        }
    }

    /** @return true when the handler finished while the lease was still held. */
    private boolean heartbeatUntilDone(Task task, CountDownLatch done, Thread handlerThread) {
        Instant leaseExpiresAt = Instant.parse(task.getLeaseExpiresAt());
        HeartbeatRequest request =
                HeartbeatRequest.newBuilder().setTaskId(task.getTaskId()).setWorkerId(workerId).build();
        try {
            while (!done.await(heartbeatWaitMillis(leaseExpiresAt), TimeUnit.MILLISECONDS)) {
                try {
                    HeartbeatResponse response = stub.heartbeat(request);
                    if (response.hasLost()) {
                        log.warning(
                                () ->
                                        "lease lost for task "
                                                + task.getTaskId()
                                                + ": "
                                                + response.getLost().getReason());
                        handlerThread.interrupt();
                        return false;
                    }
                    leaseExpiresAt = Instant.parse(response.getRenewed().getLeaseExpiresAt());
                } catch (StatusRuntimeException e) {
                    if (!Instant.now().isBefore(leaseExpiresAt)) {
                        log.log(Level.WARNING, "heartbeat failing past lease expiry, abandoning task", e);
                        handlerThread.interrupt();
                        return false;
                    }
                    log.log(Level.FINE, "heartbeat failed, lease still live, retrying", e);
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handlerThread.interrupt();
            return false;
        }
    }

    private static long heartbeatWaitMillis(Instant leaseExpiresAt) {
        long third = Duration.between(Instant.now(), leaseExpiresAt).toMillis() / 3;
        return Math.max(third, MIN_HEARTBEAT_WAIT.toMillis());
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
            // At-least-once: if the ack is lost the engine redelivers on lease expiry.
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
