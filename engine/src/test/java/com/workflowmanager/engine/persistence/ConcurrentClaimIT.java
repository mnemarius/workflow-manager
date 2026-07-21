package com.workflowmanager.engine.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** SKIP LOCKED proof (ADR 0001): two racing claim transactions, exactly one gets the task. */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ConcurrentClaimIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired WorkflowRepository repo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired Clock clock;

    @Test
    void claimReadyTask_twoConcurrentTransactions_exactlyOneWins() throws Exception {
        Instant now = clock.instant();
        UUID defId = repo.upsertDefinition("race", 1, "{\"tasks\":[{\"key\":\"step1\"}]}");
        UUID instanceId = repo.insertInstance(defId, null, now);
        repo.insertReadyTask(instanceId, "step1", "race", null, 3, null, null, now, now);

        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch bothTried = new CountDownLatch(2);
        Callable<Boolean> claimer =
                () ->
                        tx.execute(
                                status -> {
                                    var claimed = repo.claimReadyTask(List.of("race"), now);
                                    bothTried.countDown();
                                    // Hold the row lock until the other transaction has run its claim.
                                    awaitQuietly(bothTried);
                                    claimed.ifPresent(
                                            c ->
                                                    repo.markTaskRunning(
                                                            c.taskId(), "racer", now, now.plusSeconds(30)));
                                    return claimed.isPresent();
                                });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = pool.invokeAll(List.of(claimer, claimer));
            long winners = 0;
            for (Future<Boolean> result : results) {
                if (Boolean.TRUE.equals(result.get(30, TimeUnit.SECONDS))) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(repo.findTasks(instanceId).get(0).attempts()).isEqualTo(1);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
