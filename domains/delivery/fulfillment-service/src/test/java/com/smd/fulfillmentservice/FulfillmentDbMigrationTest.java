package com.smd.fulfillmentservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Applies V1 (schema) and V2 (shipping_address) to a real Postgres 16. */
class FulfillmentDbMigrationTest extends FulfillmentIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void migrationsSucceed() {
        assertThat(jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("1", "2");
    }

    @Test
    void fulfillmentsKeepTheShippingAddress() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT data_type || ' ' || is_nullable FROM information_schema.columns
                 WHERE table_name = 'fulfillments' AND column_name = 'shipping_address'""", String.class))
                .isEqualTo("jsonb NO");
    }

    @Test
    void allTablesExist() {
        assertThat(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class))
                .contains("fulfillments", "fulfillment_items", "outbox_event", "processed_event");
    }
}
