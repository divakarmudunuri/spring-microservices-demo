package com.smd.discoveryserver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DiscoveryServerTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void registryIsServedAndEmpty() {
        var response = rest.getForEntity("/eureka/apps", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // self-registration is disabled, so the registry starts empty
        assertThat(response.getBody()).doesNotContain("DISCOVERY-SERVER");
    }

    @Test
    void dashboardIsServed() {
        assertThat(rest.getForEntity("/", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
