package com.smd.orderservice.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Audit trail of every stock change. Sales are negative; cancellations and restocks are positive. */
@Entity
@Table(name = "stock_movements")
public class StockMovement {

    public static final String ORDER_CONFIRMED = "ORDER_CONFIRMED";
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String RESTOCK = "RESTOCK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID productId;

    private int delta;

    private String reason;

    private UUID orderId;

    private UUID performedBy;

    private String note;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected StockMovement() {
        // for JPA
    }

    /** An admin added stock: who did it and why are recorded. */
    public static StockMovement restock(UUID productId, int quantity, UUID adminId, String note) {
        StockMovement m = new StockMovement();
        m.productId = productId;
        m.delta = quantity;
        m.reason = RESTOCK;
        m.performedBy = adminId;
        m.note = note;
        return m;
    }

    /** Stock given back because the order was cancelled (positive delta). */
    public static StockMovement cancellation(UUID productId, int quantity, UUID orderId) {
        StockMovement m = new StockMovement();
        m.productId = productId;
        m.delta = quantity;
        m.reason = ORDER_CANCELLED;
        m.orderId = orderId;
        return m;
    }

    public static StockMovement sale(UUID productId, int quantity, UUID orderId) {
        StockMovement m = new StockMovement();
        m.productId = productId;
        m.delta = -quantity;
        m.reason = ORDER_CONFIRMED;
        m.orderId = orderId;
        return m;
    }
}
