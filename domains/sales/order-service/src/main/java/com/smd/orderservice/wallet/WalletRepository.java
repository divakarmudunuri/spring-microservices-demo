package com.smd.orderservice.wallet;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Customer wallets (the simulated payment method). Plain SQL for the atomic conditional debit. */
@Repository
public class WalletRepository {

    private final JdbcClient jdbc;

    public WalletRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Debits {@code amount} if, and only if, the balance covers it. A customer without a wallet row
     * (balance 0, not created yet) also gets {@code false}.
     */
    public boolean debit(UUID userId, BigDecimal amount) {
        return jdbc.sql("""
                        UPDATE customer_wallets
                           SET balance = balance - :amount, version = version + 1
                         WHERE user_id = :userId AND balance >= :amount""")
                .param("amount", amount)
                .param("userId", userId)
                .update() == 1;
    }

    /** Gives money back (refund). The wallet must exist: it was debited for the same order. */
    public void credit(UUID userId, BigDecimal amount) {
        int updated = jdbc.sql("""
                        UPDATE customer_wallets
                           SET balance = balance + :amount, version = version + 1
                         WHERE user_id = :userId""")
                .param("amount", amount)
                .param("userId", userId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("No wallet for user " + userId);
        }
    }
}
