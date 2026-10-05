package com.smd.fulfillmentservice.fulfillment;

import com.smd.fulfillmentservice.events.OrderConfirmed;
import com.smd.fulfillmentservice.events.ShippingAddress;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "fulfillments")
public class Fulfillment {

    @Id
    private UUID id;

    private UUID orderId;

    /** The customer who owns the order, from the event envelope; passed on in every event. */
    private UUID userId;

    @Enumerated(EnumType.STRING)
    private FulfillmentStatus status;

    private String warehouseCode;

    @JdbcTypeCode(SqlTypes.JSON)
    private ShippingAddress shippingAddress;

    private String failureReason;

    /** When the simulator should advance it; null once PACKED or FAILED. */
    private Instant nextStepAt;

    @Version
    private long version;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "fulfillment", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FulfillmentItem> items = new ArrayList<>();

    protected Fulfillment() {
        // for JPA
    }

    public static Fulfillment receive(UUID orderId, UUID userId, String warehouseCode, OrderConfirmed order,
                                      Instant nextStepAt) {
        Fulfillment f = new Fulfillment();
        f.id = UUID.randomUUID();
        f.orderId = orderId;
        f.userId = userId;
        f.status = FulfillmentStatus.RECEIVED;
        f.warehouseCode = warehouseCode;
        f.shippingAddress = order.shippingAddress();
        f.nextStepAt = nextStepAt;
        order.items().forEach(line -> f.items.add(new FulfillmentItem(f, line.productId(), line.quantity())));
        return f;
    }

    void startPicking(Instant nextStepAt) {
        require(FulfillmentStatus.RECEIVED);
        status = FulfillmentStatus.PICKING;
        this.nextStepAt = nextStepAt;
    }

    void pack() {
        require(FulfillmentStatus.PICKING);
        status = FulfillmentStatus.PACKED;
        nextStepAt = null;
    }

    void fail(String reason) {
        require(FulfillmentStatus.RECEIVED);
        status = FulfillmentStatus.FAILED;
        failureReason = reason;
        nextStepAt = null;
    }

    private void require(FulfillmentStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Fulfillment " + id + " is " + status + ", expected " + expected);
        }
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

    public FulfillmentStatus getStatus() {
        return status;
    }

    public String getWarehouseCode() {
        return warehouseCode;
    }

    public ShippingAddress getShippingAddress() {
        return shippingAddress;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public List<FulfillmentItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
