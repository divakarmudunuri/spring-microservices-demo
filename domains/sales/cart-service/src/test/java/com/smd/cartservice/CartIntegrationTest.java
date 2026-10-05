package com.smd.cartservice;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * DynamoDB Local, Kafka and a WireMock product-service (found through Spring Cloud's simple discovery client).
 * The line limit is 3 here, to test it without dozens of products. Tests use fresh carts and users.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "cart.dynamodb.create-table=true",
        "cart.max-lines=3",
        "spring.cloud.openfeign.client.config.product-service.read-timeout=300",
        "resilience4j.retry.instances.productService.wait-duration=10ms",
        "resilience4j.circuitbreaker.instances.productService.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.productService.minimum-number-of-calls=4"
})
@AutoConfigureMockMvc
public abstract class CartIntegrationTest {

    protected static final UUID EARBUDS = UUID.fromString("20000000-0000-4000-8000-000000000001");   // IN_STOCK 79.99
    protected static final UUID CHARGER = UUID.fromString("20000000-0000-4000-8000-000000000002");   // LOW_STOCK 39.99
    protected static final UUID KEYBOARD = UUID.fromString("20000000-0000-4000-8000-000000000003");  // IN_STOCK 129.00
    protected static final UUID MONITOR = UUID.fromString("20000000-0000-4000-8000-000000000004");   // OUT_OF_STOCK
    protected static final UUID BOTTLE = UUID.fromString("20000000-0000-4000-8000-000000000009");    // IN_STOCK 27.50

    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    protected static final GenericContainer<?> DYNAMODB = new GenericContainer<>("amazon/dynamodb-local:3.3.1")
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
            .withExposedPorts(8000);

    protected static final WireMockServer PRODUCT_SERVICE = new WireMockServer(options().dynamicPort());

    static {
        KAFKA.start();
        DYNAMODB.start();
        PRODUCT_SERVICE.start();
    }

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("cart.dynamodb.endpoint", () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[0].uri", PRODUCT_SERVICE::baseUrl);
    }

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    @BeforeEach
    void stubCatalog() {
        PRODUCT_SERVICE.resetAll();
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo("/api/products")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(CATALOG)));
    }

    protected static final String CATALOG = """
            [{"id":"%s","name":"Wireless Earbuds","slug":"wireless-earbuds","imageUrl":"/products/wireless-earbuds.svg","price":79.99,"currency":"USD","availability":"IN_STOCK"},
             {"id":"%s","name":"USB-C Charger 65W","slug":"usb-c-charger-65w","imageUrl":"/products/usb-c-charger-65w.svg","price":39.99,"currency":"USD","availability":"LOW_STOCK"},
             {"id":"%s","name":"Mechanical Keyboard","slug":"mechanical-keyboard","imageUrl":"/products/mechanical-keyboard.svg","price":129.00,"currency":"USD","availability":"IN_STOCK"},
             {"id":"%s","name":"27\\" 4K Monitor","slug":"monitor-27-4k","imageUrl":"/products/monitor-27-4k.svg","price":349.00,"currency":"USD","availability":"OUT_OF_STOCK"},
             {"id":"%s","name":"Insulated Water Bottle 1L","slug":"insulated-water-bottle-1l","imageUrl":"/products/insulated-water-bottle-1l.svg","price":27.50,"currency":"USD","availability":"IN_STOCK"}]"""
            .formatted(EARBUDS, CHARGER, KEYBOARD, MONITOR, BOTTLE);

    protected static RequestPostProcessor customer(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString())).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    protected static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("00000000-0000-4000-8000-0000000000a1")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
