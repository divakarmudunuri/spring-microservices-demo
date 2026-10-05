package com.smd.orderservice.wallet;

import java.math.BigDecimal;
import java.util.List;
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

    /** Customers get a wallet (balance 0) the first time they open it. Safe to call repeatedly or concurrently. */
    public void createIfMissing(UUID userId) {
        jdbc.sql("INSERT INTO customer_wallets (user_id) VALUES (:userId) ON CONFLICT (user_id) DO NOTHING")
                .param("userId", userId)
                .update();
    }

    public WalletView find(UUID userId) {
        return jdbc.sql("SELECT user_id, balance, currency FROM customer_wallets WHERE user_id = :userId")
                .param("userId", userId)
                .query((rs, n) -> new WalletView(rs.getObject("user_id", UUID.class), rs.getBigDecimal("balance"),
                        rs.getString("currency"), List.of()))
                .single();
    }

    public List<WalletView.Entry> recentTransactions(UUID userId, int limit) {
        return jdbc.sql("""
                        SELECT type, amount, order_id, created_at FROM wallet_transactions
                         WHERE user_id = :userId ORDER BY created_at DESC, id DESC LIMIT :limit""")
                .param("userId", userId)
                .param("limit", limit)
                .query((rs, n) -> new WalletView.Entry(rs.getString("type"), rs.getBigDecimal("amount"),
                        rs.getObject("order_id", UUID.class), rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
