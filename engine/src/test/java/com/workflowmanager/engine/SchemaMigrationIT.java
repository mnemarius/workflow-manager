package com.workflowmanager.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class SchemaMigrationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    DSLContext db;

    @Test
    void flyway_onStartup_createsEveryTable() {
        var tables = db.meta().getTables().stream().map(t -> t.getName()).toList();

        assertThat(tables).contains(
                "workflow_definitions",
                "workflow_instances",
                "task_instances",
                "task_dependencies",
                "task_leases",
                "workflow_schedules",
                "events",
                "outbox");
    }
}
