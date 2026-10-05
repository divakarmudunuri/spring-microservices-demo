package com.smd.productservice.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Read model of stock levels, built from {@code inventory-events} (phase 12).
 * order-service owns the exact counts; this table only holds a level.
 */
@Entity
@Table(name = "product_availability")
public class ProductAvailability {

    @Id
    private UUID productId;

    @Enumerated(EnumType.STRING)
    private AvailabilityLevel level;

    /** Events older than this are ignored. */
    private Instant lastEventAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected ProductAvailability() {
        // for JPA
    }

    public UUID getProductId() {
        return productId;
    }

    public AvailabilityLevel getLevel() {
        return level;
    }

    public Instant getLastEventAt() {
        return lastEventAt;
    }
}
