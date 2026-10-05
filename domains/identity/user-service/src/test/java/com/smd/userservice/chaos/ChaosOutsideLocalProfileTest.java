package com.smd.userservice.chaos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** No {@code local} profile: the chaos endpoint must not exist. */
@WebMvcTest(controllers = ChaosController.class)
class ChaosOutsideLocalProfileTest {

    @Autowired
    MockMvc mvc;

    @Test
    void chaosEndpointDoesNotExist() throws Exception {
        mvc.perform(get("/internal/chaos")).andExpect(status().isNotFound());
        mvc.perform(post("/internal/chaos").contentType(MediaType.APPLICATION_JSON).content("{\"failureRate\": 1.0}"))
                .andExpect(status().isNotFound());
    }
}
