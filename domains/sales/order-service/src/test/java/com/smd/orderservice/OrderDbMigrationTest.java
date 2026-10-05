package com.smd.orderservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Applies V1 (schema) and, in the {@code local} profile, V1000 (seed) to a real Postgres 16. */
class OrderDbMigrationTest extends OrderServiceIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void migrationsSucceed() {
        assertThat(jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("1", "1000");
    }

    @Test
    void allTablesExist() {
        assertThat(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class))
                .contains("orders", "order_items", "inventory", "stock_movements", "customer_wallets", "wallet_transactions", "payments", "outbox_event", "processed_event");
    }

    @Test
    void seedIsApplied() {
        assertThat(count("inventory")).isEqualTo(12);
        assertThat(count("customer_wallets")).isEqualTo(1);
    }
}
