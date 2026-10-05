package com.smd.productservice.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smd.productservice.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class ChaosToggleTest extends PostgresIntegrationTest {

    static final String USER_URL = "/api/products/mechanical-keyboard";

    @Autowired
    MockMvc mvc;

    @AfterEach
    void resetChaos() throws Exception {
        setChaos("{\"latencyMs\": 0, \"failureRate\": 0.0}");
    }

    @Test
    void failureRateOneFailsEveryApiCall() throws Exception {
        setChaos("{\"failureRate\": 1.0}");

        mvc.perform(get(USER_URL))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("/problems/chaos-failure"));
    }

    @Test
    void latencySlowsApiCalls() throws Exception {
        setChaos("{\"latencyMs\": 300}");

        long start = System.nanoTime();
        mvc.perform(get(USER_URL)).andExpect(status().isOk());

        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(300);
    }

    @Test
    void chaosDoesNotTouchNonApiPaths() throws Exception {
        setChaos("{\"failureRate\": 1.0}");

        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/internal/chaos")).andExpect(status().isOk());
    }

    @Test
    void leftOutFieldKeepsItsValue() throws Exception {
        setChaos("{\"latencyMs\": 5}");
        setChaos("{\"failureRate\": 0.25}");

        mvc.perform(get("/internal/chaos"))
                .andExpect(jsonPath("$.latencyMs").value(5))
                .andExpect(jsonPath("$.failureRate").value(0.25));
    }

    @Test
    void invalidValuesAreRejected() throws Exception {
        mvc.perform(post("/internal/chaos").contentType(MediaType.APPLICATION_JSON).content("{\"failureRate\": 1.5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/chaos").contentType(MediaType.APPLICATION_JSON).content("{\"latencyMs\": -1}"))
                .andExpect(status().isBadRequest());
    }

    private void setChaos(String json) throws Exception {
        mvc.perform(post("/internal/chaos").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }
}
