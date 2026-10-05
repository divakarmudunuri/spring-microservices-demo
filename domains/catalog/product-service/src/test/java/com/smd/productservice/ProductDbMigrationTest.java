package com.smd.productservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Applies V1 (schema) and, in the {@code local} profile, V1000 (seed) to a real Postgres 16. */
@SpringBootTest(properties = "eureka.client.enabled=false")
@ActiveProfiles("local")
@Testcontainers
class ProductDbMigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15")
            .withDatabaseName("product_db")
            .withUsername("product_svc");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migrationsSucceed() {
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("1", "1000");
    }

    @Test
    void allTablesExist() {
        assertThat(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class))
                .contains("categories", "products", "product_availability", "processed_event");
    }

    @Test
    void seedIsApplied() {
        assertThat(count("categories")).isEqualTo(3);
        assertThat(count("products")).isEqualTo(12);
        assertThat(count("product_availability")).isEqualTo(12);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
