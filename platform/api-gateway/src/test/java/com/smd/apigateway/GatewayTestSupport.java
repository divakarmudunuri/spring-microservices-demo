package com.smd.apigateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The real gateway on a random port, with one WireMock server per downstream service (found through Spring
 * Cloud's simple discovery client instead of Eureka). Each server answers {@code {"service":"<name>"}}, so a test
 * can see which service a path was routed to. cart-service points at a closed port (connection refused);
 * storefront-bff has no instance at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eureka.client.enabled=false")
public abstract class GatewayTestSupport {

    protected static final Map<String, WireMockServer> SERVICES = new LinkedHashMap<>();

    static {
        for (String name : new String[]{"user-service", "product-service", "order-service", "shipping-service",
                "order-tracking-service"}) {
            WireMockServer server = new WireMockServer(options().dynamicPort());
            server.start();
            SERVICES.put(name, server);
        }
    }

    @DynamicPropertySource
    static void instances(DynamicPropertyRegistry registry) {
        SERVICES.forEach((name, server) ->
                registry.add("spring.cloud.discovery.client.simple.instances." + name + "[0].uri", server::baseUrl));
        registry.add("spring.cloud.discovery.client.simple.instances.cart-service[0].uri", () -> "http://localhost:1");
    }

    @Autowired
    protected WebTestClient client;

    @BeforeEach
    void echoServiceName() {
        SERVICES.forEach((name, server) -> {
            server.resetAll();
            server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json").withBody("{\"service\":\"" + name + "\"}")));
        });
    }

    protected static WireMockServer service(String name) {
        return SERVICES.get(name);
    }
}
