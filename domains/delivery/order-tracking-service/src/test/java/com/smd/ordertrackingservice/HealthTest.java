package com.smd.ordertrackingservice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@TestPropertySource(properties = "management.endpoint.health.show-details=always")
class HealthTest extends TrackingIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void healthIncludesTheTrackingTable() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.trackingTable.status").value("UP"))
                .andExpect(jsonPath("$.components.trackingTable.details.status").value("ACTIVE"));
    }
}
