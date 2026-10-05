package com.smd.shippingservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Applies V1 (schema) to a real Postgres 16. */
@SpringBootTest(properties = "eureka.client.enabled=false")
@Testcontainers
class ShippingDbMigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15")
            .withDatabaseName("shipping_db")
            .withUsername("shipping_svc");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migrationsSucceed() {
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("1");
    }

    @Test
    void allTablesExist() {
        assertThat(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class))
                .contains("shipments", "outbox_event", "processed_event");
    }
}
