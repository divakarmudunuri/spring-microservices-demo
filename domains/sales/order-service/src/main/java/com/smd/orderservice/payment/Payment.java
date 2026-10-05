package com.smd.orderservice.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One payment per order. Captured from the wallet inside the checkout transaction. */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    private UUID orderId;

    private UUID userId;

    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 3)
    private String currency;

    private String status;

    private Instant refundedAt;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected Payment() {
        // for JPA
    }

    public static Payment captured(UUID orderId, UUID userId, BigDecimal amount, String currency) {
        Payment p = new Payment();
        p.id = UUID.randomUUID();
        p.orderId = orderId;
        p.userId = userId;
        p.amount = amount;
        p.currency = currency;
        p.status = "CAPTURED";
        return p;
    }

    /** The money went back to the wallet. */
    public void refund(Instant at) {
        if (!"CAPTURED".equals(status)) {
            throw new IllegalStateException("Payment " + id + " is " + status + ", expected CAPTURED");
        }
        status = "REFUNDED";
        refundedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getStatus() {
        return status;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }
}
