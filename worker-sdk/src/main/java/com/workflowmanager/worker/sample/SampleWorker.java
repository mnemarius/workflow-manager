package com.workflowmanager.worker.sample;

import com.workflowmanager.worker.TaskHandler;
import com.workflowmanager.worker.WorkerRuntime;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The demo worker. Two foundational toy handlers — {@code echo} (sleep 1s, echo the input back)
 * and {@code sleep} (input {@code {"seconds": n}}) long enough to survive lease renewals, or to be
 * killed mid-task for the M2 failover demo — plus the M3 order-fulfillment handlers that drive the
 * reference diamond DAG {@code validate -> {charge-payment, reserve-inventory} -> ship -> notify}:
 *
 * <ul>
 *   <li>{@code validate} — validate the order, {@code {"valid":true}}.
 *   <li>{@code charge-payment} — the <strong>legitimately flaky</strong> step: throws ~40% of the
 *       time so the demo exercises real retries, else {@code {"charged":true}}.
 *   <li>{@code reserve-inventory} — reserve stock, {@code {"reserved":true}}.
 *   <li>{@code ship} — the fan-in join (waits on charge + reserve), {@code {"tracking":"..."}}.
 *   <li>{@code notify} — the sole sink, {@code {"notified":true}}.
 * </ul>
 *
 * One worker registers every handler; its capabilities auto-derive from the handler-map keys, so a
 * single {@code worker} container (scale it freely) can run every task type. Entry point for the
 * {@code worker} docker-compose service.
 */
public final class SampleWorker {

    private static final Pattern SECONDS = Pattern.compile("\"seconds\"\\s*:\\s*(\\d+)");

    /** Probability the flaky {@code charge-payment} handler fails an attempt (retries recover it). */
    private static final double PAYMENT_FAILURE_RATE = 0.4;

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
                new WorkerRuntime(
                        target,
                        workerId,
                        Map.of(
                                "echo", echo,
                                "sleep", sleep,
                                "validate", validateHandler(),
                                "charge-payment", chargePaymentHandler(),
                                "reserve-inventory", reserveInventoryHandler(),
                                "ship", shipHandler(),
                                "notify", notifyHandler()));
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::close));
        runtime.start();
        Thread.currentThread().join();
    }

    // --- Order-fulfillment handlers (M3 reference demo) ------------------------------------------
    // Each returns a small hand-rolled JSON object (matching this file's no-extra-deps style),
    // sleeps briefly for a realistic cadence, honors interruption via the shared sleep() helper,
    // and is idempotent — re-running yields the same result (charge-payment aside, whose retries
    // are the whole point).

    private static TaskHandler validateHandler() {
        return input -> {
            sleep(Duration.ofMillis(500));
            return "{\"valid\":true}";
        };
    }

    /**
     * The legitimately flaky step: fails ~{@value #PAYMENT_FAILURE_RATE} of the time so the demo
     * shows a real retry story. On success the charge is idempotent — a retried attempt charges the
     * same order once and returns the same {@code {"charged":true}}.
     */
    private static TaskHandler chargePaymentHandler() {
        return input -> {
            sleep(Duration.ofMillis(500));
            if (ThreadLocalRandom.current().nextDouble() < PAYMENT_FAILURE_RATE) {
                throw new IllegalStateException("payment gateway declined (transient)");
            }
            return "{\"charged\":true}";
        };
    }

    private static TaskHandler reserveInventoryHandler() {
        return input -> {
            sleep(Duration.ofMillis(500));
            return "{\"reserved\":true}";
        };
    }

    private static TaskHandler shipHandler() {
        return input -> {
            sleep(Duration.ofMillis(500));
            return "{\"tracking\":\"1Z" + UUID.randomUUID().toString().substring(0, 12) + "\"}";
        };
    }

    private static TaskHandler notifyHandler() {
        return input -> {
            sleep(Duration.ofMillis(500));
            return "{\"notified\":true}";
        };
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
