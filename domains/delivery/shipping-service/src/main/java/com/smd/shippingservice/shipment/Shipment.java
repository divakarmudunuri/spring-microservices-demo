package com.smd.shippingservice.shipment;

import com.smd.shippingservice.events.ShippingAddress;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "shipments")
public class Shipment {

    @Id
    private UUID id;

    private UUID orderId;

    /** The customer who owns the order, from the event; used for ownership checks. */
    private UUID userId;

    private String trackingNumber;

    private String carrier;

    @Enumerated(EnumType.STRING)
    private ShipmentStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    private ShippingAddress shippingAddress;

    private LocalDate estimatedDelivery;

    private Instant deliveredAt;

    /** When the simulator should advance it; null once DELIVERED. */
    private Instant nextStepAt;

    @Version
    private long version;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected Shipment() {
        // for JPA
    }

    public static Shipment createLabel(UUID orderId, UUID userId, String trackingNumber, String carrier,
                                       ShippingAddress address, LocalDate estimatedDelivery, Instant nextStepAt) {
        Shipment s = new Shipment();
        s.id = UUID.randomUUID();
        s.orderId = orderId;
        s.userId = userId;
        s.trackingNumber = trackingNumber;
        s.carrier = carrier;
        s.status = ShipmentStatus.LABEL_CREATED;
        s.shippingAddress = address;
        s.estimatedDelivery = estimatedDelivery;
        s.nextStepAt = nextStepAt;
        return s;
    }

    /** One step forward; {@code nextStepAt} is ignored (and cleared) when the shipment becomes DELIVERED. */
    ShipmentStatus advance(Instant now, Instant nextStepAt) {
        if (status == ShipmentStatus.DELIVERED) {
            throw new IllegalStateException("Shipment " + id + " is already delivered");
        }
        status = status.next();
        if (status == ShipmentStatus.DELIVERED) {
            deliveredAt = now;
            this.nextStepAt = null;
        } else {
            this.nextStepAt = nextStepAt;
        }
        return status;
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

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public String getCarrier() {
        return carrier;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public ShippingAddress getShippingAddress() {
        return shippingAddress;
    }

    public LocalDate getEstimatedDelivery() {
        return estimatedDelivery;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
