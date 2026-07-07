package com.workflowmanager.worker.sample;

import com.workflowmanager.worker.TaskHandler;
import com.workflowmanager.worker.WorkerRuntime;
import java.util.Map;
import java.util.UUID;

/**
 * The M1 demo worker: registers a toy "echo" handler (sleep 1s, echo the input back) and runs the
 * long-poll loop against the engine. Entry point for the {@code worker} docker-compose service.
 */
public final class SampleWorker {

    private SampleWorker() {}

    public static void main(String[] args) throws InterruptedException {
        String target = env("ENGINE_GRPC_TARGET", "localhost:9090");
        String workerId = env("WORKER_ID", "sample-worker-" + UUID.randomUUID());

        TaskHandler echo =
                input -> {
                    sleep(1000);
                    String payload = (input == null || input.isBlank()) ? "null" : input;
                    return "{\"echoed\":" + payload + "}";
                };

        WorkerRuntime runtime = new WorkerRuntime(target, workerId, Map.of("echo", echo));
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::close));
        runtime.start();
        Thread.currentThread().join();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

