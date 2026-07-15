package com.workflowmanager.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.workflowmanager.protocol.v1.CompleteTaskRequest;
import com.workflowmanager.protocol.v1.CompleteTaskResponse;
import com.workflowmanager.protocol.v1.FetchTaskRequest;
import com.workflowmanager.protocol.v1.FetchTaskResponse;
import com.workflowmanager.protocol.v1.HeartbeatRequest;
import com.workflowmanager.protocol.v1.HeartbeatResponse;
import com.workflowmanager.protocol.v1.LeaseLost;
import com.workflowmanager.protocol.v1.LeaseRenewed;
import com.workflowmanager.protocol.v1.NoTask;
import com.workflowmanager.protocol.v1.Task;
import com.workflowmanager.protocol.v1.WorkerServiceGrpc;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkerRuntimeTest {

    private static final Duration LEASE = Duration.ofMillis(600);

    private Server server;
    private WorkerRuntime runtime;

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    @Test
    void handle_whenHandlerOutlivesLease_heartbeatsRenewThenCompletes() throws Exception {
        FakeWorkerService service = new FakeWorkerService();
        service.offerTask("block");
        runtime = startRuntime(service, "block", input -> {
            awaitUninterruptibly(service.twoHeartbeatsSeen);
            return "{\"ok\":true}";
        });

        CompleteTaskRequest completion = service.completions.poll(10, TimeUnit.SECONDS);

        assertThat(completion).isNotNull();
        assertThat(completion.hasSuccess()).isTrue();
        assertThat(completion.getSuccess().getOutput()).isEqualTo("{\"ok\":true}");
        assertThat(service.heartbeats.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void handle_whenHeartbeatReportsLeaseLost_interruptsHandlerAndSuppressesCompletion()
            throws Exception {
        FakeWorkerService service = new FakeWorkerService();
        service.loseLease = true;
        service.offerTask("block");
        CountDownLatch interrupted = new CountDownLatch(1);
        runtime = startRuntime(service, "block", input -> {
            try {
                new CountDownLatch(1).await(); // blocks until the runtime interrupts us
                return "unreachable";
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        });

        assertThat(interrupted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(service.completions.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    private WorkerRuntime startRuntime(FakeWorkerService service, String type, TaskHandler handler)
            throws IOException {
        String name = InProcessServerBuilder.generateName();
        server =
                InProcessServerBuilder.forName(name)
                        .executor(Executors.newVirtualThreadPerTaskExecutor())
                        .addService(service)
                        .build()
                        .start();
        WorkerRuntime workerRuntime =
                new WorkerRuntime(
                        InProcessChannelBuilder.forName(name).build(),
                        "test-worker",
                        Map.of(type, handler));
        workerRuntime.start();
        return workerRuntime;
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class FakeWorkerService extends WorkerServiceGrpc.WorkerServiceImplBase {

        final BlockingQueue<Task> tasks = new LinkedBlockingQueue<>();
        final BlockingQueue<CompleteTaskRequest> completions = new LinkedBlockingQueue<>();
        final AtomicInteger heartbeats = new AtomicInteger();
        final CountDownLatch twoHeartbeatsSeen = new CountDownLatch(2);
        volatile boolean loseLease;

        void offerTask(String type) {
            tasks.add(
                    Task.newBuilder()
                            .setTaskId("11111111-1111-1111-1111-111111111111")
                            .setTaskKey("step1")
                            .setType(type)
                            .setInput("{}")
                            .setAttempt(1)
                            .setLeaseExpiresAt(Instant.now().plus(LEASE).toString())
                            .build());
        }

        @Override
        public void fetchTask(FetchTaskRequest request, StreamObserver<FetchTaskResponse> observer) {
            Task task;
            try {
                task = tasks.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                task = null;
            }
            FetchTaskResponse response =
                    task == null
                            ? FetchTaskResponse.newBuilder().setNoTask(NoTask.getDefaultInstance()).build()
                            : FetchTaskResponse.newBuilder().setTask(task).build();
            observer.onNext(response);
            observer.onCompleted();
        }

        @Override
        public void heartbeat(HeartbeatRequest request, StreamObserver<HeartbeatResponse> observer) {
            heartbeats.incrementAndGet();
            twoHeartbeatsSeen.countDown();
            HeartbeatResponse response =
                    loseLease
                            ? HeartbeatResponse.newBuilder()
                                    .setLost(LeaseLost.newBuilder().setReason("lease expired"))
                                    .build()
                            : HeartbeatResponse.newBuilder()
                                    .setRenewed(
                                            LeaseRenewed.newBuilder()
                                                    .setLeaseExpiresAt(Instant.now().plus(LEASE).toString()))
                                    .build();
            observer.onNext(response);
            observer.onCompleted();
        }

        @Override
        public void completeTask(
                CompleteTaskRequest request, StreamObserver<CompleteTaskResponse> observer) {
            completions.add(request);
            observer.onNext(CompleteTaskResponse.newBuilder().setAccepted(true).build());
            observer.onCompleted();
        }
    }
}
