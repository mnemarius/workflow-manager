package com.workflowmanager.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TaskHandlerTest {

    @Test
    void handle_givenInput_returnsResult() {
        TaskHandler echo = input -> "handled:" + input;

        assertThat(echo.handle("ping")).isEqualTo("handled:ping");
    }
}
