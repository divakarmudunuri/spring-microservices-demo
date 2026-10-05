package com.smd.shippingservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Applies V1 (schema) to a real Postgres 16. */
class ShippingDbMigrationTest extends ShippingIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void migrationsSucceed() {
        assertThat(jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("1");
    }

    @Test
    void allTablesExist() {
        assertThat(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class))
                .contains("shipments", "outbox_event", "processed_event");
    }
}
