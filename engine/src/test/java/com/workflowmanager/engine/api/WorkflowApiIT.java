package com.workflowmanager.engine.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WorkflowApiIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate rest;

    private HttpEntity<String> jsonBody(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void submit_validSingleTask_thenGet_showsPendingReadyTask() {
        String body =
                "{\"name\":\"demo\",\"version\":1,\"dag\":{\"tasks\":"
                        + "[{\"key\":\"step1\",\"type\":\"echo\",\"input\":{\"msg\":\"hi\"}}]}}";

        var created = rest.postForEntity("/workflows", jsonBody(body), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String instanceId = created.getBody().get("instanceId").asText();

        var view = rest.getForEntity("/workflows/" + instanceId, JsonNode.class);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(view.getBody().get("status").asText()).isEqualTo("PENDING");
        assertThat(view.getBody().get("tasks").get(0).get("status").asText()).isEqualTo("READY");
        assertThat(view.getBody().get("tasks").get(0).get("type").asText()).isEqualTo("echo");
    }

    @Test
    void submit_emptyTasks_returnsBadRequest() {
        String body = "{\"name\":\"demo\",\"version\":2,\"dag\":{\"tasks\":[]}}";
        var resp = rest.postForEntity("/workflows", jsonBody(body), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void get_unknownWorkflow_returnsNotFound() {
        var resp =
                rest.getForEntity(
                        "/workflows/00000000-0000-0000-0000-000000000000", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
