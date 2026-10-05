package com.smd.orderservice.wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A customer's wallet with its latest ledger entries. */
public record WalletView(UUID userId, BigDecimal balance, String currency, List<Entry> recentTransactions) {

    public record Entry(String type, BigDecimal amount, UUID orderId, Instant at) {
    }

    WalletView withTransactions(List<Entry> entries) {
        return new WalletView(userId, balance, currency, entries);
    }
}
