package com.smd.userservice.api;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smd.userservice.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class UserApiTest extends PostgresIntegrationTest {

    static final String SAMPLE_CUSTOMER = "00000000-0000-4000-8000-0000000000c1";
    static final String SAMPLE_ADMIN = "00000000-0000-4000-8000-0000000000a1";

    @Autowired
    MockMvc mvc;

    @Test
    void customerComesWithDefaultAddress() throws Exception {
        mvc.perform(get("/api/users/{id}", SAMPLE_CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("customer@demo.local"))
                .andExpect(jsonPath("$.fullName").value("Demo Customer"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.defaultAddress.city").value("Detroit"))
                .andExpect(jsonPath("$.defaultAddress.country").value("US"));
    }

    @Test
    void userWithoutAddressHasNullDefaultAddress() throws Exception {
        mvc.perform(get("/api/users/{id}", SAMPLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.defaultAddress").value(nullValue()));
    }

    @Test
    void unknownUserIsNotFoundProblem() throws Exception {
        mvc.perform(get("/api/users/{id}", "00000000-0000-4000-8000-00000000ffff"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/user-not-found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void malformedIdIsBadRequest() throws Exception {
        mvc.perform(get("/api/users/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
