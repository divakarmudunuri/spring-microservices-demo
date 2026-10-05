package com.smd.userservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Applies V1 (schema) and, in the {@code local} profile, V1000 (seed) to a real Postgres 16. */
class UserDbMigrationTest extends PostgresIntegrationTest {

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
                .contains("users", "addresses");
    }

    @Test
    void seedIsApplied() {
        assertThat(count("users")).isEqualTo(2);
        assertThat(count("addresses")).isEqualTo(1);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
