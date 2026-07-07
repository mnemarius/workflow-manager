package com.workflowmanager.engine.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Owns the gRPC server lifecycle, tied to the Spring context. Calls are served on a
 * virtual-thread executor so a held-open FetchTask long poll costs almost nothing.
 */
@Component
public class GrpcServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServer.class);

    private final int port;
    private final WorkerServiceImpl workerService;
    private Server server;
    private volatile boolean running;

    public GrpcServer(
            @Value("${grpc.server.port:9090}") int port, WorkerServiceImpl workerService) {
        this.port = port;
        this.workerService = workerService;
    }

    @Override
    public void start() {
        server =
                ServerBuilder.forPort(port)
                        .executor(Executors.newVirtualThreadPerTaskExecutor())
                        .addService(workerService)
                        .build();
        try {
            server.start();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start gRPC server on port " + port, e);
        }
        running = true;
        log.info("gRPC worker service listening on port {}", port);
    }

    @Override
    public void stop() {
        if (server != null) {
            try {
                server.shutdown().awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** The actual bound port (resolves the ephemeral port when configured with 0, e.g. in tests). */
    public int port() {
        return server == null ? port : server.getPort();
    }
}
