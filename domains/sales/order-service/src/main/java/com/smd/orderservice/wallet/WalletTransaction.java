package com.smd.orderservice.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Wallet ledger row. {@code amount} is always positive; {@code type} gives the direction. */
@Entity
@Table(name = "wallet_transactions")
public class WalletTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID userId;

    private String type;

    private BigDecimal amount;

    private UUID orderId;

    private String idempotencyKey;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected WalletTransaction() {
        // for JPA
    }

    public static WalletTransaction refund(UUID userId, BigDecimal amount, UUID orderId) {
        WalletTransaction tx = new WalletTransaction();
        tx.userId = userId;
        tx.type = "REFUND";
        tx.amount = amount;
        tx.orderId = orderId;
        return tx;
    }

    public static WalletTransaction payment(UUID userId, BigDecimal amount, UUID orderId) {
        WalletTransaction tx = new WalletTransaction();
        tx.userId = userId;
        tx.type = "PAYMENT";
        tx.amount = amount;
        tx.orderId = orderId;
        return tx;
    }
}
