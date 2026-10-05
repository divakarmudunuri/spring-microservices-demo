package com.smd.orderservice;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * Real Postgres (schema + local seed, reset before every test) and a WireMock server standing in for
 * user-service and product-service. Feign finds WireMock through Spring Cloud's simple discovery client.
 */
@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("local")
public abstract class OrderServiceIntegrationTest {

    protected static final UUID CUSTOMER = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    protected static final UUID EARBUDS = UUID.fromString("20000000-0000-4000-8000-000000000001");    // 79.99, stock 40
    protected static final UUID CHARGER = UUID.fromString("20000000-0000-4000-8000-000000000002");    // 39.99, stock 100
    protected static final UUID KEYBOARD = UUID.fromString("20000000-0000-4000-8000-000000000003");   // 129.00, stock 1
    protected static final UUID MONITOR = UUID.fromString("20000000-0000-4000-8000-000000000004");    // 349.00, stock 0

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.15")
            .withDatabaseName("order_db")
            .withUsername("order_svc");

    protected static final WireMockServer DOWNSTREAM = new WireMockServer(options().dynamicPort());

    static {
        POSTGRES.start();
        DOWNSTREAM.start();
    }

    @DynamicPropertySource
    static void downstreamServices(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri", DOWNSTREAM::baseUrl);
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[0].uri", DOWNSTREAM::baseUrl);
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void resetDatabaseAndStubs() {
        jdbc.sql("""
                TRUNCATE outbox_event, processed_event, payments, wallet_transactions, stock_movements,
                         order_items, orders, customer_wallets, inventory RESTART IDENTITY CASCADE""").update();
        new ResourceDatabasePopulator(new ClassPathResource("db/seed/V1000__seed.sql")).execute(dataSource);

        DOWNSTREAM.resetAll();
        stubUser(CUSTOMER, "ACTIVE", true);
        DOWNSTREAM.stubFor(get(urlPathEqualTo("/api/products")).willReturn(okJson(PRODUCTS_JSON)));
    }

    protected static void stubUser(UUID id, String status, boolean withAddress) {
        String address = withAddress ? """
                {"fullName":"Demo Customer","line1":"100 Example Street","line2":"Apt 4B","city":"Detroit",
                 "state":"MI","postalCode":"48226","country":"US","phone":"+1-555-0100"}""" : "null";
        DOWNSTREAM.stubFor(get(urlPathMatching("/api/users/" + id)).willReturn(okJson("""
                {"id":"%s","email":"customer@demo.local","fullName":"Demo Customer","role":"CUSTOMER",
                 "status":"%s","defaultAddress":%s}""".formatted(id, status, address))));
    }

    protected static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(String json) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(json);
    }

    protected static final String PRODUCTS_JSON = """
            [{"id":"%s","name":"Wireless Earbuds","description":"...","price":79.99,"currency":"USD"},
             {"id":"%s","name":"USB-C Charger 65W","description":"...","price":39.99,"currency":"USD"},
             {"id":"%s","name":"Mechanical Keyboard","description":"...","price":129.00,"currency":"USD"},
             {"id":"%s","name":"27\\" 4K Monitor","description":"...","price":349.00,"currency":"USD"}]"""
            .formatted(EARBUDS, CHARGER, KEYBOARD, MONITOR);

    // ---- database helpers -------------------------------------------------------------------------

    protected int stock(UUID productId) {
        return jdbc.sql("SELECT quantity_on_hand FROM inventory WHERE product_id = ?").param(productId)
                .query(Integer.class).single();
    }

    protected BigDecimal balance(UUID userId) {
        return jdbc.sql("SELECT balance FROM customer_wallets WHERE user_id = ?").param(userId)
                .query(BigDecimal.class).single();
    }

    protected int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    /** order-events of one order, in the order they were written. */
    protected List<String> orderEvents(UUID orderId) {
        return jdbc.sql("""
                        SELECT event_type FROM outbox_event
                         WHERE topic = 'order-events' AND aggregate_id = ? ORDER BY created_at""")
                .param(orderId).query(String.class).list();
    }

    protected List<String> statuses() {
        return jdbc.sql("SELECT status FROM orders ORDER BY status").query(String.class).list();
    }
}
