package com.smd.storefrontbff;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The BFF with a WireMock product-service; short timeouts and a small circuit-breaker window. */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.openfeign.client.config.product-service.read-timeout=500",
        "resilience4j.retry.instances.productService.wait-duration=10ms",
        "resilience4j.circuitbreaker.instances.productService.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.productService.minimum-number-of-calls=4"
})
@AutoConfigureMockMvc
public abstract class BffIntegrationTest {

    protected static final WireMockServer PRODUCT_SERVICE = new WireMockServer(options().dynamicPort());

    static {
        PRODUCT_SERVICE.start();
    }

    @DynamicPropertySource
    static void productService(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[0].uri", PRODUCT_SERVICE::baseUrl);
    }

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    @BeforeEach
    void stubCatalog() {
        PRODUCT_SERVICE.resetAll();
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        stubCategories(json(CATEGORIES));
        stubFeatured(json(page(EARBUDS, KEYBOARD)));
        stubNewest(json(page(MONITOR, EARBUDS)));
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo("/api/products/mechanical-keyboard")).willReturn(json(KEYBOARD)));
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo("/api/products")).withQueryParam("category", equalTo("electronics"))
                .willReturn(json(page(EARBUDS, KEYBOARD, MONITOR))));
    }

    protected static void stubCategories(ResponseDefinitionBuilder response) {
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo("/api/categories")).willReturn(response));
    }

    protected static void stubFeatured(ResponseDefinitionBuilder response) {
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo("/api/products")).withQueryParam("featured", equalTo("true")).willReturn(response));
    }

    protected static void stubNewest(ResponseDefinitionBuilder response) {
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo("/api/products")).withQueryParam("sort", equalTo("newest")).willReturn(response));
    }

    protected static ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    protected static String page(String... products) {
        return "{\"content\":[" + String.join(",", products) + "],\"page\":0,\"size\":8,\"totalElements\":" + products.length + "}";
    }

    protected static final String CATEGORIES = """
            [{"id":"10000000-0000-4000-8000-000000000001","slug":"electronics","name":"Electronics"},
             {"id":"10000000-0000-4000-8000-000000000002","slug":"home-kitchen","name":"Home & Kitchen"}]""";

    protected static final String EARBUDS = product("20000000-0000-4000-8000-000000000001", "wireless-earbuds", "Wireless Earbuds", "79.99", "IN_STOCK");
    protected static final String KEYBOARD = product("20000000-0000-4000-8000-000000000003", "mechanical-keyboard", "Mechanical Keyboard", "129.00", "LOW_STOCK");
    protected static final String MONITOR = product("20000000-0000-4000-8000-000000000004", "monitor-27-4k", "27-inch 4K Monitor", "349.00", "OUT_OF_STOCK");

    private static String product(String id, String slug, String name, String price, String availability) {
        return """
                {"id":"%s","slug":"%s","name":"%s","description":"...","category":{"slug":"electronics","name":"Electronics"},
                 "imageUrl":"/products/%s.svg","price":%s,"currency":"USD","featured":true,"availability":"%s"}"""
                .formatted(id, slug, name, slug, price, availability);
    }
}
