package com.workflowmanager.worker.sample;

import com.workflowmanager.worker.TaskHandler;
import com.workflowmanager.worker.WorkerRuntime;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The demo worker: a toy "echo" handler (sleep 1s, echo the input back) and a "sleep" handler
 * (input {@code {"seconds": n}}) long enough to survive lease renewals — or to be killed
 * mid-task for the M2 failover demo. Entry point for the {@code worker} docker-compose service.
 */
public final class SampleWorker {

    private static final Pattern SECONDS = Pattern.compile("\"seconds\"\\s*:\\s*(\\d+)");

    private SampleWorker() {}

    public static void main(String[] args) throws InterruptedException {
        String target = env("ENGINE_GRPC_TARGET", "localhost:9090");
        // HOSTNAME is the container hostname, so `docker compose up --scale worker=N`
        // yields N distinct worker ids without any per-container config.
        String workerId = env("WORKER_ID", env("HOSTNAME", "sample-worker-" + UUID.randomUUID()));

        TaskHandler echo =
                input -> {
                    sleep(Duration.ofSeconds(1));
                    String payload = (input == null || input.isBlank()) ? "null" : input;
                    return "{\"echoed\":" + payload + "}";
                };

        TaskHandler sleep =
                input -> {
                    long seconds = parseSeconds(input);
                    sleep(Duration.ofSeconds(seconds));
                    return "{\"slept\":" + seconds + "}";
                };

        WorkerRuntime runtime =
                new WorkerRuntime(target, workerId, Map.of("echo", echo, "sleep", sleep));
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::close));
        runtime.start();
        Thread.currentThread().join();
    }

    private static long parseSeconds(String input) {
        if (input == null) {
            return 0;
        }
        Matcher matcher = SECONDS.matcher(input);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            // Lease revoked (or shutdown): stop the handler instead of finishing stale work.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
