package com.workflowmanager.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.workflowmanager.engine.application.ScheduleSweeper;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Scheduled workflows end to end (M4, ADR 0011): a registered cron schedule fires a real workflow
 * run, fires exactly once per nominal slot, drops the backlog after an outage, and stops firing
 * while paused. The sweeper is driven directly with an explicit Instant rather than waited on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class ScheduleApiIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate rest;
    @Autowired ScheduleSweeper sweeper;
    @Autowired DSLContext db;

    private static final String EVERY_TEN_MINUTES = "0 */10 * * * *";

    /**
     * A sweep fires every due schedule, which is right in production and ruinous in a shared test
     * database — one test's sweep would otherwise fire every other test's schedule. Parking the
     * schedules left behind by earlier methods leaves each test alone with the one it creates.
     */
    @BeforeEach
    void parkSchedulesFromEarlierTests() {
        db.execute("update workflow_schedules set paused = true");
    }

    @Test
    void create_returnsScheduleWithNextFireTime() {
        JsonNode created = createSchedule("drip-" + UUID.randomUUID(), EVERY_TEN_MINUTES, "UTC");

        assertThat(created.get("cronExpression").asText()).isEqualTo(EVERY_TEN_MINUTES);
        assertThat(created.get("timezone").asText()).isEqualTo("UTC");
        assertThat(created.get("paused").asBoolean()).isFalse();
        // The API omits null fields, so a never-fired schedule has no lastFiredAt at all.
        assertThat(created.has("lastFiredAt")).isFalse();
        assertThat(Instant.parse(created.get("nextFireAt").asText())).isAfter(Instant.now());
    }

    @Test
    void create_rejectsMalformedCronExpression() {
        var response = postSchedule(body("bad-cron-" + UUID.randomUUID(), "not a cron", "UTC"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_rejectsDuplicateName() {
        String name = "dupe-" + UUID.randomUUID();
        createSchedule(name, EVERY_TEN_MINUTES, "UTC");

        var second = postSchedule(body(name, EVERY_TEN_MINUTES, "UTC"));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void sweep_whenDue_submitsRunAndAdvancesSchedule() {
        String name = "fires-" + UUID.randomUUID();
        UUID scheduleId = UUID.fromString(createSchedule(name, EVERY_TEN_MINUTES, "UTC").get("id").asText());
        Instant firstFire = nextFireAt(scheduleId);

        assertThat(sweeper.sweep(firstFire.minusSeconds(1))).as("not due yet").isZero();
        assertThat(runsOf(scheduleId)).isZero();

        assertThat(sweeper.sweep(firstFire)).isEqualTo(1);
        assertThat(runsOf(scheduleId)).isEqualTo(1);
        assertThat(nextFireAt(scheduleId)).isEqualTo(firstFire.plusSeconds(600));
        assertThat(lastFiredAt(scheduleId)).isEqualTo(firstFire);

        // The run is a real workflow instance, tagged with the slot it fired for.
        assertThat(firedForOf(scheduleId)).isEqualTo(firstFire);
    }

    @Test
    void runs_listsWhatTheScheduleStarted() {
        String name = "runs-" + UUID.randomUUID();
        UUID scheduleId = UUID.fromString(createSchedule(name, EVERY_TEN_MINUTES, "UTC").get("id").asText());
        Instant firstFire = nextFireAt(scheduleId);

        assertThat(rest.getForObject("/schedules/" + scheduleId + "/runs", JsonNode.class)).isEmpty();

        sweeper.sweep(firstFire);

        JsonNode runs = rest.getForObject("/schedules/" + scheduleId + "/runs", JsonNode.class);
        assertThat(runs).hasSize(1);
        assertThat(Instant.parse(runs.get(0).get("firedFor").asText())).isEqualTo(firstFire);
        assertThat(runs.get(0).get("workflowInstanceId").asText()).isNotBlank();
    }

    @Test
    void sweep_repeatedAtSameInstant_firesOnlyOnce() {
        String name = "once-" + UUID.randomUUID();
        UUID scheduleId = UUID.fromString(createSchedule(name, EVERY_TEN_MINUTES, "UTC").get("id").asText());
        Instant firstFire = nextFireAt(scheduleId);

        sweeper.sweep(firstFire);
        sweeper.sweep(firstFire);
        sweeper.sweep(firstFire);

        assertThat(runsOf(scheduleId)).as("one nominal slot, one run").isEqualTo(1);
    }

    @Test
    void sweep_afterOutage_firesOnceAndDropsTheBacklog() {
        String name = "outage-" + UUID.randomUUID();
        UUID scheduleId = UUID.fromString(createSchedule(name, EVERY_TEN_MINUTES, "UTC").get("id").asText());
        Instant firstFire = nextFireAt(scheduleId);

        // Six hours of missed grid in a single sweep — the engine was down.
        assertThat(sweeper.sweep(firstFire.plusSeconds(6 * 3600))).isEqualTo(1);

        assertThat(runsOf(scheduleId)).as("backlog dropped, not replayed").isEqualTo(1);
        assertThat(firedForOf(scheduleId))
                .as("fires for the most recent missed slot")
                .isEqualTo(firstFire.plusSeconds(6 * 3600));
    }

    @Test
    void sweep_whilePaused_doesNotFire() {
        String name = "paused-" + UUID.randomUUID();
        UUID scheduleId = UUID.fromString(createSchedule(name, EVERY_TEN_MINUTES, "UTC").get("id").asText());
        Instant firstFire = nextFireAt(scheduleId);

        rest.exchange(
                "/schedules/" + scheduleId + "?paused=true", HttpMethod.PATCH, null, JsonNode.class);

        assertThat(sweeper.sweep(firstFire.plusSeconds(3600))).isZero();
        assertThat(runsOf(scheduleId)).isZero();
    }

    @Test
    void resume_reanchorsNextFireToTheFuture() {
        String name = "resume-" + UUID.randomUUID();
        UUID scheduleId = UUID.fromString(createSchedule(name, EVERY_TEN_MINUTES, "UTC").get("id").asText());

        rest.exchange(
                "/schedules/" + scheduleId + "?paused=true", HttpMethod.PATCH, null, JsonNode.class);
        var resumed =
                rest.exchange(
                        "/schedules/" + scheduleId + "?paused=false",
                        HttpMethod.PATCH,
                        null,
                        JsonNode.class);

        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resumed.getBody().get("paused").asBoolean()).isFalse();
        assertThat(Instant.parse(resumed.getBody().get("nextFireAt").asText())).isAfter(Instant.now());
    }

    @Test
    void delete_removesSchedule() {
        String name = "gone-" + UUID.randomUUID();
        UUID scheduleId = UUID.fromString(createSchedule(name, EVERY_TEN_MINUTES, "UTC").get("id").asText());

        var deleted = rest.exchange("/schedules/" + scheduleId, HttpMethod.DELETE, null, Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var fetched = rest.getForEntity("/schedules/" + scheduleId, JsonNode.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String body(String name, String cron, String timezone) {
        return "{\"name\":\""
                + name
                + "\",\"workflowName\":\""
                + name
                + "\",\"workflowVersion\":1,"
                + "\"cronExpression\":\""
                + cron
                + "\",\"timezone\":\""
                + timezone
                + "\",\"dag\":{\"tasks\":[{\"key\":\"send\",\"type\":\"send-email\"}]}}";
    }

    private org.springframework.http.ResponseEntity<JsonNode> postSchedule(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/schedules", new HttpEntity<>(body, headers), JsonNode.class);
    }

    private JsonNode createSchedule(String name, String cron, String timezone) {
        var created = postSchedule(body(name, cron, timezone));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody();
    }

    private Instant nextFireAt(UUID scheduleId) {
        return db.fetchSingle("select next_fire_at from workflow_schedules where id = ?", scheduleId)
                .get(0, Instant.class);
    }

    private Instant lastFiredAt(UUID scheduleId) {
        return db.fetchSingle("select last_fired_at from workflow_schedules where id = ?", scheduleId)
                .get(0, Instant.class);
    }

    private int runsOf(UUID scheduleId) {
        return db.fetchSingle(
                        "select count(*) from workflow_instances where schedule_id = ?", scheduleId)
                .get(0, Integer.class);
    }

    private Instant firedForOf(UUID scheduleId) {
        return db.fetchSingle(
                        "select fired_for from workflow_instances where schedule_id = ?"
                                + " order by fired_for desc limit 1",
                        scheduleId)
                .get(0, Instant.class);
    }
}
