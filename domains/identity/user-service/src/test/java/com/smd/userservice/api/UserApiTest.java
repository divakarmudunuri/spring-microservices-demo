package com.smd.userservice.api;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smd.userservice.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/** Roles and ownership on user-service's own endpoints (internal JWTs, simulated with jwt()). */
@Transactional   // MockMvc runs in the test thread: the address change is rolled back after each test
class UserApiTest extends PostgresIntegrationTest {

    static final String SAMPLE_CUSTOMER = "00000000-0000-4000-8000-0000000000c1";
    static final String SAMPLE_ADMIN = "00000000-0000-4000-8000-0000000000a1";
    static final String OTHER_CUSTOMER = "00000000-0000-4000-8000-0000000000c2";

    @Autowired
    MockMvc mvc;

    @Test
    void customerReadsTheirOwnProfileWithAddress() throws Exception {
        mvc.perform(get("/api/users/me").with(customer(SAMPLE_CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("customer@demo.local"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.defaultAddress.city").value("Detroit"));
    }

    @Test
    void customerSavesTheirAddress() throws Exception {
        mvc.perform(put("/api/users/me/address").with(customer(SAMPLE_CUSTOMER)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Demo Customer","line1":"1 Woodward Ave","city":"Detroit","state":"MI",
                                 "postalCode":"48226","country":"us"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultAddress.line1").value("1 Woodward Ave"))
                .andExpect(jsonPath("$.defaultAddress.country").value("US"));
        mvc.perform(put("/api/users/me/address").with(customer(SAMPLE_CUSTOMER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"x\",\"country\":\"USA\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminReadsTheirProfileOnTheAdminPath() throws Exception {
        mvc.perform(get("/api/admin/me").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.defaultAddress").value(nullValue()));
    }

    @Test
    void rolesAreEnforced() throws Exception {
        mvc.perform(get("/api/admin/me").with(customer(SAMPLE_CUSTOMER))).andExpect(status().isForbidden());
        mvc.perform(get("/api/users/me").with(admin())).andExpect(status().isForbidden());
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void getUserByIdIsForTheOwnerOrAnAdmin() throws Exception {
        mvc.perform(get("/api/users/{id}", SAMPLE_CUSTOMER).with(customer(SAMPLE_CUSTOMER))).andExpect(status().isOk());
        mvc.perform(get("/api/users/{id}", SAMPLE_CUSTOMER).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Demo Customer"));
        // someone else's id is "not found", not "forbidden": ids can't be probed
        mvc.perform(get("/api/users/{id}", SAMPLE_CUSTOMER).with(customer(OTHER_CUSTOMER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/user-not-found"));
        mvc.perform(get("/api/users/{id}", SAMPLE_CUSTOMER)).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownUserIsNotFoundForAnAdmin() throws Exception {
        mvc.perform(get("/api/users/{id}", "00000000-0000-4000-8000-00000000ffff").with(admin()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/users/{id}", "not-a-uuid").with(admin())).andExpect(status().isBadRequest());
    }

    static RequestPostProcessor customer(String id) {
        return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(SAMPLE_ADMIN)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
